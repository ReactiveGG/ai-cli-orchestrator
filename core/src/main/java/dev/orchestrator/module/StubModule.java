package dev.orchestrator.module;

import dev.orchestrator.domain.AiModule;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.domain.TokenUsage;

/**
 * Echoes the compiled prompt instead of calling a real tool. Used when the
 * backing CLI is not installed, and in tests.
 */
public final class StubModule implements AiModule {
    private final String name;
    private final String strategy;

    public StubModule(String name, String strategy) {
        this.name = name;
        this.strategy = strategy;
    }

    public static StubModule codex() {
        return new StubModule("codex", "static analysis oriented execution");
    }

    public static StubModule claude() {
        return new StubModule("claude", "implementation and review oriented execution");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "stub";
    }

    @Override
    public ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request, ExecutionContext context) {
        context.detail("[stub] " + name + " would run with prompt:");
        for (String line : prompt.body().split("\\R")) {
            context.detail(line);
        }
        String content = """
                Strategy: %s
                Prompt:
                %s
                """.formatted(strategy, prompt.body());
        long approxTokens = Math.max(1, prompt.body().length() / 4);
        return new ExecutionResult(name, prompt.taskType(), content.trim(), new TokenUsage(approxTokens, 0, 0.0));
    }
}
