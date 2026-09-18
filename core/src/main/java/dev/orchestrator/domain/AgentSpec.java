package dev.orchestrator.domain;

import java.util.function.Predicate;

/**
 * One agent inside a stage: which module (tool) runs it, optionally which role
 * (instructions) it plays, and optionally which model variant and effort level
 * the tool should use ({@code claude --model opus --effort high}).
 * {@code role == null} means "run the compiled prompt as is".
 */
/**
 * @param command slash command the agent starts with: {@code /plan} becomes the CLI's plan permission mode,
 *                any other {@code /name args} is put on the prompt's first line so Claude Code runs that
 *                project command or skill (non-interactive mode supports both). Null = none.
 */
public record AgentSpec(String role, String module, String model, String effort, String command) {
    public AgentSpec {
        if (module == null || module.isBlank()) {
            throw new IllegalArgumentException("agent module is required");
        }
        role = blankToNull(role);
        model = blankToNull(model);
        effort = blankToNull(effort);
        command = normalizeCommand(command);
    }

    public AgentSpec(String role, String module) {
        this(role, module, null, null, null);
    }

    public AgentSpec(String role, String module, String model, String effort) {
        this(role, module, model, effort, null);
    }

    private static final java.util.regex.Pattern COMMAND = java.util.regex.Pattern.compile("^/[A-Za-z0-9_:.-]+( [^\\r\\n]*)?$");

    /** Trims, requires the {@code /name [args]} shape on one line; blank → null. */
    static String normalizeCommand(String command) {
        String c = blankToNull(command);
        if (c == null) {
            return null;
        }
        if (!COMMAND.matcher(c).matches()) {
            throw new IllegalArgumentException("슬래시 명령은 '/이름' 또는 '/이름 인자' 한 줄이어야 합니다: " + c);
        }
        return c;
    }

    /** {@code claude} or {@code planner@claude}. */
    public String label() {
        return role == null ? module : role + "@" + module;
    }

    /** {@code claude}, {@code claude opus}, {@code claude opus/high}. */
    public String describeModel() {
        String base = model == null && effort == null ? module : module + " " + (model == null ? "기본" : model) + (effort == null ? "" : "/" + effort);
        return command == null ? base : base + " " + command;
    }

    public AgentOptions options() {
        return new AgentOptions(model, effort, command);
    }

    /**
     * Parses the YAML shorthand: {@code claude} (module only), {@code planner@claude},
     * or {@code planner} (a known role on {@code defaultModule}). A {@code :model}
     * suffix picks the model and {@code /effort} the effort: {@code claude:opus/high}.
     */
    public static AgentSpec parse(String text, String defaultModule, Predicate<String> isRole) {
        String s = text.trim();
        String command = null;
        int space = s.indexOf(' ');
        if (space > 0 && s.charAt(space + 1 < s.length() ? space + 1 : space) == '/') {
            command = s.substring(space + 1).trim();   // "claude:opus/high /plan"
            s = s.substring(0, space).trim();
        }
        String role = null;
        int at = s.indexOf('@');
        if (at >= 0) {
            role = s.substring(0, at);
            s = s.substring(at + 1);
        }
        String effort = null;
        int slash = s.indexOf('/');
        if (slash >= 0) {
            effort = s.substring(slash + 1);
            s = s.substring(0, slash);
        }
        String model = null;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            model = s.substring(colon + 1);
            s = s.substring(0, colon);
        }
        if (role == null && isRole.test(s)) {
            if (defaultModule == null || defaultModule.isBlank()) {
                throw new IllegalArgumentException("Role '" + s + "' needs a module: write role@module or set defaultModule");
            }
            return new AgentSpec(s, defaultModule, model, effort, command);
        }
        return new AgentSpec(role, s, model, effort, command);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
