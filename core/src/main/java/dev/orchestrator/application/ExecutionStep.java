package dev.orchestrator.application;

import java.util.List;

/**
 * One agent node of the execution graph. Every agent of stage N depends on
 * every agent of stage N-1; stage 1 depends on nothing. Housekeeping (prompt
 * compilation, aggregation) is not a step: it logs against {@link #SYSTEM}.
 *
 * @param stage 1-based stage index
 */
public record ExecutionStep(String id, String label, String moduleName, String role, int stage, List<String> dependsOn) {
    /** Pseudo step for log lines that belong to no agent (never part of a plan). */
    public static final ExecutionStep SYSTEM = new ExecutionStep("system", "시스템", null, null, 0, List.of());

    /**
     * @param duplicateIndex 0 for the first agent with this role@module in the stage; higher
     *                       values get a {@code #n} suffix so ids stay unique
     */
    public static ExecutionStep agent(int stage, int duplicateIndex, String stageName, String role, String roleLabel, String module, List<String> dependsOn) {
        String id = "s" + stage + "/" + (role == null ? module : role + "@" + module) + (duplicateIndex > 0 ? "#" + duplicateIndex : "");
        String label = (role == null ? stageName : (roleLabel == null ? role : roleLabel)) + " (" + module + ")";
        return new ExecutionStep(id, label, module, role, stage, List.copyOf(dependsOn));
    }

    public ExecutionStep withModule(String newModule) {
        return new ExecutionStep(id, label, newModule, role, stage, dependsOn);
    }

    public boolean isModuleStep() {
        return moduleName != null;
    }

    public boolean isSystem() {
        return this == SYSTEM || "system".equals(id);
    }
}
