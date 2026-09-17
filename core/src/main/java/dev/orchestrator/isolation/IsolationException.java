package dev.orchestrator.isolation;

/** A git/worktree operation needed for candidate isolation failed. */
public class IsolationException extends RuntimeException {
    public IsolationException(String message) {
        super(message);
    }

    public IsolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
