package dev.orchestrator.application;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * of the next stage receives all earlier outputs. Progress goes to an
 * {@link ExecutionObserver} (called from several threads during a parallel stage).
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

    public ExecutionManager(PromptCompiler promptCompiler, FlowConfig config, AiModule... modules) {
        this(promptCompiler, config, Duration.ofMinutes(10), Duration.ofSeconds(60), modules);
    }

    public ExecutionManager(
            PromptCompiler promptCompiler,
            FlowConfig config,
            Duration moduleTimeout,
            Duration idleWarning,
            AiModule... modules
    ) {
        this.promptCompiler = Objects.requireNonNull(promptCompiler);
        this.config = Objects.requireNonNull(config);
        this.moduleTimeout = Objects.requireNonNull(moduleTimeout);
        this.idleWarning = Objects.requireNonNull(idleWarning);
        this.modules = Arrays.stream(modules)
                .collect(Collectors.toMap(AiModule::name, module -> module, (left, right) -> left, LinkedHashMap::new));
    }

    public Map<String, AiModule> modules() {
        return Map.copyOf(modules);
    }

    public FlowConfig config() {
        return config;
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
            List<String> ids = new ArrayList<>();
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (AgentSpec agent : stage.agents()) {
                AgentRole role = effectiveRole(agent);
                String roleName = role == null ? null : role.name();
                int duplicateIndex = seen.merge(agent.module() + "@" + roleName, 0, (old, ignored) -> old + 1);
                ExecutionStep step = ExecutionStep.agent(stageIndex, duplicateIndex, stage.name(), roleName,
                        role == null ? null : role.labelKo(), agent.module(), agent.model(), agent.effort(), previous);
                plan.add(step);
                ids.add(step.id());
            }
            previous = ids;
        }
        return plan;
    }

    public ExecutionReport execute(ExecutionRequest request, ExecutionObserver observer, BooleanSupplier cancelled) {
        FlowDefinition flow = config.flow(request.flow());
        List<ExecutionStep> plan = plan(flow);
        observer.onPlan(plan);

        CompiledPrompt prompt = promptCompiler.compile(request, flow.taskType());
        observer.onDetail(ExecutionStep.SYSTEM, prompt.body());
        flow.modules().forEach(this::resolveModule);
        observer.onSummary(ExecutionStep.SYSTEM, "프리셋 " + flow.label() + " " + flow.signature() + ": " + flow.describe()
                + " · focus: " + String.join(", ", prompt.focus()));

        List<ExecutionResult> results = new ArrayList<>();
        List<ExecutionStep> stageSteps = plan.stream().filter(ExecutionStep::isModuleStep).toList();
        int cursor = 0;
        int stageIndex = 0;
        for (StageDefinition stage : flow.stages()) {
            stageIndex++;
            checkCancelled(cancelled, stage.name());
            List<ExecutionResult> previous = List.copyOf(results);
            List<Callable<ExecutionResult>> tasks = new ArrayList<>();
            AtomicBoolean stageFailed = new AtomicBoolean(false);
            BooleanSupplier stop = () -> cancelled.getAsBoolean() || stageFailed.get();
            for (AgentSpec agent : stage.agents()) {
                ExecutionStep step = stageSteps.get(cursor++);
                if (agent.role() != null) {
                    config.role(agent.role());   // fail fast on unknown roles
                }
                AgentRole role = effectiveRole(agent);
                CompiledPrompt stagePrompt = promptCompiler.compileForStage(prompt, role, previous);
                int currentStage = stageIndex;
                String roleName = role == null ? null : role.name();
                tasks.add(() -> {
                    try {
                        if (role != null || !previous.isEmpty()) {
                            observer.onDetail(step, stagePrompt.body());
                        }
                        return runModule(step, agent.module(), agent.options(), stagePrompt, request, observer, stop, true)
                                .at(currentStage, roleName);
                    } catch (RuntimeException e) {
                        stageFailed.set(true);
                        throw e;
                    }
                });
            }
            results.addAll(runStage(tasks));
        }

        ExecutionReport report = new ExecutionReport(request, flow, prompt, List.copyOf(results));
        observer.onSummary(ExecutionStep.SYSTEM, "완료: 에이전트 " + results.size() + "개, 토큰 " + report.totalUsage().totalTokens());
        return report;
    }

    /** The role that actually shapes the prompt: null for no role or a pass-through role such as {@code executor}. */
    private AgentRole effectiveRole(AgentSpec agent) {
        if (agent.role() == null) {
            return null;
        }
        AgentRole role = config.roles().get(agent.role());
        return role == null || role.isPassThrough() ? null : role;
    }

    /** Runs one stage's agents; a single agent runs inline, several run in parallel. */
    private List<ExecutionResult> runStage(List<Callable<ExecutionResult>> tasks) {
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
            for (Future<ExecutionResult> future : futures) {
                try {
                    out.add(future.get());
                } catch (ExecutionException e) {
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
                throw first;
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private ExecutionResult runModule(
            ExecutionStep step,
            String moduleName,
            AgentOptions options,
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
                + (options.effort() == null ? "" : ", effort " + options.effort()) + ")");
        ExecutionContext context = new ObserverContext(step, observer, cancelled, moduleTimeout, idleWarning, options);
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
                return runModule(step.withModule(fallback), fallback, AgentOptions.NONE, prompt, request, observer, cancelled, false);
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
            builder.append("[").append(result.label()).append("]").append(nl);
            builder.append(result.content()).append(nl).append(nl);
        }
        return builder.toString().trim();
    }

    private record ObserverContext(
            ExecutionStep step,
            ExecutionObserver observer,
            BooleanSupplier cancelled,
            Duration timeout,
            Duration idleWarning,
            AgentOptions options
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
