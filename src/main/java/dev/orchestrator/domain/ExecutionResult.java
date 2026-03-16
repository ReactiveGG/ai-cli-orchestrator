package dev.orchestrator.domain;

public record ExecutionResult(
        String moduleName,
        TaskType taskType,
        String content
) {
}
