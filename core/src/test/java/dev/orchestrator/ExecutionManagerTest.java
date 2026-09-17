package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.application.ExecutionManager;
import dev.orchestrator.application.ExecutionObserver;
import dev.orchestrator.application.ExecutionStep;
import dev.orchestrator.application.PromptCompiler;
import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.AgentSpec;
import dev.orchestrator.domain.AiModule;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ExecutionReport;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.domain.FlowDefinition;
import dev.orchestrator.domain.ModuleExecutionException;
import dev.orchestrator.domain.StageDefinition;
import dev.orchestrator.domain.TaskType;
import dev.orchestrator.domain.TokenUsage;
import dev.orchestrator.module.StubModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionManagerTest {
    private static ExecutionManager manager(FlowConfig config, AiModule... modules) {
        return new ExecutionManager(new PromptCompiler(), config, Duration.ofSeconds(5), Duration.ofSeconds(1), modules);
    }

    /** A flow with one role-less stage of two models, like the old verify command. */
    private static FlowConfig fanOut() {
        FlowDefinition verify = new FlowDefinition("verify", "검증", TaskType.VERIFY, "claude",
                List.of(StageDefinition.ofRole("실행", "executor", List.of("codex", "claude"))));
        FlowDefinition review = new FlowDefinition("review", "리뷰", TaskType.REVIEW, "claude",
                List.of(StageDefinition.ofRole("실행", "executor", List.of("claude"))));
        return new FlowConfig(Map.of("verify", verify, "review", review), Map.of(), Map.of());
    }

    @Test
    void verifyFlowRunsBothAgentsInParallelAndAggregates() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            public void onPlan(List<ExecutionStep> steps) {
                events.add("plan:" + steps.stream().map(ExecutionStep::id).toList());
            }

            @Override
            public void onStepFinished(ExecutionStep step, ExecutionResult result) {
                events.add("done:" + step.id());
            }
        };
        ExecutionManager manager = manager(fanOut(), StubModule.codex(), StubModule.claude());

        ExecutionReport report = manager.execute(new ExecutionRequest("verify", "loginService.ts", List.of(), "ko"), observer, () -> false);

        assertEquals("plan:[s1/codex, s1/claude]", events.get(0));
        assertEquals(List.of("codex", "claude"), report.labels());
        assertTrue(events.contains("done:s1/codex") && events.contains("done:s1/claude"));
        assertEquals(3, events.size(), "plan + one done per agent, nothing else");
        assertTrue(report.finalContent().contains("## codex") && report.finalContent().contains("## claude"));
        assertTrue(report.totalUsage().totalTokens() > 0);
    }

    @Test
    void stagesChainOutputsAndParallelReviewersBothFeedNextStage() {
        RecordingModule claude = new RecordingModule("claude");
        RecordingModule codex = new RecordingModule("codex");
        FlowDefinition flow = new FlowDefinition("ship", "배포 준비", TaskType.CUSTOM, "claude", List.of(
                StageDefinition.of("계획", new AgentSpec("planner", "claude")),
                StageDefinition.of("리뷰", new AgentSpec("reviewer", "claude"), new AgentSpec("reviewer", "codex")),
                StageDefinition.of("검증", new AgentSpec("verifier", "claude"))));
        FlowConfig config = new FlowConfig(Map.of("ship", flow), Map.of(), Map.of());
        ExecutionManager manager = manager(config, claude, codex);

        ExecutionReport report = manager.execute(new ExecutionRequest("ship", "release 1.4", List.of(), "ko"), ExecutionObserver.NOOP, () -> false);

        assertEquals(List.of("claude/planner", "claude/reviewer", "codex/reviewer", "claude/verifier"), report.labels());
        assertEquals(List.of(1, 2, 2, 3), report.results().stream().map(ExecutionResult::stage).toList());
        CompiledPrompt verifierPrompt = claude.prompts.get(claude.prompts.size() - 1);
        assertTrue(verifierPrompt.body().startsWith("역할: 검증자"));
        assertTrue(verifierPrompt.body().contains("## 이전 단계 1 결과: claude/planner"));
        assertTrue(verifierPrompt.body().contains("## 이전 단계 2 결과: claude/reviewer"));
        assertTrue(verifierPrompt.body().contains("## 이전 단계 2 결과: codex/reviewer"));
        assertTrue(codex.prompts.get(0).body().contains("output of claude/planner"));
        assertTrue(!claude.prompts.get(0).editsFiles(), "planner must not edit files");
        List<ExecutionStep> plan = manager.plan(flow);
        assertEquals(List.of(), plan.get(0).dependsOn());
        assertEquals(List.of("s1/planner@claude"), plan.stream().filter(s -> s.id().equals("s2/reviewer@claude")).findFirst().orElseThrow().dependsOn());
        assertEquals(List.of("s2/reviewer@claude", "s2/reviewer@codex"), plan.get(plan.size() - 1).dependsOn());
    }

    @Test
    void timeoutFallsBackToConfiguredModule() {
        AiModule slow = new AiModule() {
            @Override
            public String name() {
                return "claude";
            }

            @Override
            public ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request, ExecutionContext context) {
                throw new ModuleExecutionException(ModuleExecutionException.Kind.TIMEOUT, "claude", "too slow");
            }
        };
        FlowConfig config = new FlowConfig(fanOut().flows(), Map.of(), Map.of("claude", "codex"));
        ExecutionManager manager = manager(config, slow, StubModule.codex());
        List<String> failed = Collections.synchronizedList(new ArrayList<>());
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            public void onStepFailed(ExecutionStep step, Throwable error) {
                failed.add(step.id());
            }
        };

        ExecutionReport report = manager.execute(new ExecutionRequest("review", "x.ts", List.of(), "ko"), observer, () -> false);

        assertEquals(List.of("codex"), report.labels());
        assertEquals(List.of("s1/claude"), failed);
    }

    @Test
    void cancellationBeforeStageStopsRun() {
        ExecutionManager manager = manager(fanOut(), StubModule.codex(), StubModule.claude());

        ModuleExecutionException error = assertThrows(ModuleExecutionException.class,
                () -> manager.execute(new ExecutionRequest("verify", "x", List.of(), "ko"), ExecutionObserver.NOOP, () -> true));

        assertEquals(ModuleExecutionException.Kind.CANCELLED, error.kind());
    }

    @Test
    void failureInParallelStagePropagates() {
        AiModule broken = new AiModule() {
            @Override
            public String name() {
                return "codex";
            }

            @Override
            public ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request, ExecutionContext context) {
                throw new IllegalStateException("boom");
            }
        };
        ExecutionManager manager = manager(fanOut(), broken, StubModule.claude());

        ModuleExecutionException error = assertThrows(ModuleExecutionException.class,
                () -> manager.execute(new ExecutionRequest("verify", "x", List.of(), "ko"), ExecutionObserver.NOOP, () -> false));

        assertEquals(ModuleExecutionException.Kind.FAILED, error.kind());
        assertEquals("codex", error.moduleName());
    }

    @Test
    void bestOfThreePresetFansOutCodersAndReviewers() {
        RecordingModule claude = new RecordingModule("claude");
        ExecutionManager manager = manager(FlowConfig.defaultConfig(), claude);

        ExecutionReport report = manager.execute(new ExecutionRequest("best-of-3", "feature x", List.of(), "ko"), ExecutionObserver.NOOP, () -> false);

        assertEquals(List.of(1, 2, 2, 2, 3, 3, 3, 4), report.results().stream().map(ExecutionResult::stage).toList());
        assertEquals(8, claude.prompts.size());
        CompiledPrompt verifier = claude.prompts.get(7);
        assertEquals(3, verifier.body().split("## 이전 단계 2 결과", -1).length - 1, "verifier sees all three implementations");
        assertEquals(3, verifier.body().split("## 이전 단계 3 결과", -1).length - 1, "verifier sees all three reviews");
        List<ExecutionStep> plan = manager.plan(FlowConfig.defaultConfig().flow("best-of-3"));
        assertEquals(List.of("s2/coder@claude", "s2/coder@claude#1", "s2/coder@claude#2"),
                plan.stream().filter(s -> s.stage() == 2).map(ExecutionStep::id).toList());
    }

    @Test
    void renderMatchesLegacyTextLayout() {
        FlowDefinition flow = fanOut().flow("verify");
        ExecutionReport report = new ExecutionReport(new ExecutionRequest("verify", "x", List.of(), "ko"), flow, null, List.of(
                new ExecutionResult("codex", TaskType.VERIFY, "A", TokenUsage.ZERO).at(1, null),
                new ExecutionResult("claude", TaskType.VERIFY, "B", TokenUsage.ZERO).at(1, null)));

        String text = ExecutionManager.render(report);

        assertTrue(text.startsWith("Flow: verify"));
        assertTrue(text.contains("Modules: codex, claude"));
        assertTrue(text.contains("[codex]") && text.contains("[claude]"));
    }

    /** Records every prompt it receives so tests can check the hand-off. */
    private static final class RecordingModule implements AiModule {
        final List<CompiledPrompt> prompts = Collections.synchronizedList(new ArrayList<>());
        private final String name;

        RecordingModule(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request, ExecutionContext context) {
            prompts.add(prompt);
            return new ExecutionResult(name, request == null ? TaskType.CUSTOM : prompt.taskType(),
                    "output of " + name + (prompt.role() == null ? "" : "/" + prompt.role()), new TokenUsage(1, 1, 0));
        }
    }
}
