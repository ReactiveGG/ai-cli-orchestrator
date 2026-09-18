package dev.orchestrator.domain;

/**
 * Per-agent tool options chosen in the preset: model variant and effort level.
 * Null means "use the module's configured default".
 */
public record AgentOptions(String model, String effort, String command) {
    public static final AgentOptions NONE = new AgentOptions(null, null, null);

    public AgentOptions(String model, String effort) {
        this(model, effort, null);
    }

    /** {@code /plan} maps to the CLI's plan permission mode instead of being typed into the prompt. */
    public boolean planMode() {
        return "/plan".equals(command);
    }

    /**
     * {@code /resume <session-id>} → that id; {@code /continue} or a bare {@code /resume} → "" (the CLI's
     * {@code --continue}, most recent session in the workspace); null when not resuming.
     */
    public String resumeSession() {
        if (command == null) {
            return null;
        }
        if ("/continue".equals(command) || "/resume".equals(command)) {
            return "";
        }
        if (command.startsWith("/resume ")) {
            return command.substring("/resume ".length()).trim();
        }
        return null;
    }

    /** A slash command to put on the prompt's first line ({@code /review}, {@code /spec …}); null for the CLI-flag commands and none. */
    public String promptCommand() {
        return command == null || planMode() || resumeSession() != null ? null : command;
    }
}
