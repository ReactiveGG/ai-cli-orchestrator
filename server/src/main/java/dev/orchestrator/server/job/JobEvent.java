package dev.orchestrator.server.job;

import java.time.Instant;

public record JobEvent(long seq, Instant at, String jobId, JobEventLevel level, String stepId, String message) {
}
