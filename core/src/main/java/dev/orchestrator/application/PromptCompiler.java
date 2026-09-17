package dev.orchestrator.application;

import dev.orchestrator.domain.AgentRole;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.domain.TaskType;
import dev.orchestrator.isolation.CandidatePatch;
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
        return compileForStage(base, role, previous, StageContext.NONE);
    }

    /**
     * Extra context for competition mode.
     *
     * @param candidateIndex   this agent's own candidate number (coder: which candidate it is
     *                         producing; reviewer paired 1:1: which candidate it reviews), 0 = none
     * @param candidateCount   how many candidates compete, 0 = no competition
     * @param decisionRequired the agent must end with a {@code 채택: 후보 N} line
     * @param maxPatchChars    diff text per candidate is cut at this length
     */
    public record StageContext(int candidateIndex, int candidateCount, boolean decisionRequired, int maxPatchChars) {
        public static final StageContext NONE = new StageContext(0, 0, false, 40_000);
    }

    public CompiledPrompt compileForStage(CompiledPrompt base, AgentRole role, List<ExecutionResult> previous, StageContext ctx) {
        if (role != null && role.isPassThrough()) {
            role = null;
        }
        if (role == null && previous.isEmpty() && ctx.candidateCount() == 0) {
            return base;
        }
        String nl = System.lineSeparator();
        StringJoiner joiner = new StringJoiner(nl);
        if (role != null) {
            joiner.add(role.instructions().strip());
            if (role.editsFiles() && ctx.candidateCount() > 1) {
                joiner.add("- 당신은 후보 " + ctx.candidateIndex() + "/" + ctx.candidateCount()
                        + "로 다른 코더들과 경쟁 중이다. 같은 계획을 각자 구현하며, 리뷰어와 검증자가 가장 나은 후보 하나만 채택한다."
                        + " 이 디렉터리는 당신 전용 작업 공간이니 계획을 끝까지 구현하고 테스트를 통과시켜라.");
            }
            if (ctx.decisionRequired()) {
                joiner.add("- 반드시 마지막 줄에 `채택: 후보 N` 형식으로 채택할 후보 번호 하나를 적는다. 어느 후보도 받아들일 수 없으면 `채택: 후보 0`이라고 적고 이유를 밝힌다.");
            }
            joiner.add("");
            joiner.add("## 원래 요청");
        }
        joiner.add(base.body());
        List<ExecutionResult> candidates = previous.stream().filter(r -> r.candidate() > 0).toList();
        for (ExecutionResult result : previous) {
            if (result.candidate() > 0) {
                continue;   // candidates are rendered below with their diffs
            }
            joiner.add("");
            joiner.add("## 이전 단계 " + result.stage() + " 결과: " + result.label());
            joiner.add(result.content().isBlank() ? "(출력 없음)" : result.content().strip());
        }
        if (!candidates.isEmpty()) {
            boolean paired = ctx.candidateIndex() > 0 && !role.editsFiles();
            joiner.add("");
            joiner.add(paired
                    ? "## 검토 대상: 후보 " + ctx.candidateIndex() + " (이 디렉터리에 그대로 적용되어 있음). 다른 후보는 참고용 요약만 있다."
                    : "## 후보 " + candidates.size() + "개 (각각 독립 작업 공간에서 만든 구현안)");
            for (ExecutionResult c : candidates) {
                CandidatePatch patch = c.patch();
                boolean full = !paired || c.candidate() == ctx.candidateIndex();
                joiner.add("");
                joiner.add("### 후보 " + c.candidate() + " · " + c.label() + " · " + patch.summary());
                if (!full) {
                    continue;
                }
                joiner.add("코더 요약:");
                joiner.add(c.content().isBlank() ? "(출력 없음)" : c.content().strip());
                if (!patch.isEmpty()) {
                    joiner.add("변경 파일:");
                    joiner.add(patch.stat());
                    joiner.add("```diff");
                    joiner.add(truncate(patch.patch(), ctx.maxPatchChars(), "... (이하 생략, 전체 diff: " + patch.patchFile() + ")"));
                    joiner.add("```");
                }
            }
        }
        boolean edits = role != null ? role.editsFiles() : base.editsFiles();
        return new CompiledPrompt(base.taskType(), base.target(), base.focus(), base.responseLanguage(),
                joiner.toString(), role == null ? null : role.name(), edits);
    }

    private static String truncate(String text, int max, String marker) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + System.lineSeparator() + marker;
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
