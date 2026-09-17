package dev.orchestrator.server.job;

import java.time.Instant;
import java.util.List;

/**
 * @param stage     1-based stage index
 * @param candidate 1-based competing-candidate number for isolated coders, 0 otherwise
 */
public record JobStep(
        String id,
        String label,
        String module,
        String role,
        int stage,
        int candidate,
        List<String> dependsOn,
        StepStatus status,
        Instant startedAt,
        Instant finishedAt,
        String error
) {
    public JobStep with(StepStatus newStatus, Instant startedAt, Instant finishedAt, String error) {
        return new JobStep(id, label, module, role, stage, candidate, dependsOn, newStatus, startedAt, finishedAt, error);
    }

    public JobStep withModule(String newModule) {
        return new JobStep(id, label, newModule, role, stage, candidate, dependsOn, status, startedAt, finishedAt, error);
    }
}
