package dev.orchestrator.domain;

import java.util.Map;

/**
 * Instructions an agent prepends to its prompt when it plays this role.
 * Flows reference roles by name; the same role can appear in several flows.
 */
public record AgentRole(String name, String labelKo, String instructions) {
    public static final AgentRole PLANNER = new AgentRole("planner", "플래너", """
            역할: 플래너. 요청을 분석해 구현 계획만 작성한다.
            - 코드를 수정하지 않는다.
            - 변경할 파일, 순서, 위험 요소, 검증 방법을 번호 목록으로 정리한다.
            - 불명확한 요구사항은 가정을 명시한다.""");

    public static final AgentRole CODER = new AgentRole("coder", "코더", """
            역할: 코더. 이전 단계의 계획을 그대로 구현한다.
            - 계획에 없는 범위를 확장하지 않는다.
            - 변경한 파일과 이유를 마지막에 요약한다.
            - 테스트가 있으면 함께 수정하거나 추가한다.
            - 계획이 여러 개면 가장 구체적인 계획을 따르고 어느 것을 골랐는지 밝힌다.""");

    public static final AgentRole REVIEWER = new AgentRole("reviewer", "리뷰어", """
            역할: 리뷰어. 이전 단계의 변경 사항을 검토한다.
            - 코드를 수정하지 않고, 정확성/회귀/유지보수성 관점의 문제를 심각도 순으로 나열한다.
            - 각 문제에 파일과 위치, 재현 시나리오, 수정 제안을 붙인다.
            - 구현 후보가 여러 개면 각각 따로 평가하고, 가장 나은 후보와 그 이유를 명시한다.
            - 문제가 없으면 '승인'이라고 명시한다.""");

    public static final AgentRole VERIFIER = new AgentRole("verifier", "검증자", """
            역할: 검증자. 이전 단계들의 결론을 독립적으로 재검토한다.
            - 이전 결과를 신뢰하지 말고 직접 근거를 확인한다.
            - 리뷰가 여러 개면 서로 어긋나는 지적을 교차 검증해 어느 쪽이 맞는지 판정한다.
            - 구현 후보가 여러 개면 최종적으로 채택할 하나를 고르고 근거를 적는다.
            - 동의/반대 항목을 구분하고 남은 위험을 정리한다.""");

    /** No instructions: the model gets the compiled prompt as is. Used by the one-stage built-in flows. */
    public static final AgentRole EXECUTOR = new AgentRole("executor", "실행", "");

    /** The fixed pipeline every preset runs: planner → coder → reviewer → verifier. */
    public static final java.util.List<AgentRole> PIPELINE = java.util.List.of(PLANNER, CODER, REVIEWER, VERIFIER);

    public static final Map<String, AgentRole> BUILT_IN = Map.of(
            EXECUTOR.name(), EXECUTOR,
            PLANNER.name(), PLANNER,
            CODER.name(), CODER,
            REVIEWER.name(), REVIEWER,
            VERIFIER.name(), VERIFIER
    );

    /** True when the role adds no instructions (prompt passes through unchanged). */
    public boolean isPassThrough() {
        return instructions == null || instructions.isBlank();
    }

    /** Whether this role may edit files (only the coder does). */
    public boolean editsFiles() {
        return "coder".equals(name);
    }
}
