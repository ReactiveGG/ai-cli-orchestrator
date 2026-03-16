package dev.orchestrator;

import dev.orchestrator.application.ExecutionManager;
import dev.orchestrator.application.PromptCompiler;
import dev.orchestrator.application.TaskRouter;
import dev.orchestrator.config.RoutingConfig;
import dev.orchestrator.module.ClaudeModule;
import dev.orchestrator.module.CodexModule;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "ai",
        mixinStandardHelpOptions = true,
        description = "Routes development tasks to the appropriate AI module.",
        subcommands = {
                AnalyzeCommand.class,
                ImplementCommand.class,
                ReviewCommand.class,
                VerifyCommand.class
        }
)
public final class OrchestratorCommand implements Runnable {
    private final ExecutionManager executionManager;

    @Spec
    private CommandSpec spec;

    public OrchestratorCommand(ExecutionManager executionManager) {
        this.executionManager = executionManager;
    }

    static OrchestratorCommand createDefault() {
        RoutingConfig routingConfig = RoutingConfig.defaultConfig();
        TaskRouter taskRouter = new TaskRouter(routingConfig);
        ExecutionManager executionManager = new ExecutionManager(
                taskRouter,
                new PromptCompiler(),
                new CodexModule(),
                new ClaudeModule()
        );
        return new OrchestratorCommand(executionManager);
    }

    ExecutionManager executionManager() {
        return executionManager;
    }

    @Override
    public void run() {
        spec.commandLine().getOut().println("Use a task command: analyze, implement, review, verify");
    }
}
