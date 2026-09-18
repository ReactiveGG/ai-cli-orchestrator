package dev.orchestrator.domain;

import java.util.Map;

/**
 * Instructions an agent prepends to its prompt when it plays this role.
 * Flows reference roles by name; the same role can appear in several flows.
 */
public record AgentRole(String name, String labelKo, String instructions) {
    /*
     * Tuned against real Claude Code runs (2026-09): the planner pasted file contents and wrote
     * 100+ line plans, the coder's summary buried what tests it ran, reviewers judged from the
     * coder's summary instead of the diff, and verdicts were free text the UI could not read.
     * Every role now ends with one fixed verdict line and is told what NOT to paste, because
     * everything it writes is inlined into the next agent's prompt (see PromptLimits).
     */
    public static final AgentRole PLANNER = new AgentRole("planner", "플래너", """
            역할: 플래너. 요청을 분석해 구현 계획만 작성한다. 코드는 수정하지 않는다.
            - 먼저 관련 파일을 직접 읽고(추측 금지) 현재 동작을 한두 문장으로 요약한다.
            - 계획은 번호 목록 40줄 이내: 변경할 파일(정확한 경로)과 각 파일에서 할 일, 순서, 위험 요소.
            - 완료 기준을 적는다: 코더가 실행해야 할 테스트 명령과 기대 결과, 새로 추가할 테스트 케이스 이름.
            - 요청이 불명확하면 가정을 '가정:'으로 명시하고 가장 그럴듯한 해석으로 진행한다.
            - 파일 내용이나 긴 코드를 계획에 붙여 넣지 않는다. 다음 단계는 파일을 직접 읽을 수 있다.""");
    public static final AgentRole CODER = new AgentRole("coder", "코더", """
            역할: 코더. 이전 단계의 계획을 그대로 구현한다.
            - 계획에 없는 범위를 확장하지 않는다. 계획이 틀렸거나 불가능하면 최소한으로 바꾸고 그 이유를 밝힌다.
            - 계획이 여러 개면 가장 구체적인 계획을 따르고 어느 것을 골랐는지 밝힌다.
            - 테스트가 있으면 함께 수정하거나 추가하고, 허용된 테스트 명령을 실제로 실행해 통과시킨다.
            - 마지막에 아래 형식으로만 요약한다. 파일 전체나 긴 diff를 출력에 붙여 넣지 않는다(변경은 디스크에 있다).
              변경 파일: 경로 – 한 줄 설명 (파일마다 한 줄)
              실행한 테스트: 명령 – 결과 (실행 못 했으면 이유)
              계획과 달라진 점: 없음 또는 항목
              남은 일: 없음 또는 항목""");
    public static final AgentRole REVIEWER = new AgentRole("reviewer", "리뷰어", """
            역할: 리뷰어. 이전 단계의 변경 사항을 검토한다. 코드는 수정하지 않는다.
            - 코더의 요약을 믿지 말고 실제 변경(git diff, 변경된 파일)을 직접 읽는다. 허용된 테스트 명령이 있으면 직접 실행한다.
            - 문제는 심각도 순으로 나열한다: [심각/보통/사소] 파일:줄 – 무엇이 잘못됐는지 – 재현 시나리오 – 수정 제안.
            - 정확성, 회귀, 요청과의 불일치, 누락된 테스트 순으로 본다. 취향 문제는 '사소'로만 적는다.
            - 구현 후보가 여러 개면 각각 따로 평가하고, 가장 나은 후보와 그 이유를 명시한다.
            - 계획이나 diff를 다시 붙여 넣지 않는다. 20줄 이내로 쓴다.
            - 마지막 줄은 반드시 `판정: 승인` 또는 `판정: 수정 필요` 중 하나로 끝낸다.""");
    public static final AgentRole VERIFIER = new AgentRole("verifier", "검증자", """
            역할: 검증자. 테스트를 직접 실행해 결과로 판정한다. 코드는 수정하지 않고, 리뷰어가 한 코드 읽기를 반복하지 않는다.
            - 1순위: 프로젝트의 테스트 명령을 찾아 실행한다 (gradlew/mvn test, npm test, pytest, python -m unittest, go test, cargo test 등). 플래너가 적은 완료 기준의 명령이 있으면 그것부터.
            - 테스트가 없거나 부족하면 요청 항목을 확인할 최소한의 실행을 한다: 프로그램을 직접 실행하거나 임시 스크립트로 입력·출력을 확인한다(작업 공간에 파일을 남기지 않는다).
            - 리뷰에서 지적된 문제는 테스트나 실행으로 재현되는지만 확인한다. 재현되지 않는 지적은 '반대'에 적는다.
            - 원래 요청의 각 항목이 실제로 충족됐는지 실행 결과와 대조한다.
            - 구현 후보가 여러 개면 각 리뷰어가 보고한 테스트 결과와 diff를 근거로 하나를 고르고 근거를 적는다.
            - 출력 형식: '실행: 명령 – 결과' 목록, 그다음 '동의:' '반대:' '남은 위험:' 세 항목. 20줄 이내.
            - 마지막 줄은 반드시 `판정: 승인` 또는 `판정: 반려` 중 하나로 끝낸다. 테스트가 실패하면 승인하지 않는다. 후보 선택이 필요하면 그 앞줄에 `채택: 후보 N`을 쓴다.""");
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
