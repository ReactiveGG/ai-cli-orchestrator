package dev.orchestrator.domain;

import dev.orchestrator.isolation.CandidatePatch;

/**
 * @param role  the role the agent played, or null for a plain run
 * @param stage 1-based stage index within the flow
 * @param patch what this agent changed in its isolated candidate, or null when it ran in the workspace
 */
public record ExecutionResult(
        String moduleName,
        String role,
        int stage,
        TaskType taskType,
        String content,
        TokenUsage usage,
        CandidatePatch patch
) {
    public ExecutionResult {
        if (usage == null) {
            usage = TokenUsage.ZERO;
        }
    }

    public ExecutionResult(String moduleName, TaskType taskType, String content) {
        this(moduleName, null, 0, taskType, content, TokenUsage.ZERO, null);
    }

    public ExecutionResult(String moduleName, TaskType taskType, String content, TokenUsage usage) {
        this(moduleName, null, 0, taskType, content, usage, null);
    }

    public ExecutionResult at(int stageIndex, String roleName) {
        return new ExecutionResult(moduleName, roleName, stageIndex, taskType, content, usage, patch);
    }

    public ExecutionResult withPatch(CandidatePatch candidatePatch) {
        return new ExecutionResult(moduleName, role, stage, taskType, content, usage, candidatePatch);
    }

    /** 1-based candidate number, or 0 when this result is not a competing candidate. */
    public int candidate() {
        return patch == null ? 0 : patch.index();
    }

    /** {@code claude} or {@code claude/planner}. */
    public String label() {
        return role == null ? moduleName : moduleName + "/" + role;
    }
}
