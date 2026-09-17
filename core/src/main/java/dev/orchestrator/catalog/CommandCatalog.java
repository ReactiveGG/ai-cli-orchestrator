package dev.orchestrator.catalog;

import dev.orchestrator.domain.TaskType;
import java.util.List;

/**
 * Describes every task command and option with Korean labels. The CLI and the
 * web command palette both read from here so the two never drift apart.
 */
public final class CommandCatalog {
    public record OptionSpec(String flag, String type, boolean repeatable, String defaultValue, String labelKo, String descriptionKo) {
    }

    public record CommandSpec(String name, TaskType taskType, String labelKo, String descriptionKo,
                              String targetHintKo, List<String> defaultFocus, List<String> examples) {
    }

    public static final List<OptionSpec> OPTIONS = List.of(
            new OptionSpec("--focus", "string", true, null, "집중 영역",
                    "프롬프트에 강조할 관점. 여러 번 지정 가능 (예: security, architecture)."),
            new OptionSpec("--language", "string", false, "ko", "응답 언어",
                    "AI 응답 언어 코드. 기본값 ko.")
    );

    public static final List<CommandSpec> COMMANDS = List.of(
            new CommandSpec("analyze", TaskType.ANALYZE, "분석",
                    "코드나 아키텍처를 분석합니다. 기본 라우팅: codex.",
                    "파일 경로 또는 분석 대상 설명",
                    List.of("architecture", "dependencies", "risks"),
                    List.of("analyze auth.ts --focus security --focus architecture")),
            new CommandSpec("implement", TaskType.IMPLEMENT, "구현",
                    "기능이나 플로우를 구현합니다. 기본 라우팅: claude (파일 수정 허용).",
                    "구현할 기능을 자연어로",
                    List.of("requirements", "edge cases", "tests"),
                    List.of("implement jwt refresh token flow")),
            new CommandSpec("review", TaskType.REVIEW, "리뷰",
                    "변경 사항이나 소스 파일을 리뷰합니다. 기본 라우팅: claude.",
                    "파일 경로 또는 변경 범위",
                    List.of("correctness", "regressions", "maintainability"),
                    List.of("review src/service/ --focus correctness")),
            new CommandSpec("verify", TaskType.VERIFY, "검증",
                    "여러 AI 모듈로 교차 검증합니다. 기본 라우팅: codex, claude.",
                    "검증할 파일 또는 결과물",
                    List.of("cross-check findings", "test coverage", "release risk"),
                    List.of("verify loginService.ts"))
    );

    private CommandCatalog() {
    }

    public static CommandSpec byName(String name) {
        return COMMANDS.stream()
                .filter(command -> command.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown command: " + name));
    }
}
