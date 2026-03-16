package dev.orchestrator.module;

import dev.orchestrator.domain.AiModule;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;

public final class ClaudeModule implements AiModule {
    @Override
    public String name() {
        return "claude";
    }

    @Override
    public ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request) {
        String content = """
                Strategy: implementation and review oriented execution
                Prompt:
                %s
                """.formatted(prompt.body());
        return new ExecutionResult(name(), request.taskType(), content.trim());
    }
}
