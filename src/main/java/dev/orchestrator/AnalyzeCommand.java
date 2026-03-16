package dev.orchestrator;

import dev.orchestrator.domain.TaskType;
import picocli.CommandLine.Command;

@Command(name = "analyze", description = "Analyze code or architecture.")
final class AnalyzeCommand extends BaseTaskCommand {
    @Override
    protected TaskType taskType() {
        return TaskType.ANALYZE;
    }
}
