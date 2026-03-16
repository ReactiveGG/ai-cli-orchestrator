package dev.orchestrator.application;

import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionRequest;
import java.util.List;
import java.util.StringJoiner;

public final class PromptCompiler {
    public CompiledPrompt compile(ExecutionRequest request) {
        List<String> focus = request.focus().isEmpty() ? defaultFocus(request) : request.focus();
        StringJoiner joiner = new StringJoiner(System.lineSeparator());
        joiner.add("Task: " + request.taskType().name().toLowerCase());
        joiner.add("Target: " + request.target());
        joiner.add("Focus:");
        for (String item : focus) {
            joiner.add("- " + item);
        }
        joiner.add("Output language: " + request.responseLanguage());
        return new CompiledPrompt(request.taskType(), request.target(), focus, request.responseLanguage(), joiner.toString());
    }

    private List<String> defaultFocus(ExecutionRequest request) {
        return switch (request.taskType()) {
            case ANALYZE -> List.of("architecture", "dependencies", "risks");
            case IMPLEMENT -> List.of("requirements", "edge cases", "tests");
            case REVIEW -> List.of("correctness", "regressions", "maintainability");
            case VERIFY -> List.of("cross-check findings", "test coverage", "release risk");
        };
    }
}
