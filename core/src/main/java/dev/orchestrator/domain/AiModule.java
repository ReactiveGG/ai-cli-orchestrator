package dev.orchestrator.domain;

public interface AiModule {
    String name();

    /** Short description of how this module runs (shown on the dashboard). */
    default String description() {
        return name();
    }

    /** Whether the backing tool is reachable. Stub modules always are. */
    default boolean isAvailable() {
        return true;
    }

    ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request, ExecutionContext context);
}
