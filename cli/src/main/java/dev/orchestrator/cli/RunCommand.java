package dev.orchestrator.cli;

import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.ExecutionRequest;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "run", description = "Run the pipeline on a target: run <target> [--preset name].")
final class RunCommand implements Runnable {
    @ParentCommand
    private OrchestratorCommand parent;

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", description = "Task target or natural language request.")
    private String target;

    @Option(names = {"-p", "--preset"}, defaultValue = FlowConfig.DEFAULT_PRESET, description = "Preset name (see `presets`). Default: ${DEFAULT-VALUE}.")
    private String preset;

    @Option(names = "--focus", description = "Focus area for prompt compilation.")
    private List<String> focus = new ArrayList<>();

    @Option(names = "--language", defaultValue = "ko", description = "Response language.")
    private String language;

    @Override
    public void run() {
        String output = parent.executionManager().execute(new ExecutionRequest(preset, target, focus, language));
        spec.commandLine().getOut().println(output);
    }
}
