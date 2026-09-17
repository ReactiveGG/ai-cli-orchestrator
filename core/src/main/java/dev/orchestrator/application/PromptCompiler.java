package dev.orchestrator.application;

import dev.orchestrator.domain.AgentRole;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.domain.TaskType;
import java.util.List;
import java.util.StringJoiner;

public final class PromptCompiler {
    public CompiledPrompt compile(ExecutionRequest request, TaskType taskType) {
        List<String> focus = request.focus().isEmpty() ? defaultFocus(taskType) : request.focus();
        StringJoiner joiner = new StringJoiner(System.lineSeparator());
        joiner.add("Task: " + (taskType == TaskType.CUSTOM ? request.flow() : taskType.name().toLowerCase()));
        joiner.add("Target: " + request.target());
        joiner.add("Focus:");
        for (String item : focus) {
            joiner.add("- " + item);
        }
        joiner.add("Output language: " + request.responseLanguage());
        return new CompiledPrompt(taskType, request.target(), focus, request.responseLanguage(), joiner.toString());
    }

    /**
     * Specialises the base prompt for one agent of one stage: role instructions
     * (if any), the original request, then every earlier stage's outputs.
     */
    public CompiledPrompt compileForStage(CompiledPrompt base, AgentRole role, List<ExecutionResult> previous) {
        if (role != null && role.isPassThrough()) {
            role = null;
        }
        if (role == null && previous.isEmpty()) {
            return base;
        }
        String nl = System.lineSeparator();
        StringJoiner joiner = new StringJoiner(nl);
        if (role != null) {
            joiner.add(role.instructions().strip());
            joiner.add("");
            joiner.add("## 원래 요청");
        }
        joiner.add(base.body());
        for (ExecutionResult result : previous) {
            joiner.add("");
            joiner.add("## 이전 단계 " + result.stage() + " 결과: " + result.label());
            joiner.add(result.content().isBlank() ? "(출력 없음)" : result.content().strip());
        }
        boolean edits = role != null ? role.editsFiles() : base.editsFiles();
        return new CompiledPrompt(base.taskType(), base.target(), base.focus(), base.responseLanguage(),
                joiner.toString(), role == null ? null : role.name(), edits);
    }

    private List<String> defaultFocus(TaskType taskType) {
        return switch (taskType) {
            case ANALYZE -> List.of("architecture", "dependencies", "risks");
            case IMPLEMENT -> List.of("requirements", "edge cases", "tests");
            case REVIEW -> List.of("correctness", "regressions", "maintainability");
            case VERIFY -> List.of("cross-check findings", "test coverage", "release risk");
            case CUSTOM -> List.of("requirements", "risks", "verification");
        };
    }
}
