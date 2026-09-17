package dev.orchestrator.domain;

/** Token and cost accounting for one module execution (or an aggregate of several). */
public record TokenUsage(long inputTokens, long outputTokens, double costUsd) {
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0.0);

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                costUsd + other.costUsd
        );
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
