package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.application.ExecutionManager;
import dev.orchestrator.application.ExecutionObserver;
import dev.orchestrator.application.ExecutionStep;
import dev.orchestrator.application.IsolationSettings;
import dev.orchestrator.application.PromptCompiler;
import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.AiModule;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ExecutionReport;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.domain.FlowDefinition;
import dev.orchestrator.domain.ModuleExecutionException;
import dev.orchestrator.domain.TokenUsage;
import dev.orchestrator.isolation.GitWorktreeIsolation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end competition run with a fake module: 1 planner, 2 coders, 2 reviewers, 1 verifier. */
@DisabledOnOs(OS.WINDOWS)
class CompetitionModeTest {
    @TempDir
    Path tempDir;

    private Path workspace;
    private IsolationSettings isolation;

    /** Records the cwd and prompt of every call; coders write a file, the verifier adopts candidate 2. */
    private static final class FakeClaude implements AiModule {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<CompiledPrompt> prompts = Collections.synchronizedList(new ArrayList<>());
        volatile boolean failFirstCoder;
        volatile Path workspaceForSingleCoder;

        @Override
        public String name() {
            return "claude";
        }

        @Override
        public ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request, ExecutionContext context) {
            prompts.add(prompt);
            Path cwd = context.workingDirectory();
            calls.add(prompt.role() + "@" + (cwd == null ? "workspace" : cwd.getFileName()));
            String text;
            if ("coder".equals(prompt.role())) {
                Path dir = cwd != null ? cwd : workspaceForSingleCoder;
                String candidate = cwd == null ? "workspace" : cwd.getFileName().toString();
                if (failFirstCoder && "c1".equals(candidate)) {
                    throw new IllegalStateException("coder 1 crashed");
                }
                try {
                    Files.writeString(dir.resolve("feature.py"), "# implemented by " + candidate + "\n", StandardCharsets.UTF_8);
                    Files.writeString(dir.resolve("app.py"), "print('" + candidate + "')\n", StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                text = "구현 완료 (" + candidate + ")";
            } else if ("verifier".equals(prompt.role())) {
                text = "후보 1은 테스트가 없고 후보 2는 통과했다.\n채택: 후보 2";
            } else if ("reviewer".equals(prompt.role())) {
                text = "리뷰 OK (" + (cwd == null ? "workspace" : cwd.getFileName()) + ")";
            } else {
                text = "계획: feature.py를 만든다";
            }
            return new ExecutionResult("claude", prompt.taskType(), text, new TokenUsage(1, 1, 0));
        }
    }

    private static String git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
        }
        return out;
    }

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        git(workspace, "init", "-q");
        Files.writeString(workspace.resolve("app.py"), "print('v1')\n");
        git(workspace, "add", "-A");
        git(workspace, "commit", "-q", "-m", "init");
        isolation = new IsolationSettings(new GitWorktreeIsolation(tempDir.resolve("wt"), List.of()), workspace, tempDir.resolve("patches"), true, false, 40_000);
    }

    private ExecutionManager manager(FlowConfig config, AiModule module) {
        return new ExecutionManager(new PromptCompiler(), config, Duration.ofSeconds(10), Duration.ofSeconds(5), isolation, module);
    }

    private static FlowConfig preset(int coders, int reviewers) {
        FlowDefinition flow = FlowDefinition.preset("p", "p", "claude", List.of(
                List.of("claude"), Collections.nCopies(coders, "claude"), Collections.nCopies(reviewers, "claude"), List.of("claude")));
        return new FlowConfig(Map.of("p", flow), Map.of(), Map.of());
    }

    @Test
    void twoCodersCompeteAndTheAdoptedCandidateIsApplied() throws Exception {
        FakeClaude claude = new FakeClaude();
        List<String> summaries = Collections.synchronizedList(new ArrayList<>());
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            public void onSummary(ExecutionStep step, String line) {
                summaries.add(line);
            }
        };
        ExecutionManager manager = manager(preset(2, 2), claude);

        ExecutionReport report = manager.execute(new ExecutionRequest("p", "feature", List.of(), "ko"), observer, () -> false, "job-a");

        // planner in the workspace, coders in c1/c2, reviewers paired 1:1 in c1/c2, verifier in the workspace
        assertEquals(List.of("coder@c1", "coder@c2", "planner@workspace", "reviewer@c1", "reviewer@c2", "verifier@workspace"),
                claude.calls.stream().sorted().toList());
        assertEquals(2, report.candidates().size());
        assertEquals(2, report.chosenCandidate());
        assertTrue(report.applied());
        assertEquals("print('c2')\n", Files.readString(workspace.resolve("app.py")), "chosen candidate applied to workspace");
        assertEquals("# implemented by c2\n", Files.readString(workspace.resolve("feature.py")));
        assertTrue(git(workspace, "status", "--porcelain").contains("?? feature.py"), "applied as uncommitted changes");
        assertFalse(Files.exists(tempDir.resolve("wt").resolve("job-a")), "worktrees cleaned up");
        assertTrue(Files.isRegularFile(tempDir.resolve("patches").resolve("job-a").resolve("candidates").resolve("c1.patch")), "patches kept");
        assertTrue(report.decisionNote().startsWith("후보 2 적용됨"), report.decisionNote());
        assertTrue(report.finalContent().endsWith("> " + report.decisionNote()));

        // prompts: coder knows it competes; paired reviewer sees its own candidate's diff and the other's summary; verifier sees both diffs and the decision rule
        CompiledPrompt coder = claude.prompts.stream().filter(p -> "coder".equals(p.role())).findFirst().orElseThrow();
        assertTrue(coder.body().contains("경쟁 중"), coder.body());
        assertTrue(coder.editsFiles());
        List<CompiledPrompt> reviews = claude.prompts.stream().filter(p -> "reviewer".equals(p.role())).toList();
        assertTrue(reviews.stream().anyMatch(p -> p.body().contains("검토 대상: 후보 1") && p.body().contains("+print('c1')") && !p.body().contains("+print('c2')")), "reviewer 1 sees only candidate 1's diff");
        assertTrue(reviews.stream().anyMatch(p -> p.body().contains("검토 대상: 후보 2") && p.body().contains("### 후보 1 ·")), "reviewer 2 still sees candidate 1's summary line");
        CompiledPrompt verifier = claude.prompts.stream().filter(p -> "verifier".equals(p.role())).findFirst().orElseThrow();
        assertTrue(verifier.body().contains("채택: 후보 N"), "decision rule");
        assertTrue(verifier.body().contains("+print('c1')") && verifier.body().contains("+print('c2')"), "verifier sees every diff");
        assertTrue(verifier.body().contains("리뷰 OK (c1)") && verifier.body().contains("리뷰 OK (c2)"), "verifier sees the reviews");
        assertTrue(summaries.stream().anyMatch(l -> l.contains("경쟁 모드")));
        assertTrue(summaries.stream().anyMatch(l -> l.startsWith("후보 2 적용됨")));
        assertTrue(manager.plan(preset(2, 2).flow("p")).stream().filter(s -> s.stage() == 2).allMatch(s -> s.candidate() > 0));
    }

    @Test
    void reviewersNotMatchingCandidatesSeeAllDiffsFromTheWorkspace() throws Exception {
        FakeClaude claude = new FakeClaude();
        ExecutionManager manager = manager(preset(3, 1), claude);

        ExecutionReport report = manager.execute(new ExecutionRequest("p", "feature", List.of(), "ko"), ExecutionObserver.NOOP, () -> false, "job-b");

        assertEquals(3, report.candidates().size());
        assertTrue(claude.calls.contains("reviewer@workspace"));
        CompiledPrompt review = claude.prompts.stream().filter(p -> "reviewer".equals(p.role())).findFirst().orElseThrow();
        assertTrue(review.body().contains("## 후보 3개") && review.body().contains("+print('c3')"), review.body());
        assertEquals("print('c2')\n", Files.readString(workspace.resolve("app.py")));
    }

    @Test
    void failedCandidateIsDroppedWhenOthersSucceed() throws Exception {
        FakeClaude claude = new FakeClaude();
        claude.failFirstCoder = true;
        List<String> summaries = Collections.synchronizedList(new ArrayList<>());
        ExecutionObserver observer = new ExecutionObserver() {
            @Override
            public void onSummary(ExecutionStep step, String line) {
                summaries.add(line);
            }
        };

        ExecutionReport report = manager(preset(2, 1), claude).execute(new ExecutionRequest("p", "feature", List.of(), "ko"), observer, () -> false, "job-c");

        assertEquals(1, report.candidates().size());
        assertEquals(2, report.candidates().get(0).index());
        assertTrue(summaries.stream().anyMatch(l -> l.startsWith("경고: 후보 1개 실패")), summaries.toString());
        assertEquals(2, report.chosenCandidate());
        assertTrue(report.applied());
    }

    @Test
    void singleCoderRunsInWorkspaceWithoutIsolation() throws Exception {
        FakeClaude claude = new FakeClaude();
        claude.workspaceForSingleCoder = workspace;

        ExecutionReport report = manager(preset(1, 1), claude).execute(new ExecutionRequest("p", "feature", List.of(), "ko"), ExecutionObserver.NOOP, () -> false, "job-d");

        assertTrue(report.candidates().isEmpty());
        assertEquals(0, report.chosenCandidate());
        assertNull(report.decisionNote());
        assertTrue(claude.calls.contains("coder@workspace"));
        assertFalse(Files.exists(tempDir.resolve("wt")));
    }

    @Test
    void competitionRefusedBeforeAnyAgentWhenWorkspaceIsNotGit() throws Exception {
        Path plain = Files.createDirectories(tempDir.resolve("plain"));
        IsolationSettings settings = new IsolationSettings(new GitWorktreeIsolation(tempDir.resolve("wt2"), List.of()), plain, tempDir.resolve("p2"), true, false, 40_000);
        FakeClaude claude = new FakeClaude();
        ExecutionManager manager = new ExecutionManager(new PromptCompiler(), preset(2, 1), Duration.ofSeconds(10), Duration.ofSeconds(5), settings, claude);

        ModuleExecutionException error = assertThrows(ModuleExecutionException.class,
                () -> manager.execute(new ExecutionRequest("p", "feature", List.of(), "ko"), ExecutionObserver.NOOP, () -> false, "job-e"));

        assertTrue(error.getMessage().contains("git 저장소"), error.getMessage());
        assertTrue(claude.calls.isEmpty(), "no tokens spent");
    }
}
