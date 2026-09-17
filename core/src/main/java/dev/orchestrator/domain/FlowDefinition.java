package dev.orchestrator.domain;

import java.util.List;

/**
 * A named one-way pipeline: stages run in order, agents inside a stage in parallel.
 *
 * <p>Presets are flows over the fixed pipeline planner → coder → reviewer → verifier,
 * differing only in how many models each stage runs ({@link #signature()} such as {@code 1-1-2-1}).
 *
 * @param name          key used on the command line ({@code run <name> <target>})
 * @param label         display name
 * @param taskType      prompt-default hint; CUSTOM for user-made flows
 * @param defaultModule module for agents written as a bare role in YAML
 */
public record FlowDefinition(String name, String label, TaskType taskType, String defaultModule, List<StageDefinition> stages) {
    public FlowDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("flow name is required");
        }
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("flow '" + name + "' needs at least one stage");
        }
        stages = List.copyOf(stages);
        label = label == null || label.isBlank() ? name : label;
        taskType = taskType == null ? TaskType.fromFlowName(name) : taskType;
    }

    /**
     * Builds a preset over the fixed pipeline. {@code modelsPerStage} has one list per
     * pipeline role; an empty list means one run on {@code defaultModule}.
     */
    public static FlowDefinition preset(String name, String label, String defaultModule, List<List<String>> modelsPerStage) {
        List<StageDefinition> stages = new java.util.ArrayList<>();
        for (int i = 0; i < AgentRole.PIPELINE.size(); i++) {
            AgentRole role = AgentRole.PIPELINE.get(i);
            List<String> models = i < modelsPerStage.size() && !modelsPerStage.get(i).isEmpty() ? modelsPerStage.get(i) : List.of(defaultModule);
            stages.add(StageDefinition.ofRole(role.labelKo(), role.name(), models));
        }
        return new FlowDefinition(name, label, TaskType.CUSTOM, defaultModule, stages);
    }

    /** Agent count per stage, e.g. {@code 1-1-2-1}. */
    public String signature() {
        return stages.stream().map(s -> String.valueOf(s.agents().size())).reduce((a, b) -> a + "-" + b).orElse("");
    }

    /** Distinct module names in stage order. */
    public List<String> modules() {
        return stages.stream().flatMap(s -> s.agents().stream()).map(AgentSpec::module).distinct().toList();
    }

    /** {@code 플래너(claude) → 리뷰어(claude ∥ codex)}. */
    public String describe() {
        return stages.stream()
                .map(s -> s.name() + "(" + String.join(" ∥ ", s.models()) + ")")
                .reduce((a, b) -> a + " → " + b)
                .orElse("");
    }
}
