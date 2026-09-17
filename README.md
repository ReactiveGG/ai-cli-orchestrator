# AI CLI Orchestrator

여러 AI 코딩 도구(Claude Code, Codex CLI)를 하나의 인터페이스로 묶어, 작업 유형에 따라 적절한 모듈로 라우팅하는 Java 기반 오케스트레이터다. 터미널 CLI와 로컬 웹 대시보드 두 가지로 쓸 수 있다.

## 사용 기술

| 영역 | 기술 |
|---|---|
| 언어 / 런타임 | Java 21 |
| 빌드 | Gradle 9.4 (Kotlin DSL), 멀티모듈 (`core`, `cli`, `server`) |
| CLI | Picocli 4.7 |
| 서버 | Spring Boot 4.1 (Spring MVC, SSE 스트리밍) |
| 설정 | SnakeYAML (`orchestrator.yml` 프리셋 정의), Jackson (CLI JSON 이벤트 파싱) |
| 테스트 | JUnit 5, Spring Boot Test |
| 프론트엔드 | React 19, TypeScript, Vite 8 |
| UI | Tailwind CSS 4, cmdk (명령 팔레트), React Flow (`@xyflow/react`, 플로우 다이어그램), dnd-kit (드래그 앤 드롭 구성), Recharts (사용량 차트), lucide-react |
| 상태 / 통신 | TanStack Query, `EventSource` (Server-Sent Events) |
| AI 모듈 | `claude -p --output-format stream-json`, `codex exec --json` 서브프로세스 |

## 구성

```
core/    도메인, 프롬프트 컴파일러, 프리셋 설정(YAML), 실행 관리자, AI 모듈(CLI/스텁)
cli/     `ai run <target> [--preset name]`, `ai presets`
server/  Spring Boot: Job 큐, SSE 로그 스트림, 대시보드/구성 API, 웹 정적 서빙
web/     Vite + React 대시보드 (빌드 결과가 server에 번들됨)
```

파이프라인은 항상 **플래너 → 코더 → 리뷰어 → 검증자** 네 단계다. 바꾸는 것은 **각 단계에 에이전트(모델)를 몇 개 두느냐**뿐이고, 그 조합 하나가 **프리셋**이다. 같은 단계의 에이전트는 병렬로 돌고, 다음 단계는 결과를 전부 받는다.

| 프리셋 | 서명 | 뜻 |
|---|---|---|
| `default` | 1-1-1-1 | 기본 체인 |
| `cross-review` | 1-1-2-1 | 리뷰어 둘이 교차 리뷰, 검증자가 어긋나는 지적을 판정 |
| `best-of-3` | 1-3-3-1 | 구현 3안, 리뷰 3건, 검증자가 최선의 안을 선택 |

```
명령 → 프롬프트 컴파일 → [플래너 ×1] → [코더 ×3] → [리뷰어 ×3] → [검증자 ×1] → 결과 취합
```

## 빠른 시작

요구 사항: JDK 21, Node 22 (웹을 빌드할 때만), 그리고 실제 호출을 원하면 `claude` 또는 `codex` CLI가 PATH에 있어야 한다. CLI가 없으면 해당 모듈은 프롬프트를 되돌려주는 스텁으로 동작한다.

```bash
# 웹 의존성 (최초 1회)
cd web && npm install && cd ..

# 서버 + 웹 (http://localhost:8080)
./gradlew :server:bootRun

# 프론트만 핫 리로드로 개발 (http://localhost:5173, /api 는 8080으로 프록시)
cd web && npm run dev

# 서버 없이 가짜 데이터로 화면만 보기 (mock 모드, 작업 실행이 브라우저 안에서 시뮬레이션됨)
cd web && npm run dev:mock

# 터미널 CLI
./gradlew :cli:run --args="run 'jwt refresh token flow'"                          # default 1-1-1-1
./gradlew :cli:run --args="run auth.ts --preset cross-review --focus security"
./gradlew :cli:run --args="presets"                                                # 프리셋 목록

# 테스트 (웹 빌드 생략)
./gradlew test -PskipWeb
```

## 웹 대시보드

인터랙티브 프로토타입(가짜 데이터, 서버 불필요)은 `web/src/lib/mock.ts`가 제공하는 mock 모드와 같은 동작을 한다. 화면 구성과 드래그 앤 드롭을 먼저 검토할 때 `npm run dev:mock`을 쓴다.

- **대시보드**: 오늘/누적 토큰 사용량과 비용, 모듈별 사용량, 최근 7일 차트, Claude CLI 설치·버전과 Anthropic 상태 페이지, 작업 진행 현황.
- **명령창 (Ctrl+K)**: 프리셋 칩(1-1-1-1, 1-1-2-1, 1-3-3-1 …)을 고르고 대상만 입력한다 (`"jwt refresh" --focus security`). 줄마다 `--preset name`으로 따로 지정할 수도 있다. 한 줄이 Job 하나이고, Shift+Enter로 줄을 추가하면 여러 Job이 한 번에 큐에 들어간다. 실행 전 다이어그램 미리보기를 보여준다.
- **작업**: Job마다 프로세스 플로우 다이어그램(단계가 열, 병렬 에이전트가 행), **요약 로그**와 **상세 로그** 두 탭, 최종 결과.
- **구성**: 프리셋을 드래그 앤 드롭으로 만든다. "이 프리셋으로 실행" 버튼을 누르면 명령창이 그 프리셋으로 열린다.

## 프리셋 구성 (드래그 앤 드롭)

구성 화면에는 프리셋 목록, 모델 블록(claude, codex), 그리고 고정된 4개 단계 칸(플래너 | 코더 | 리뷰어 | 검증자)이 있다.

- 모델 블록을 단계 칸에 놓을 때마다 그 단계의 에이전트가 하나 늘어난다. 같은 모델을 여러 번 놓아도 된다(1-3-3-1은 claude 3개).
- 칸을 비우면 프리셋의 기본 모델 1개로 돈다. 칩을 다른 칸으로 끌어 옮길 수 있다.
- 프리셋 이름 옆에 `1-1-2-1` 같은 서명이 자동으로 붙는다. 프리셋은 추가(현재 것 복사)/복제/삭제한다.
- 단계별 역할 지시문(플래너/코더/리뷰어/검증자)은 모든 프리셋에 공통이며 "단계별 역할 지시문"에서 고친다. 리뷰어와 검증자 지시문에는 후보가 여러 개일 때 비교·선택하라는 내용이 들어 있다.
- **저장 및 적용**을 누르면 `~/.ai-orchestrator/orchestrator.yml`에 기록되어 다음 Job부터 반영된다.

같은 내용을 YAML로 직접 써도 된다.

```yaml
flows:
  cross-review:                      # run <target> --preset cross-review
    label: 교차 리뷰
    defaultModule: claude            # models 를 생략한 단계의 모델
    stages:
      - role: planner
      - role: coder
      - role: reviewer
        models: [claude, claude]     # 1-1-2-1
      - role: verifier
roles:                               # 지시문 덮어쓰기
  reviewer:
    instructions: "역할: 리뷰어. ..."
fallback:                            # 시간 초과 시 다른 모델로 1회 재시도
  claude: codex
```

## 오래 걸리는 작업 대응

| 장치 | 동작 | 설정 |
|---|---|---|
| 모듈 타임아웃 | 초과하면 프로세스 트리를 강제 종료하고 Job을 `TIMEOUT`으로 표시 | `orchestrator.module-timeout` (기본 10m) |
| 유휴 경고 | 출력이 일정 시간 없으면 요약 로그와 작업 목록에 경고 표시 | `orchestrator.idle-warning` (기본 60s) |
| 취소 | 대기 중이면 즉시 제거, 실행 중이면 서브프로세스 종료 | 웹 취소 버튼, `POST /api/jobs/{id}/cancel` |
| 폴백 | 타임아웃 시 지정한 다른 모듈로 1회 재시도 | `fallback` (YAML 또는 구성 화면) |
| 동시 실행 상한 | 초과분은 큐에서 대기 | `orchestrator.concurrency` (기본 2) |
| 예산 | Claude CLI 인자로 턴 수 등을 제한 | `orchestrator.modules.claude.extra-args: ["--max-turns", "30"]` |
| 알림 | Job 종료 시 브라우저 알림 | 브라우저 권한 허용 |

서버가 재시작되면 실행 중이던 Job은 `FAILED`로 표시되고 로그는 디스크에 남는다.

## 서버 설정 (`server/src/main/resources/application.yml`)

| 키 | 의미 | 기본값 |
|---|---|---|
| `orchestrator.data-dir` | Job 저장소와 `orchestrator.yml` 위치 | `~/.ai-orchestrator` |
| `orchestrator.workspace` | AI CLI가 실행되는 디렉터리(수정 대상 코드) | 서버 실행 위치 |
| `orchestrator.modules.<name>.mode` | `AUTO`(설치돼 있으면 CLI, 아니면 스텁) / `CLI` / `STUB` | `AUTO` |
| `orchestrator.modules.<name>.command` | 실행 파일 이름 또는 경로 | 모듈 이름 |
| `orchestrator.modules.claude.model` | `--model` 별칭/이름 (예: `sonnet`) | CLI 기본값 |
| `orchestrator.modules.claude.max-budget-usd` | 에이전트 1회 실행의 비용 상한 (`--max-budget-usd`) | 2.0 |
| `orchestrator.modules.claude.allowed-tools` | 묻지 않고 허용할 도구 패턴 (`--allowedTools`). 비대화형이라 목록에 없는 셸 명령은 거부됨 | git status/diff/log, 테스트 러너 등 |

### Claude CLI 실연동에서 확인된 동작

- 코더 단계만 `--permission-mode acceptEdits`로 돌아 파일을 수정한다. 플래너·리뷰어·검증자는 읽기만 가능하다.
- 비대화형 모드는 권한 프롬프트에 답할 수 없어서, `allowed-tools`에 없는 셸 명령은 자동 거부되고 요약 로그에 "권한 거부됨"으로 남는다. 테스트를 돌리게 하려면 프로젝트의 테스트 명령을 이 목록에 넣는다.
- 비용은 CLI가 계산한 API 정가 환산값(`total_cost_usd`)이다. 구독 로그인으로 쓰면 실제 청구가 아니라 사용량 창 소진의 상대 지표다. 사소한 실행도 시스템 프롬프트 캐시 생성 때문에 약 $0.2가 나온다.
- 서버를 Claude Code 세션 안에서 띄워도 자식 `claude`가 중첩 세션으로 오인하지 않도록 관련 환경 변수를 제거하고 실행한다.

Job 데이터는 `<data-dir>/jobs/<id>/`에 `job.json`, `summary.log`, `detail.log`로 남는다.

## API 요약

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/dashboard` | 사용량, 상태, 작업 카운트 |
| GET | `/api/catalog` | 프리셋(서명 포함), 옵션(한국어), 모듈, 역할 |
| GET/POST | `/api/jobs` | 목록 / 생성 (`commandLine` 또는 `target`+`flow`=프리셋) |
| POST | `/api/jobs/batch` | 여러 명령을 한 번에 |
| POST | `/api/jobs/preview` | 실행 없이 단계 그래프만 |
| POST | `/api/jobs/{id}/cancel` | 취소 |
| GET | `/api/jobs/{id}/events` | SSE: `job` 스냅샷 + `log` 이벤트 (`?after=seq`로 이어받기) |
| GET | `/api/jobs/{id}/logs?level=SUMMARY|DETAIL` | 로그 조회 |
| GET | `/api/events` | SSE: 전체 Job 변경 |
| GET/PUT | `/api/config/routing` | 프리셋 구성 조회/저장 (저장 시 즉시 반영) |

## 개발 메모

- WSL에서 저장소가 `/mnt/c` 아래에 있으면 Gradle이 파일 권한을 바꾸지 못해 실패할 수 있다. `~/.gradle/gradle.properties`에 `buildDirBase=/home/<you>/.cache/ai-cli-orchestrator`를 넣으면 빌드 출력이 리눅스 파일시스템으로 간다.
- Gradle configuration cache는 같은 이유로 꺼져 있다 (`gradle.properties`).
