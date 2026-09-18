package dev.orchestrator.domain;

import java.time.Instant;

/**
 * Subscription usage windows reported by the Claude CLI's {@code rate_limit_event}
 * (subscription accounts have a 5-hour and a 7-day window; utilization is 0..1).
 * Fields the event did not carry are null. {@code observedAt} is when we saw it.
 */
public record RateLimitInfo(
        String status,
        Double fiveHourUtilization,
        Instant fiveHourResetsAt,
        Double sevenDayUtilization,
        Instant sevenDayResetsAt,
        String rateLimitType,
        Instant observedAt
) {
    public boolean allowed() {
        return status == null || status.isBlank() || "allowed".equals(status);
    }
}
