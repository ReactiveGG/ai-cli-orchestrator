package dev.orchestrator.domain;

import java.util.List;

/**
 * @param flow  name of the {@link FlowDefinition} to run (e.g. {@code implement} or a user flow)
 */
public record ExecutionRequest(
        String flow,
        String target,
        List<String> focus,
        String responseLanguage
) {
    public ExecutionRequest {
        if (flow == null || flow.isBlank()) {
            throw new IllegalArgumentException("flow is required");
        }
        flow = flow.trim();
        focus = focus == null ? List.of() : List.copyOf(focus);
        responseLanguage = responseLanguage == null || responseLanguage.isBlank() ? "ko" : responseLanguage;
    }

    public ExecutionRequest(TaskType taskType, String target, List<String> focus, String responseLanguage) {
        this(taskType.name().toLowerCase(java.util.Locale.ROOT), target, focus, responseLanguage);
    }
}
