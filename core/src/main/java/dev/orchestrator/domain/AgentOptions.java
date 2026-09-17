package dev.orchestrator.domain;

/**
 * Per-agent tool options chosen in the preset: model variant and effort level.
 * Null means "use the module's configured default".
 */
public record AgentOptions(String model, String effort) {
    public static final AgentOptions NONE = new AgentOptions(null, null);
}
