package dev.orchestrator.domain;

import java.util.List;

/**
 * @param role       role this prompt was specialised for, or null
 * @param editsFiles hint for modules: whether the tool should be allowed to modify files
 */
public record CompiledPrompt(
        TaskType taskType,
        String target,
        List<String> focus,
        String responseLanguage,
        String body,
        String role,
        boolean editsFiles
) {
    public CompiledPrompt(TaskType taskType, String target, List<String> focus, String responseLanguage, String body) {
        this(taskType, target, focus, responseLanguage, body, null, taskType == TaskType.IMPLEMENT);
    }
}
