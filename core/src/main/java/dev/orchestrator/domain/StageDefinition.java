package dev.orchestrator.domain;

import java.util.List;

/**
 * One column of a flow: a role played by one or more models in parallel.
 * Every agent gets the same input; all their outputs are handed to the next stage.
 * (Mixed roles inside one stage are still representable for YAML compatibility.)
 */
public record StageDefinition(String name, List<AgentSpec> agents) {
    public StageDefinition {
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("stage '" + name + "' needs at least one model");
        }
        agents = List.copyOf(agents);
        name = name == null || name.isBlank() ? agents.get(0).label() : name;
    }

    public static StageDefinition of(String name, AgentSpec... agents) {
        return new StageDefinition(name, List.of(agents));
    }

    /** A stage of one role run by several models in parallel. */
    public static StageDefinition ofRole(String name, String role, List<String> models) {
        return new StageDefinition(name, models.stream().map(m -> new AgentSpec(role, m)).toList());
    }

    /** The role shared by every agent, or null when agents have no/mixed roles. */
    public String role() {
        String first = agents.get(0).role();
        return agents.stream().allMatch(a -> java.util.Objects.equals(a.role(), first)) ? first : null;
    }

    public List<String> models() {
        return agents.stream().map(AgentSpec::module).toList();
    }
}
