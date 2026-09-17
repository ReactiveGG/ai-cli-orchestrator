package dev.orchestrator.server.job;

import java.time.Instant;
import java.util.List;

/**
 * @param stage 1-based stage index for agent steps, 0 for compile/route/aggregate
 */
public record JobStep(
        String id,
        String label,
        String module,
        String role,
        int stage,
        List<String> dependsOn,
        StepStatus status,
        Instant startedAt,
        Instant finishedAt,
        String error
) {
    public JobStep with(StepStatus newStatus, Instant startedAt, Instant finishedAt, String error) {
        return new JobStep(id, label, module, role, stage, dependsOn, newStatus, startedAt, finishedAt, error);
    }

    public JobStep withModule(String newModule) {
        return new JobStep(id, label, newModule, role, stage, dependsOn, status, startedAt, finishedAt, error);
    }
}
