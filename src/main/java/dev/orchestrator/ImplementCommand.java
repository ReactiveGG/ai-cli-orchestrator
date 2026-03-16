package dev.orchestrator;

import dev.orchestrator.domain.TaskType;
import picocli.CommandLine.Command;

@Command(name = "implement", description = "Implement a feature or flow.")
final class ImplementCommand extends BaseTaskCommand {
    @Override
    protected TaskType taskType() {
        return TaskType.IMPLEMENT;
    }
}
