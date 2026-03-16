package dev.orchestrator.domain;

public interface AiModule {
    String name();

    ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request);
}
