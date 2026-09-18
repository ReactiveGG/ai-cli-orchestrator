package dev.orchestrator.application;

import dev.orchestrator.application.PromptCompiler.StageContext;
import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.AgentOptions;
import dev.orchestrator.domain.AgentRole;
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
import dev.orchestrator.isolation.Candidate;
import dev.orchestrator.isolation.CandidateDecision;
import dev.orchestrator.isolation.CandidatePatch;
import dev.orchestrator.isolation.IsolationException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Runs one request through its flow: compile the prompt, then each stage in
 * order. Agents inside a stage run in parallel on the same input; every agent
 * of the next stage receives all earlier outputs.
 *
 * <p><b>Competition mode:</b> when a file-editing stage (the coder) has N ≥ 2
 * agents and {@link IsolationSettings} are enabled, each coder works in its own
 * candidate worktree. Reviewers are paired 1:1 with candidates when the counts
 * match (they run inside "their" worktree), otherwise every reviewer sees every
 * candidate. The last stage must end with {@code 채택: 후보 N}; that candidate's
 * patch is applied to the workspace and the worktrees are removed.
 *
 * <p>A module that times out is retried once on its configured fallback module.
 * Cancellation is checked between stages and handed to modules via {@link ExecutionContext}.
 */
public final class ExecutionManager {
    private final PromptCompiler promptCompiler;
    private final Map<String, AiModule> modules;
    private final FlowConfig config;
    private final Duration moduleTimeout;
    private final Duration idleWarning;
    private final IsolationSettings isolationSettings;

    public ExecutionManager(PromptCompiler promptCompiler, FlowConfig config, AiModule... modules) {
        this(promptCompiler, config, Duration.ofMinutes(10), Duration.ofSeconds(60), IsolationSettings.DISABLED, modules);
    }

    public ExecutionManager(PromptCompiler promptCompiler, FlowConfig config, Duration moduleTimeout, Duration idleWarning, AiModule... modules) {
        this(promptCompiler, config, moduleTimeout, idleWarning, IsolationSettings.DISABLED, modules);
    }

    public ExecutionManager(
            PromptCompiler promptCompiler,
            FlowConfig config,
            Duration moduleTimeout,
            Duration idleWarning,
            IsolationSettings isolationSettings,
            AiModule... modules
    ) {
        this.promptCompiler = Objects.requireNonNull(promptCompiler);
        this.config = Objects.requireNonNull(config);
        this.moduleTimeout = Objects.requireNonNull(moduleTimeout);
        this.idleWarning = Objects.requireNonNull(idleWarning);
        this.isolationSettings = isolationSettings == null ? IsolationSettings.DISABLED : isolationSettings;
        this.modules = Arrays.stream(modules)
                .collect(Collectors.toMap(AiModule::name, module -> module, (left, right) -> left, LinkedHashMap::new));
    }

    public Map<String, AiModule> modules() {
        return Map.copyOf(modules);
    }

    public FlowConfig config() {
        return config;
    }

    public IsolationSettings isolationSettings() {
        return isolationSettings;
    }

    /** Convenience for the CLI: run and render as plain text. */
    public String execute(ExecutionRequest request) {
        return render(execute(request, ExecutionObserver.NOOP, () -> false));
    }

    /** Builds the step graph without running it (for previews). */
    public List<ExecutionStep> plan(ExecutionRequest request) {
        return plan(config.flow(request.flow()));
    }

    public List<ExecutionStep> plan(FlowDefinition flow) {
        List<ExecutionStep> plan = new ArrayList<>();
        List<String> previous = List.of();
        int stageIndex = 0;
        for (StageDefinition stage : flow.stages()) {
            stageIndex++;
            boolean competing = isCompetition(stage);
            List<String> ids = new ArrayList<>();
            Map<String, Integer> seen = new LinkedHashMap<>();
            int agentIndex = 0;
            for (AgentSpec agent : stage.agents()) {
                agentIndex++;
                AgentRole role = effectiveRole(agent);
                String roleName = role == null ? null : role.name();
                int duplicateIndex = seen.merge(agent.module() + "@" + roleName, 0, (old, ignored) -> old + 1);
                ExecutionStep step = ExecutionStep.agent(stageIndex, duplicateIndex, stage.name(), roleName,
                        role == null ? null : role.labelKo(), agent.module(), agent.model(), agent.effort(), previous);
                if (competing) {
                    step = step.asCandidate(agentIndex);
                }
                plan.add(step);
                ids.add(step.id());
            }
            previous = ids;
        }
        return plan;
    }

    /** A stage whose role edits files and that has several agents runs as a competition. */
    private boolean isCompetition(StageDefinition stage) {
        if (stage.agents().size() < 2) {
            return false;
        }
        AgentRole role = effectiveRole(stage.agents().get(0));
        return role != null && role.editsFiles();
    }

    public ExecutionReport execute(ExecutionRequest request, ExecutionObserver observer, BooleanSupplier cancelled) {
        return execute(request, observer, cancelled, UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * @param runId identifies this run for isolation artefacts (worktrees, patches); the server passes the job id
     */
    public ExecutionReport execute(ExecutionRequest request, ExecutionObserver observer, BooleanSupplier cancelled, String runId) {
        FlowDefinition flow = config.flow(request.flow());
        List<ExecutionStep> plan = plan(flow);
        observer.onPlan(plan);

        boolean competition = flow.stages().stream().anyMatch(this::isCompetition);
        if (competition) {
            if (!isolationSettings.enabled()) {
                throw new ModuleExecutionException(ModuleExecutionException.Kind.FAILED, "isolation",
                        "코더가 2개 이상인 프리셋은 작업 공간 격리가 필요합니다 (orchestrator.isolation 설정 확인)");
            }
            if (!isolationSettings.isolation().supports(isolationSettings.workspace())) {
                throw new ModuleExecutionException(ModuleExecutionException.Kind.FAILED, "isolation",
                        "병렬 코더는 git 저장소에서만 쓸 수 있습니다: " + isolationSettings.workspace()
                                + " (git init 하거나 코더를 1개로 줄이세요)");
            }
        }

        CompiledPrompt prompt = promptCompiler.compile(request, flow.taskType());
        observer.onDetail(ExecutionStep.SYSTEM, prompt.body());
        flow.modules().forEach(this::resolveModule);
        observer.onSummary(ExecutionStep.SYSTEM, "프리셋 " + flow.label() + " " + flow.signature() + ": " + flow.describe()
                + " · focus: " + String.join(", ", prompt.focus())
                + (competition ? " · 경쟁 모드(worktree 격리)" : ""));

        List<ExecutionResult> results = new ArrayList<>();
        List<CandidatePatch> candidates = new ArrayList<>();
        List<ExecutionStep> stageSteps = plan.stream().filter(ExecutionStep::isModuleStep).toList();
        Path patchDir = isolationSettings.enabled() ? isolationSettings.patchRoot().resolve(runId).resolve("candidates") : null;
        int cursor = 0;
        int stageIndex = 0;
        boolean cleanupNeeded = false;
        List<Candidate> competitionWorktrees = List.of();   // worktrees of the coder stage, for 1:1 reviewer pairing
        try {
            for (StageDefinition stage : flow.stages()) {
                stageIndex++;
                checkCancelled(cancelled, stage.name());
                List<ExecutionResult> previous = List.copyOf(results);
                boolean competing = isCompetition(stage);
                boolean lastStage = stageIndex == flow.stages().size();
                boolean decisionRequired = lastStage && !candidates.isEmpty();
                boolean paired = !competing && !candidates.isEmpty() && stage.agents().size() == candidates.size()
                        && !lastStage;

                List<Candidate> worktrees = List.of();
                if (competing) {
                    cleanupNeeded = true;
                    worktrees = isolationSettings.isolation().prepare(runId, isolationSettings.workspace(), stage.agents().size());
                    observer.onSummary(ExecutionStep.SYSTEM, "후보 작업 공간 " + worktrees.size() + "개 준비 (기준 "
                            + worktrees.get(0).baseCommit().substring(0, Math.min(10, worktrees.get(0).baseCommit().length())) + ")");
                }

                List<Callable<ExecutionResult>> tasks = new ArrayList<>();
                AtomicBoolean stageFailed = new AtomicBoolean(false);
                BooleanSupplier stop = () -> cancelled.getAsBoolean() || (!competing && stageFailed.get());
                int agentIndex = 0;
                for (AgentSpec agent : stage.agents()) {
                    agentIndex++;
                    ExecutionStep step = stageSteps.get(cursor++);
                    if (agent.role() != null) {
                        config.role(agent.role());   // fail fast on unknown roles
                    }
                    AgentRole role = effectiveRole(agent);
                    int candidateIndex = competing ? agentIndex : (paired ? agentIndex : 0);
                    StageContext ctx = new StageContext(candidateIndex, competing ? stage.agents().size() : candidates.size(),
                            decisionRequired, isolationSettings.maxPatchChars());
                    CompiledPrompt stagePrompt = promptCompiler.compileForStage(prompt, role, previous, ctx, note -> observer.onSummary(step, note));
                    Path cwd = competing ? worktrees.get(agentIndex - 1).workingDir()
                            : paired ? candidateWorkingDir(competitionWorktrees, candidates, agentIndex) : null;
                    Candidate candidate = competing ? worktrees.get(agentIndex - 1) : null;
                    int currentStage = stageIndex;
                    String roleName = role == null ? null : role.name();
                    tasks.add(() -> {
                        try {
                            if (role != null || !previous.isEmpty()) {
                                observer.onDetail(step, stagePrompt.body());
                            }
                            ExecutionResult result = runModule(step, agent.module(), agent.options(), cwd, stagePrompt, request, observer, stop, true)
                                    .at(currentStage, roleName);
                            if (candidate != null) {
                                CandidatePatch patch = isolationSettings.isolation().capture(candidate, patchDir);
                                observer.onSummary(step, "후보 " + candidate.index() + " 변경 추출: " + patch.summary());
                                result = result.withPatch(patch);
                                observer.onCandidate(step, patch, result);
                            }
                            return result;
                        } catch (RuntimeException e) {
                            stageFailed.set(true);
                            throw e;
                        }
                    });
                }
                List<ExecutionResult> stageResults = runStage(tasks, competing, observer);
                results.addAll(stageResults);
                if (competing) {
                    candidates = stageResults.stream().map(ExecutionResult::patch).filter(Objects::nonNull).toList();
                    competitionWorktrees = worktrees;
                }
            }

            ExecutionReport report = decide(request, flow, prompt, results, candidates, observer);
            observer.onSummary(ExecutionStep.SYSTEM, "완료: 에이전트 " + results.size() + "개, 토큰 " + report.totalUsage().totalTokens());
            return report;
        } finally {
            if (cleanupNeeded && !isolationSettings.keepWorktrees()) {
                try {
                    isolationSettings.isolation().cleanup(runId, isolationSettings.workspace());
                    observer.onSummary(ExecutionStep.SYSTEM, "후보 작업 공간 정리 완료 (patch 보존: " + patchDir + ")");
                } catch (IsolationException e) {
                    observer.onSummary(ExecutionStep.SYSTEM, "경고: 후보 작업 공간 정리 실패 – " + e.getMessage());
                }
            }
        }
    }

    /** Working directory of the candidate that reviewer {@code agentIndex} is paired with. */
    private static Path candidateWorkingDir(List<Candidate> worktrees, List<CandidatePatch> candidates, int agentIndex) {
        CandidatePatch patch = candidates.get(agentIndex - 1);
        return worktrees.stream().filter(c -> c.index() == patch.index()).map(Candidate::workingDir).findFirst().orElse(null);
    }

    /** Reads the last stage's decision, applies the chosen patch when configured, and builds the report. */
    private ExecutionReport decide(ExecutionRequest request, FlowDefinition flow, CompiledPrompt prompt,
                                   List<ExecutionResult> results, List<CandidatePatch> candidates, ExecutionObserver observer) {
        if (candidates.isEmpty()) {
            return new ExecutionReport(request, flow, prompt, List.copyOf(results));
        }
        int lastStage = results.get(results.size() - 1).stage();
        Optional<Integer> decision = results.stream().filter(r -> r.stage() == lastStage)
                .map(r -> CandidateDecision.parse(r.content())).filter(Optional::isPresent).map(Optional::get).findFirst();
        String note;
        int chosen = 0;
        boolean applied = false;
        if (decision.isEmpty()) {
            note = "채택 후보를 판독하지 못했습니다. 후보 patch를 보고 직접 적용하세요.";
        } else if (decision.get() == 0) {
            note = "검증자가 어느 후보도 채택하지 않았습니다.";
        } else {
            chosen = decision.get();
            int chosenIndex = chosen;
            Optional<CandidatePatch> patch = candidates.stream().filter(c -> c.index() == chosenIndex).findFirst();
            if (patch.isEmpty()) {
                note = "채택된 후보 " + chosen + "이(가) 존재하지 않습니다 (후보 " + candidates.size() + "개).";
                chosen = 0;
            } else if (patch.get().isEmpty()) {
                note = "채택된 후보 " + chosen + "은(는) 변경이 없습니다.";
            } else if (!isolationSettings.autoApply()) {
                note = "후보 " + chosen + " 채택됨 (자동 적용 꺼짐, patch: " + patch.get().patchFile() + ")";
            } else {
                try {
                    isolationSettings.isolation().apply(patch.get(), isolationSettings.workspace());
                    applied = true;
                    note = "후보 " + chosen + " 적용됨 (" + patch.get().summary() + ")";
                } catch (IsolationException e) {
                    note = "후보 " + chosen + " 적용 실패 – " + e.getMessage();
                }
            }
        }
        observer.onSummary(ExecutionStep.SYSTEM, note);
        return new ExecutionReport(request, flow, prompt, List.copyOf(results), List.copyOf(candidates), chosen, applied, note);
    }

    /**
     * Runs one stage's agents; a single agent runs inline, several run in parallel.
     * In a competition a failed candidate is dropped as long as one succeeds;
     * otherwise the first failure fails the stage.
     */
    private List<ExecutionResult> runStage(List<Callable<ExecutionResult>> tasks, boolean competing, ExecutionObserver observer) {
        if (tasks.size() == 1) {
            try {
                return List.of(tasks.get(0).call());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size(), runnable -> {
            Thread thread = new Thread(runnable, "stage-agent");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<ExecutionResult>> futures = new ArrayList<>();
            for (Callable<ExecutionResult> task : tasks) {
                futures.add(pool.submit(task));
            }
            List<ExecutionResult> out = new ArrayList<>();
            RuntimeException first = null;
            int failures = 0;
            for (Future<ExecutionResult> future : futures) {
                try {
                    out.add(future.get());
                } catch (ExecutionException e) {
                    failures++;
                    RuntimeException cause = e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
                    if (first == null || (cause instanceof ModuleExecutionException && !(first instanceof ModuleExecutionException))) {
                        first = cause;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ModuleExecutionException(ModuleExecutionException.Kind.CANCELLED, "stage", "interrupted", e);
                }
            }
            if (first != null) {
                if (competing && !out.isEmpty()
                        && !(first instanceof ModuleExecutionException me && me.kind() == ModuleExecutionException.Kind.CANCELLED)) {
                    observer.onSummary(ExecutionStep.SYSTEM, "경고: 후보 " + failures + "개 실패, 남은 " + out.size() + "개로 계속 – " + first.getMessage());
                } else {
                    throw first;
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The role that actually shapes the prompt: null for no role or a pass-through role such as {@code executor}. */
    private AgentRole effectiveRole(AgentSpec agent) {
        if (agent.role() == null) {
            return null;
        }
        AgentRole role = config.roles().get(agent.role());
        return role == null || role.isPassThrough() ? null : role;
    }

    private ExecutionResult runModule(
            ExecutionStep step,
            String moduleName,
            AgentOptions options,
            Path workingDirectory,
            CompiledPrompt prompt,
            ExecutionRequest request,
            ExecutionObserver observer,
            BooleanSupplier cancelled,
            boolean allowFallback
    ) {
        AiModule module = resolveModule(moduleName);
        observer.onStepStarted(step);
        observer.onSummary(step, moduleName + " 시작 (" + module.description()
                + (options.model() == null ? "" : ", model " + options.model())
                + (options.effort() == null ? "" : ", effort " + options.effort())
                + (workingDirectory == null ? "" : ", cwd " + workingDirectory) + ")");
        ExecutionContext context = new ObserverContext(step, observer, cancelled, moduleTimeout, idleWarning, options, workingDirectory);
        try {
            ExecutionResult result = module.execute(prompt, request, context);
            observer.onSummary(step, moduleName + " 완료: 입력 " + result.usage().inputTokens()
                    + " / 출력 " + result.usage().outputTokens() + " 토큰");
            observer.onStepFinished(step, result);
            return result;
        } catch (ModuleExecutionException error) {
            observer.onStepFailed(step, error);
            String fallback = config.fallbackFor(moduleName);
            if (allowFallback && error.kind() == ModuleExecutionException.Kind.TIMEOUT
                    && fallback != null && modules.containsKey(fallback)) {
                observer.onSummary(step, moduleName + " 시간 초과, 폴백 모듈 " + fallback + "(으)로 재시도");
                return runModule(step.withModule(fallback), fallback, AgentOptions.NONE, workingDirectory, prompt, request, observer, cancelled, false);
            }
            throw error;
        } catch (RuntimeException error) {
            observer.onStepFailed(step, error);
            throw new ModuleExecutionException(ModuleExecutionException.Kind.FAILED, moduleName, error.getMessage(), error);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled, String what) {
        if (cancelled.getAsBoolean()) {
            throw new ModuleExecutionException(ModuleExecutionException.Kind.CANCELLED, what, "Cancelled before " + what);
        }
    }

    private AiModule resolveModule(String moduleName) {
        AiModule module = modules.get(moduleName);
        if (module == null) {
            throw new IllegalArgumentException("Unknown AI module: " + moduleName);
        }
        return module;
    }

    public static String render(ExecutionReport report) {
        String nl = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("Flow: ").append(report.flow().name()).append(nl);
        builder.append("Target: ").append(report.request().target()).append(nl);
        builder.append("Modules: ").append(String.join(", ", report.labels())).append(nl).append(nl);
        for (ExecutionResult result : report.results()) {
            builder.append("[").append(result.label()).append(result.candidate() > 0 ? " 후보 " + result.candidate() : "").append("]").append(nl);
            builder.append(result.content()).append(nl).append(nl);
        }
        if (report.decisionNote() != null) {
            builder.append("> ").append(report.decisionNote()).append(nl);
        }
        return builder.toString().trim();
    }

    private record ObserverContext(
            ExecutionStep step,
            ExecutionObserver observer,
            BooleanSupplier cancelled,
            Duration timeout,
            Duration idleWarning,
            AgentOptions options,
            Path workingDirectory
    ) implements ExecutionContext {
        @Override
        public void detail(String line) {
            observer.onDetail(step, line);
        }

        @Override
        public void summary(String line) {
            observer.onSummary(step, line);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.getAsBoolean();
        }
    }
}
