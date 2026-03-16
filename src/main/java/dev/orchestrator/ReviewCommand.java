package dev.orchestrator;

import dev.orchestrator.domain.TaskType;
import picocli.CommandLine.Command;

@Command(name = "review", description = "Review changes or source files.")
final class ReviewCommand extends BaseTaskCommand {
    @Override
    protected TaskType taskType() {
        return TaskType.REVIEW;
    }
}
