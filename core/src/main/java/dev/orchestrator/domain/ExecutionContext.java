package dev.orchestrator.domain;

import java.time.Duration;

/**
 * Runtime hooks handed to an {@link AiModule} while it executes: where to send
 * log lines, whether the caller asked to stop, and how long it may run.
 */
public interface ExecutionContext {
    /** Verbose output (raw subprocess lines, tool calls). */
    void detail(String line);

    /** One-line, human-readable progress notes. */
    void summary(String line);

    boolean isCancelled();

    /** Hard limit for a single module execution. */
    Duration timeout();

    /** After this much silence the module should emit a warning via {@link #summary}. */
    Duration idleWarning();

    /** Model/effort chosen for this agent in the preset. */
    /** Subscription usage windows the CLI reported during this run (dashboard material). */
    default void rateLimit(RateLimitInfo info) {
    }

    default AgentOptions options() {
        return AgentOptions.NONE;
    }

    /** Directory the agent must run in (its isolated candidate worktree), or null for the module default. */
    default java.nio.file.Path workingDirectory() {
        return null;
    }

    static ExecutionContext noop() {
        return new ExecutionContext() {
            @Override
            public void detail(String line) {
            }

            @Override
            public void summary(String line) {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public Duration timeout() {
                return Duration.ofMinutes(10);
            }

            @Override
            public Duration idleWarning() {
                return Duration.ofSeconds(60);
            }
        };
    }
}
