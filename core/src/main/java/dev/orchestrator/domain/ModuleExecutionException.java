package dev.orchestrator.domain;

/** A module failed, timed out or was cancelled. {@link #kind()} tells which. */
public class ModuleExecutionException extends RuntimeException {
    public enum Kind { FAILED, TIMEOUT, CANCELLED }

    private final Kind kind;
    private final String moduleName;

    public ModuleExecutionException(Kind kind, String moduleName, String message) {
        super(message);
        this.kind = kind;
        this.moduleName = moduleName;
    }

    public ModuleExecutionException(Kind kind, String moduleName, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.moduleName = moduleName;
    }

    public Kind kind() {
        return kind;
    }

    public String moduleName() {
        return moduleName;
    }
}
