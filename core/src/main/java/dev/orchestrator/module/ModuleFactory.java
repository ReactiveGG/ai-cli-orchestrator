package dev.orchestrator.module;

import dev.orchestrator.domain.AiModule;

/** Builds the built-in modules according to a {@link ModuleMode}. */
public final class ModuleFactory {
    private ModuleFactory() {
    }

    public static AiModule create(String name, ModuleMode mode, CliModuleSettings settings) {
        AiModule cli = switch (name) {
            case "claude" -> new ClaudeCliModule(settings);
            case "codex" -> new CodexCliModule(settings);
            default -> throw new IllegalArgumentException("Unknown module: " + name);
        };
        AiModule stub = switch (name) {
            case "claude" -> StubModule.claude();
            case "codex" -> StubModule.codex();
            default -> throw new IllegalArgumentException("Unknown module: " + name);
        };
        return switch (mode) {
            case CLI -> cli;
            case STUB -> stub;
            case AUTO -> cli.isAvailable() ? cli : stub;
        };
    }
}
