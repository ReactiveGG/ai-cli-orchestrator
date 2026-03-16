# AI CLI Orchestrator

여러 AI 코딩 도구를 하나의 CLI 인터페이스로 묶어 작업 유형에 따라 적절한 모듈로 라우팅하는 Java 기반 오케스트레이터다.

## 현재 포함된 기본 구조

- Picocli 기반 CLI 엔트리포인트
- 작업 유형별 명령: `analyze`, `implement`, `review`, `verify`
- 기본 라우팅 규칙
- 프롬프트 컴파일러
- AI 모듈 인터페이스와 `codex` / `claude` 스텁 모듈
- 다중 모듈 검증 흐름의 최소 구현

## 실행 예시

```bash
./gradlew run --args="analyze auth.ts --focus security --focus architecture"
./gradlew run --args="implement jwt refresh token flow"
./gradlew run --args="verify loginService.ts"
```

## 목표

- 작업 중심 CLI 제공
- AI 모듈 교체 가능 구조 유지
- 설정 기반 라우팅과 규칙 확장
- 검증 가능한 실행 흐름 제공

## 프로젝트 구조

단일 모듈 Gradle 프로젝트로 구성되어 있으며, 모든 소스는 루트 `src/` 아래에 위치한다.
