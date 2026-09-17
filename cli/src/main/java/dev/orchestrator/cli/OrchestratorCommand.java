package dev.orchestrator.cli;

import dev.orchestrator.application.ExecutionManager;
import dev.orchestrator.application.IsolationSettings;
import dev.orchestrator.application.PromptCompiler;
import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.AiModule;
import dev.orchestrator.isolation.GitWorktreeIsolation;
import dev.orchestrator.module.CliModuleSettings;
import dev.orchestrator.module.ModuleFactory;
import dev.orchestrator.module.ModuleMode;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "ai",
        mixinStandardHelpOptions = true,
        description = "Runs a task through the planner -> coder -> reviewer -> verifier pipeline.",
        subcommands = {
                RunCommand.class,
                PresetsCommand.class
        }
)
public final class OrchestratorCommand implements Runnable {
    private final ExecutionManager executionManager;

    @Spec
    private CommandSpec spec;

    public OrchestratorCommand(ExecutionManager executionManager) {
        this.executionManager = executionManager;
    }

    /** Real CLIs when installed, stubs otherwise. Presets come from {@code ./orchestrator.yml} if present. */
    static OrchestratorCommand createDefault() {
        return create(ModuleMode.AUTO);
    }

    static OrchestratorCommand create(ModuleMode mode) {
        FlowConfig config = FlowConfig.loadOrDefault(Path.of("orchestrator.yml"));
        Path workspace = Path.of("").toAbsolutePath();
        Path home = Path.of(System.getProperty("user.home"), ".ai-orchestrator");
        AiModule codex = ModuleFactory.create("codex", mode, new CliModuleSettings("codex", List.of(), workspace));
        AiModule claude = ModuleFactory.create("claude", mode, new CliModuleSettings("claude", List.of(), workspace));
        IsolationSettings isolation = new IsolationSettings(
                new GitWorktreeIsolation(home.resolve("worktrees"), List.of("node_modules", ".venv", "venv", "target", "build", ".gradle")),
                workspace, home.resolve("cli-runs"), true, false, 40_000);
        ExecutionManager executionManager = new ExecutionManager(
                new PromptCompiler(), config, Duration.ofMinutes(10), Duration.ofSeconds(60), isolation, codex, claude);
        return new OrchestratorCommand(executionManager);
    }

    ExecutionManager executionManager() {
        return executionManager;
    }

    @Override
    public void run() {
        spec.commandLine().getOut().println("Use: run <target> [--preset name]. `presets` lists the presets.");
    }
}
