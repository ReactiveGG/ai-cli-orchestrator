package dev.orchestrator.application;

import dev.orchestrator.domain.ExecutionResult;

/**
 * Receives step-level progress from {@link ExecutionManager}. All methods have
 * no-op defaults so callers implement only what they need.
 */
public interface ExecutionObserver {
    ExecutionObserver NOOP = new ExecutionObserver() { };

    /** Called once, before any step, with the full ordered step plan. */
    default void onPlan(java.util.List<ExecutionStep> steps) {
    }

    default void onStepStarted(ExecutionStep step) {
    }

    default void onStepFinished(ExecutionStep step, ExecutionResult result) {
    }

    default void onStepFailed(ExecutionStep step, Throwable error) {
    }

    /** A competing coder finished and its changes were captured (after {@link #onStepFinished}). */
    default void onCandidate(ExecutionStep step, dev.orchestrator.isolation.CandidatePatch patch, ExecutionResult result) {
    }

    default void onRateLimit(ExecutionStep step, dev.orchestrator.domain.RateLimitInfo info) {
    }

    default void onSummary(ExecutionStep step, String line) {
    }

    default void onDetail(ExecutionStep step, String line) {
    }
}
