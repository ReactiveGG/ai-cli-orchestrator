package dev.orchestrator.domain;

import java.util.List;

public record ExecutionRequest(
        TaskType taskType,
        String target,
        List<String> focus,
        String responseLanguage
) {
}
