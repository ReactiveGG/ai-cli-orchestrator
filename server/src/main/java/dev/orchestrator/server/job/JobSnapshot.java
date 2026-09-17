package dev.orchestrator.server.job;

import dev.orchestrator.domain.TokenUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable view of a {@link Job} for the API and for persistence. */
public record JobSnapshot(
        String id,
        JobStatus status,
        String command,
        String flow,
        String flowLabel,
        String target,
        List<String> focus,
        String language,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant lastOutputAt,
        List<JobStep> steps,
        TokenUsage usage,
        Map<String, TokenUsage> usageByModule,
        String error,
        String result,
        long eventCount,
        List<JobCandidate> candidates,
        int chosenCandidate,
        boolean applied,
        String decisionNote
) {
    public int progressPercent() {
        if (steps == null || steps.isEmpty()) {
            return status.isTerminal() ? 100 : 0;
        }
        long done = steps.stream().filter(step -> step.status() == StepStatus.DONE).count();
        return (int) Math.round(100.0 * done / steps.size());
    }
}
