package dev.orchestrator.domain;

import java.util.function.Predicate;

/**
 * One agent inside a stage: which module runs it and, optionally, which role
 * (instructions) it plays. {@code role == null} means "run the compiled prompt as is".
 */
public record AgentSpec(String role, String module) {
    public AgentSpec {
        if (module == null || module.isBlank()) {
            throw new IllegalArgumentException("agent module is required");
        }
        role = role == null || role.isBlank() ? null : role;
    }

    /** {@code claude} or {@code planner@claude}. */
    public String label() {
        return role == null ? module : role + "@" + module;
    }

    /**
     * Parses the YAML shorthand: {@code claude} (module only), {@code planner@claude},
     * or {@code planner} (a known role on {@code defaultModule}).
     */
    public static AgentSpec parse(String text, String defaultModule, Predicate<String> isRole) {
        String s = text.trim();
        int at = s.indexOf('@');
        if (at >= 0) {
            return new AgentSpec(s.substring(0, at), s.substring(at + 1));
        }
        if (isRole.test(s)) {
            if (defaultModule == null || defaultModule.isBlank()) {
                throw new IllegalArgumentException("Role '" + s + "' needs a module: write role@module or set defaultModule");
            }
            return new AgentSpec(s, defaultModule);
        }
        return new AgentSpec(null, s);
    }
}
