package dev.orchestrator.domain;

/** Hint for prompt defaults. Built-in flows map 1:1; user-made flows are CUSTOM. */
public enum TaskType {
    ANALYZE, IMPLEMENT, REVIEW, VERIFY, CUSTOM;

    public static TaskType fromFlowName(String name) {
        if (name == null) {
            return CUSTOM;
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CUSTOM;
        }
    }
}
