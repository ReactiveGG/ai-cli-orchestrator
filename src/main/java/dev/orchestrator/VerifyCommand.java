package dev.orchestrator;

import dev.orchestrator.domain.TaskType;
import picocli.CommandLine.Command;

@Command(name = "verify", description = "Verify output with multiple AI modules.")
final class VerifyCommand extends BaseTaskCommand {
    @Override
    protected TaskType taskType() {
        return TaskType.VERIFY;
    }
}
