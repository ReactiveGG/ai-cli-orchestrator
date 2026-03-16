package dev.orchestrator.domain;

import java.util.List;

public record CompiledPrompt(
        TaskType taskType,
        String target,
        List<String> focus,
        String responseLanguage,
        String body
) {
}
