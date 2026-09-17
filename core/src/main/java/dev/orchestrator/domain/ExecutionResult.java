package dev.orchestrator.domain;

/**
 * @param role  the role the agent played, or null for a plain run
 * @param stage 1-based stage index within the flow
 */
public record ExecutionResult(
        String moduleName,
        String role,
        int stage,
        TaskType taskType,
        String content,
        TokenUsage usage
) {
    public ExecutionResult {
        if (usage == null) {
            usage = TokenUsage.ZERO;
        }
    }

    public ExecutionResult(String moduleName, TaskType taskType, String content) {
        this(moduleName, null, 0, taskType, content, TokenUsage.ZERO);
    }

    public ExecutionResult(String moduleName, TaskType taskType, String content, TokenUsage usage) {
        this(moduleName, null, 0, taskType, content, usage);
    }

    public ExecutionResult at(int stageIndex, String roleName) {
        return new ExecutionResult(moduleName, roleName, stageIndex, taskType, content, usage);
    }

    /** {@code claude} or {@code claude/planner}. */
    public String label() {
        return role == null ? moduleName : moduleName + "/" + role;
    }
}
