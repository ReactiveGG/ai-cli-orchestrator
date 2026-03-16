package dev.orchestrator;

import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.TaskType;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

abstract class BaseTaskCommand implements Runnable {
    @ParentCommand
    private OrchestratorCommand parent;

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", description = "Task target or natural language request.")
    private String target;

    @Option(names = "--focus", description = "Focus area for prompt compilation.")
    private List<String> focus = new ArrayList<>();

    @Option(names = "--language", defaultValue = "ko", description = "Response language.")
    private String language;

    protected abstract TaskType taskType();

    @Override
    public final void run() {
        ExecutionRequest request = new ExecutionRequest(taskType(), target, focus, language);
        String output = parent.executionManager().execute(request);
        spec.commandLine().getOut().println(output);
    }
}
