package dev.orchestrator.cli;

import dev.orchestrator.domain.FlowDefinition;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "presets", description = "List presets (agents per stage of planner -> coder -> reviewer -> verifier).")
final class PresetsCommand implements Runnable {
    @ParentCommand
    private OrchestratorCommand parent;

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        for (FlowDefinition flow : parent.executionManager().config().flows().values()) {
            spec.commandLine().getOut().printf("%-14s %-8s %-12s %s%n", flow.name(), flow.signature(), flow.label(), flow.describe());
        }
    }
}
