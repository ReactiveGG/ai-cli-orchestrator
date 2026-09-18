# AI CLI Orchestrator

[![CI](https://github.com/ReactiveGG/ai-cli-orchestrator/actions/workflows/ci.yml/badge.svg)](https://github.com/ReactiveGG/ai-cli-orchestrator/actions/workflows/ci.yml)

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

# 서버 + 웹 (http://localhost:47120)
./run.sh                       # Windows: run.cmd
./gradlew :server:bootRun      # 같은 명령

# 프론트만 핫 리로드로 개발 (http://localhost:5173, /api 는 47120으로 프록시)
cd web && npm run dev

# 서버 없이 가짜 데이터로 화면만 보기 (mock 모드, 작업 실행이 브라우저 안에서 시뮬레이션됨)
cd web && npm run dev:mock

# 터미널 CLI
./gradlew :cli:run --args="run 'jwt refresh token flow'"                          # default 1-1-1-1
./gradlew :cli:run --args="run auth.ts --preset cross-review --focus security"
./gradlew :cli:run --args="presets"                                                # 프리셋 목록

# 테스트 (웹 빌드 생략)
./gradlew test -PskipWeb
cd web && npm test -- --run        # 웹 단위 테스트 (vitest)
```

CI(GitHub Actions)는 push/PR마다 Ubuntu와 Windows에서 Java 테스트를, Ubuntu에서 웹 테스트·빌드를 돌린다.

### 단일 jar로 실행

```bash
./gradlew :server:bootJar -PskipWeb          # web/dist가 있으면 함께 담긴다 (없으면 cd web && npm run build 먼저)
java -jar server/build/libs/server-0.1.1.jar --orchestrator.workspace=/path/to/project
```

jar 하나에 서버와 웹 UI가 들어 있어 JDK 21만 있는 PC에 복사해 바로 띄울 수 있다. 인자는 `run.sh`와 같다.

### Windows 앱으로 실행 (설치형, Java 불필요)

GitHub Actions의 **Windows app (jpackage)** 잡이 비설치형 `AI-CLI-Orchestrator-windows-x64.zip`(약 60MB: 서버 jar 25MB + 필요한 모듈만 담은 Java 런타임)을 만든다. `v*` 태그를 푸시하면 GitHub 릴리스에 자동으로 붙는다. 풀어서 `AI CLI Orchestrator.exe`를 실행하면 된다. 설치형이 필요하면 Windows에서 `gradlew.bat :server:jpackage -PjpackageType=msi`(WiX 필요).

**"Windows의 PC 보호" 경고가 뜰 때**: 실행 파일에 코드 서명이 없어서 SmartScreen이 인터넷에서 받은 파일에 띄우는 경고다. 다음 중 하나로 넘어간다.
- 경고 창에서 **추가 정보 → 실행**. 한 번 허용하면 그 파일은 다시 묻지 않는다.
- zip을 풀기 **전에** 차단 해제: zip 파일 우클릭 → 속성 → 아래쪽 **"차단 해제"** 체크 → 확인. 그다음 풀면 exe에 "인터넷에서 받음" 표시가 붙지 않아 경고가 나오지 않는다. PowerShell로는 `Unblock-File .\AI-CLI-Orchestrator-windows-x64.zip`.
- 이미 풀었다면 폴더 전체를 `Get-ChildItem -Recurse "AI CLI Orchestrator" | Unblock-File`.
경고를 근본적으로 없애려면 코드 서명 인증서가 필요하다(CI에 `signtool` 단계를 붙이면 된다). 실행하면 서버가 뜨고 기본 브라우저에 http://localhost:47120 이 열리며, 트레이 아이콘의 "대시보드 열기 / 종료"로 다룬다. 이미 떠 있는데 아이콘을 또 누르면 브라우저만 다시 연다. 서버 로그는 `%USERPROFILE%\.ai-orchestrator\server.log`에 쌓인다. 직접 만들려면 Windows에서 `gradlew.bat :server:jpackage`(zip용 앱 폴더) 또는 `-PjpackageType=msi`.

### IntelliJ에서 실행

`.run/` 폴더에 공유 실행 구성이 들어 있어 프로젝트를 Gradle 프로젝트로 열면 실행 목록에 바로 나타난다. Community Edition에서도 된다.

- `server: bootRun (47120)` — 서버 + 웹 UI. 평소 이걸 쓴다.
- `server: ServerApplication (debug)` — 일반 Application 구성. 브레이크포인트를 걸 때. Program arguments에 `--orchestrator.workspace=...`
- `tests: gradlew test -PskipWeb` — CI와 같은 테스트

로그 위치, 설정 파일, 문제 해결은 [docs/operations.md](docs/operations.md)에 있다.

## 웹 대시보드

인터랙티브 프로토타입(가짜 데이터, 서버 불필요)은 `web/src/lib/mock.ts`가 제공하는 mock 모드와 같은 동작을 한다. 화면 구성과 드래그 앤 드롭을 먼저 검토할 때 `npm run dev:mock`을 쓴다.

- **대시보드**: 오늘/누적 토큰 사용량과 비용, 모듈별 사용량, 최근 7일 차트, Claude CLI 설치·버전·로그인 상태(`claude auth status`; 못 찾으면 찾아본 위치와 "다시 확인"·"실행 파일 설정", 로그인 안 됐으면 "로그인 창 열기", 로그인돼 있으면 "로그아웃" 버튼 — 이 PC의 터미널 claude도 함께 해제됨을 확인 창으로 안내), 구독 사용량(마지막 Claude 실행이 보고한 5시간·7일 창 사용률과 초기화 시각), Anthropic 상태 페이지, 작업 진행 현황.
- **명령창 (Ctrl+K)**: 프리셋 칩(1-1-1-1, 1-1-2-1, 1-3-3-1 …)을 고르고 대상만 입력한다 (`"jwt refresh" --focus security`). 줄마다 `--preset name`으로 따로 지정할 수도 있다. 한 줄이 Job 하나이고, Shift+Enter로 줄을 추가하면 여러 Job이 한 번에 큐에 들어간다. 실행 전 다이어그램 미리보기를 보여준다.
- **작업**: Job마다 프로세스 플로우 다이어그램(단계가 열, 병렬 에이전트가 행), **요약 로그**와 **상세 로그** 두 탭, 최종 결과.
- **상태 표시**: 헤더의 점이 SSE 연결 상태다(연결 중 / 실시간 연결 / 서버 연결 끊김). 서버는 15초마다 `ping` 이벤트를 보내고, 브라우저는 40초 동안 아무 프레임도 없으면 스트림을 끊긴 것으로 보고 다시 연다. 서버가 죽으면 상단에 배너가 뜨고, 다시 켜면 몇 초 안에 자동으로 복구된다. 목록·대시보드·구성 로딩 실패는 각 영역에 "다시 시도" 버튼과 함께 표시되고, 알 수 없는 프리셋·옵션 같은 입력 오류는 명령창 하단에 한국어로 나온다.
- **완료 알림**: 헤더의 종 아이콘을 누르면 브라우저 알림 권한을 요청하고(페이지 로드 시 자동으로 묻지 않음) 작업이 끝날 때 데스크톱 알림을 보낸다. 알림을 클릭하면 그 작업이 열린다. 설정은 브라우저별로 기억된다.
- **삭제 확인**: 작업의 "삭제"는 한 번 누르면 4초 동안 "삭제 확인"으로 바뀌고, 그 안에 다시 눌러야 지워진다.
- **URL**: `#dashboard`, `#jobs/<id>`, `#config`가 화면이고 `#command`는 명령창을 연 채로 시작한다. 다크 모드는 OS 설정을 따르며 실행 중 바뀌어도 반영된다. 400px 폭(모바일)에서도 가로 스크롤 없이 쓸 수 있다.
- **구성**: 프리셋을 드래그 앤 드롭으로 만든다. "이 프리셋으로 실행" 버튼을 누르면 명령창이 그 프리셋으로 열린다. 아래 **서버 설정**에서 작업 공간("찾아보기"를 누르면 서버가 도는 PC의 폴더 선택 창이 열린다. Windows·WSL·Linux(zenity/kdialog)·macOS), 동시 실행 수, 타임아웃, 모듈(모드·기본 모델·예산·허용 도구), 격리 옵션을 바꾸면 `<data-dir>/settings.yml`에 저장되고 새 작업부터 반영된다. `application.yml`은 기본값이고 화면에서 저장한 값이 우선한다.

## 프리셋 구성 (드래그 앤 드롭)

구성 화면에는 프리셋 목록, 모델 블록(claude, codex), 그리고 고정된 4개 단계 칸(플래너 | 코더 | 리뷰어 | 검증자)이 있다.

- 모델 블록을 단계 칸에 놓을 때마다 그 단계의 에이전트가 하나 늘어난다. 같은 모델을 여러 번 놓아도 된다(1-3-3-1은 claude 3개).
- 칩마다 **사용 모델**(`--model`: fable/opus/sonnet/haiku 별칭 또는 전체 이름)과 **에포트**(`--effort`: low/medium/high/xhigh/max)를 정할 수 있다. 비우면 CLI 기본값이다.
- 칸을 비우면 프리셋의 기본 모델 1개로 돈다. 칩을 다른 칸으로 끌어 옮길 수 있다.
- 프리셋 이름 옆에 `1-1-2-1` 같은 서명이 자동으로 붙는다. 프리셋은 추가(현재 것 복사)/복제/삭제한다.
- 칩마다 **슬래시 명령**을 줄 수 있다. YAML은 `claude:opus/high /plan` 또는 `{module: claude, command: /review}`.
  - `/plan`: 대화형 CLI의 /plan과 같은 계획 전용 권한 모드(`--permission-mode plan`). 플래너·리뷰어·검증자는 그 모드로 한 번 돈다. **코더에 주면 대화형 흐름을 두 번 실행으로 재현한다**: 1/2 plan 모드로 계획(읽기만) → 2/2 같은 세션을 `--resume`으로 이어받아 acceptEdits로 계획을 구현. 비대화형에서는 계획 승인을 눌러 줄 사람이 없어서 이렇게 나눈다. 결과에는 계획과 구현 요약이 함께 남고 토큰은 합산된다.
  - `/resume <세션 id>`: 그 세션을 이어받아 실행(`--resume`). `/continue` 또는 인자 없는 `/resume`: 그 작업 공간의 가장 최근 세션(`--continue`). 각 에이전트의 세션 id는 요약 로그의 "claude … · 세션 <id>" 줄에 남으므로 복사해 쓰면 된다. 세션은 서버가 도는 PC의 `~/.claude/projects`에 있는 것만 이어받을 수 있다.
  - 그 밖의 `/이름 [인자]`: 프롬프트 첫 줄에 들어가 작업 공간의 `.claude/commands/이름.md`나 스킬을 실행한다(비대화형 `claude -p`가 이를 지원함을 확인했다). `/compact`, `/clear`, `/model` 같은 대화형 UI 명령은 의미가 없다(모델은 칩에서 고른다).
- 단계별 역할 지시문(플래너/코더/리뷰어/검증자)은 모든 프리셋에 공통이며 "단계별 역할 지시문"에서 고친다. 기본 지시문은 실제 실행 결과를 보고 다듬은 것이다: 플래너는 파일을 먼저 읽고 40줄 이내 계획과 완료 기준(테스트 명령)을 쓰고, 코더는 `변경 파일 / 실행한 테스트 / 계획과 달라진 점 / 남은 일` 형식으로만 요약하며, 리뷰어는 요약 대신 실제 diff를 읽고 `판정: 승인|수정 필요`로, 검증자는 `동의/반대/남은 위험`과 `판정: 승인|반려`로 끝낸다. 모든 역할이 파일 내용을 출력에 붙여 넣지 않도록 되어 있다(다음 단계 프롬프트에 그대로 들어가므로). 후보가 여러 개일 때 비교·선택하라는 내용도 들어 있다. 지시문을 고친 뒤 기본으로 되돌리려면 "기본 프리셋으로"를 누른다.
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
        models: [claude, claude:sonnet]            # 1-1-2-1, 두 번째 리뷰어는 sonnet
      - role: verifier
        models: [{ module: claude, model: opus, effort: high }]   # 또는 claude:opus/high
roles:                               # 지시문 덮어쓰기
  reviewer:
    instructions: "역할: 리뷰어. ..."
fallback:                            # 시간 초과 시 다른 모델로 1회 재시도
  claude: codex
```

## 프롬프트 길이 제한

각 에이전트는 "역할 지시문 + 원래 요청 + 이전 단계 결과"를 받는다. 이전 단계 결과는 결과당 24,000자, 단계 합계 60,000자(약 20k 토큰, 200k 컨텍스트의 10% 이하)까지만 넣고, 넘치면 앞 70%와 뒤 30%를 남기고 가운데를 생략한다(결론은 보통 끝에 있으므로). 잘리면 요약 로그에 `이전 결과 '…' N자 → M자로 잘라 전달`이 남고, 전문은 작업 상세 로그에서 볼 수 있다. 경쟁 모드의 후보 diff는 별도로 `isolation.max-patch-chars`(40,000자)로 자른다.

왜 필요한가: `claude -p`는 도구를 부르며 여러 턴을 돌고 프롬프트를 매 턴 다시 보내므로 물려받은 텍스트가 길수록 비용이 비례해 늘고, 파일 전체를 출력에 붙인 코더나 diff를 통째로 인용한 리뷰어 하나가 다음 에이전트의 컨텍스트를 넘치게 할 수 있다. 값은 `orchestrator.prompt.*`로 조정한다(0 = 무제한).

## 오래 걸리는 작업 대응

| 장치 | 동작 | 설정 |
|---|---|---|
| 모듈 타임아웃 | 초과하면 프로세스 트리를 강제 종료하고 Job을 `TIMEOUT`으로 표시 | `orchestrator.module-timeout` (기본 25m) |
| 유휴 경고 | 출력이 일정 시간 없으면 요약 로그와 작업 목록에 경고 표시 | `orchestrator.idle-warning` (기본 60s) |
| 취소 | 대기 중이면 즉시 제거, 실행 중이면 서브프로세스 종료 | 웹 취소 버튼, `POST /api/jobs/{id}/cancel` |
| 폴백 | 타임아웃 시 지정한 다른 모듈로 1회 재시도 | `fallback` (YAML 또는 구성 화면) |
| 동시 실행 상한 | 초과분은 큐에서 대기 | `orchestrator.concurrency` (기본 2) |
| 예산 | Claude CLI 인자로 턴 수 등을 제한 | `orchestrator.modules.claude.extra-args: ["--max-turns", "30"]` |
| 알림 | Job 종료 시 브라우저 알림 | 브라우저 권한 허용 |

서버가 재시작되면 실행 중이던 Job은 `FAILED`로 표시되고 로그는 디스크에 남는다.

## 보안

로컬 도구지만 브라우저를 통한 공격은 막아 두었다.

| 장치 | 동작 |
|---|---|
| loopback 바인딩 | `server.address=127.0.0.1`. 다른 기기에서는 접속할 수 없다. 포트는 8080이 아닌 `47120`을 기본으로 쓴다 |
| Host 검사 | `Host` 헤더가 localhost/127.0.0.1/[::1]가 아니면 403. DNS 리바인딩(공격 사이트 도메인이 127.0.0.1로 풀리는 수법) 차단 |
| Origin 검사 | 다른 출처(`Origin`)에서 온 POST/PUT/DELETE는 403. 악성 페이지가 사용자 브라우저로 작업을 제출하거나 설정을 바꾸지 못한다 |
| API 토큰 | 설치마다 무작위 토큰을 만들어 `<data-dir>/api-token`(소유자만 읽기)에 둔다. 모든 `/api` 요청은 `X-Orchestrator-Token` 헤더(또는 `Authorization: Bearer`, SSE는 `?token=`)로 이 값을 보내야 한다. 같은 출처의 웹 화면만 `GET /api/session`으로 토큰을 받을 수 있고, 다른 사이트는 CORS 때문에 그 응답을 읽지 못한다 |
| 작업 공간 제한 | 화면에서 바꾸는 작업 공간은 `orchestrator.security.allowed-workspace-roots`(기본 홈 디렉터리) 아래여야 한다 |
| 에이전트 권한 | 파일 수정은 코더 단계에만(`acceptEdits`), 나머지는 읽기 전용. 비대화형 Claude는 작업 공간 밖 파일 읽기와 허용 목록 밖 셸 명령을 자동 거부하므로 에이전트가 `~/.claude` 자격증명 등을 읽어 내보내는 경로가 막힌다 |

이 서버는 API 키를 저장하지 않는다. Claude CLI가 자기 로그인(구독 또는 `ANTHROPIC_API_KEY`)을 쓰며, 서버는 그 키를 로그나 응답에 싣지 않는다. curl로 API를 부를 때는 `-H "X-Orchestrator-Token: $(cat ~/.ai-orchestrator/api-token)"`를 붙인다. 토큰이 불편하면 `orchestrator.security.require-token=false`로 끌 수 있지만 그러면 로컬의 어떤 프로세스든 API를 쓸 수 있다.

## 서버 설정 (`server/src/main/resources/application.yml`)

| 키 | 의미 | 기본값 |
|---|---|---|
| `server.port` / `server.address` | 포트 / 바인드 주소 | 47120 / 127.0.0.1 |
| `orchestrator.security.require-token` | `/api` 요청에 설치별 토큰 요구 | true |
| `orchestrator.security.allowed-workspace-roots` | 작업 공간으로 허용할 루트 | `${user.home}` |
| `orchestrator.data-dir` | Job 저장소와 `orchestrator.yml` 위치 | `~/.ai-orchestrator` |
| `orchestrator.workspace` | AI CLI가 실행되는 디렉터리(수정 대상 코드) | 서버 실행 위치 |
| `orchestrator.modules.<name>.mode` | `AUTO`(설치돼 있으면 CLI, 아니면 스텁) / `CLI` / `STUB` | `AUTO` |
| `orchestrator.modules.<name>.command` | 실행 파일 이름 또는 경로 | 모듈 이름 |
| `orchestrator.modules.claude.model` | `--model` 별칭/이름 (예: `sonnet`) | CLI 기본값 |
| `orchestrator.modules.claude.max-budget-usd` | 에이전트 1회 실행의 비용 상한 (`--max-budget-usd`) | 2.0 |
| `orchestrator.modules.claude.allowed-tools` | 묻지 않고 허용할 도구 패턴 (`--allowedTools`). 비대화형이라 목록에 없는 셸 명령은 거부됨 | git status/diff/log, 테스트 러너 등 |
| `orchestrator.prompt.max-result-chars` / `max-total-chars` | 다음 단계 프롬프트에 넣는 이전 단계 결과의 상한(결과당 / 단계 합계). 넘치면 앞 70%·뒤 30%만 남기고 요약 로그에 기록. 0 = 무제한 | 24000 / 60000 |
| `orchestrator.isolation.enabled` | 경쟁 모드(코더 2개 이상) 작업 공간 격리. 작업 공간이 git 저장소여야 함 | true |
| `orchestrator.isolation.auto-apply` | 검증자가 채택한 후보를 작업 공간에 자동 적용 | true |
| `orchestrator.isolation.link-dirs` | worktree에 심볼릭 링크로 연결할 ignore 디렉터리 | node_modules, .venv, venv, target, build, .gradle |

### 경쟁 모드 (코더 2개 이상)

코더가 여러 개인 프리셋(1-1-2-1이 아니라 1-**3**-3-1처럼 코더 칸에 2개 이상)은 **경쟁 모드**로 돈다. 자세한 설계는 `docs/design-competition-isolation.md`.

- 코더마다 git worktree(`<data-dir>/worktrees/<jobId>/c<k>`)를 만들어 같은 기준(HEAD + 커밋 안 한 변경)에서 출발한다. 원본 작업 공간은 채택 전까지 바뀌지 않는다.
- 각 후보의 변경은 `<data-dir>/jobs/<id>/candidates/c<k>.patch`로 저장된다.
- 리뷰어 수가 후보 수와 같으면 1:1로 해당 worktree 안에서 리뷰한다(테스트 실행 가능). 다르면 작업 공간에서 모든 후보를 본다.
- 검증자는 마지막 줄에 `채택: 후보 N`을 쓰고, 그 patch가 작업 공간에 미커밋 변경으로 적용된다. 판독 실패·충돌 시에는 적용하지 않고 작업 상세에서 수동으로 고른다.
- 후보 일부가 실패해도 하나 이상 성공하면 계속한다. worktree는 작업이 끝나면 정리된다.

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
| GET/POST | `/api/jobs` | 목록(`?q=검색&status=SUCCEEDED&offset=0&limit=50`, 총 개수는 `X-Total-Count` 헤더) / 생성 (`commandLine` 또는 `target`+`flow`=프리셋) |
| POST | `/api/jobs/batch` | 여러 명령을 한 번에 |
| POST | `/api/jobs/preview` | 실행 없이 단계 그래프만 |
| POST | `/api/jobs/{id}/cancel` | 취소 |
| POST | `/api/jobs/{id}/apply?candidate=k` | 경쟁 모드 후보 k의 patch를 작업 공간에 수동 적용 (검증자 선택 덮어쓰기 가능) |
| GET | `/api/jobs/{id}/candidates/{k}/patch` | 후보 k의 diff 원문 |
| GET | `/api/jobs/{id}/events` | SSE: `job` 스냅샷 + `log` 이벤트. `?after=seq` 또는 브라우저의 `Last-Event-ID` 헤더로 이어받기, 15초마다 `ping` |
| GET | `/api/jobs/{id}/logs?level=SUMMARY|DETAIL` | 로그 조회 |
| GET | `/api/events` | SSE: 전체 Job 변경 |
| GET/PUT | `/api/config/routing` | 프리셋 구성 조회/저장 (저장 시 즉시 반영) |
| GET/PUT | `/api/config/settings` | 서버 설정 조회/저장 (`settings.yml`, 즉시 반영) |

## Windows에서 실행

WSL 없이 Windows 네이티브로 돈다. 필요한 것: JDK 21, Git for Windows, `claude` CLI(npm으로 설치하면 `claude.cmd`), 웹 UI를 다시 빌드할 때만 Node 22.

- `run.cmd`(또는 `gradlew.bat :server:bootRun -PskipWeb`)로 실행하고 http://localhost:47120 을 연다. 빌드한 jar는 `java -jar server\build\libs\server-0.1.1.jar`로 띄운다.
- npm이 설치한 `claude.cmd` 같은 배치 셈은 Java가 직접 실행하지 못하므로 서버가 PATHEXT로 실행 파일을 찾아 `cmd.exe /c`로 감싸 실행한다. 설정의 실행 파일에는 `claude`라고만 적으면 된다.
- 경쟁 모드의 worktree에 `node_modules` 같은 ignore 디렉터리를 연결할 때 심볼릭 링크가 안 되면(개발자 모드 꺼짐) 디렉터리 정션(`mklink /J`)으로 대신 연결한다.
- 데이터는 `%USERPROFILE%\.ai-orchestrator`에 쌓인다. API 토큰 파일은 NTFS 기본 ACL(사용자 프로필은 본인만 접근)을 따른다.
- 테스트: `gradlew.bat test -PskipWeb`. git worktree 격리 테스트는 POSIX 전용이라 Windows에서는 건너뛴다.

## 개발 메모

- WSL에서 저장소가 `/mnt/c` 아래에 있으면 Gradle이 파일 권한을 바꾸지 못해 실패할 수 있다. `~/.gradle/gradle.properties`에 `buildDirBase=/home/<you>/.cache/ai-cli-orchestrator`를 넣으면 빌드 출력이 리눅스 파일시스템으로 간다.
- Gradle configuration cache는 같은 이유로 꺼져 있다 (`gradle.properties`).
- WSL에서 서버를 띄우고 Windows 브라우저로 열 때는 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`를 준다. JVM이 기본으로 `[::ffff:127.0.0.1]`(IPv6 매핑 주소)에 바인딩하면 WSL의 localhost 포워딩이 그 포트를 Windows로 넘겨주지 않는다. Node(Vite)는 IPv4로 바인딩해서 문제가 없다.
- 브라우저 QA는 Windows Chrome을 헤드리스로 띄워 찍는다. `web/scripts/screenshot.ps1`은 DevTools 프로토콜로 뷰포트 크기·다크 모드를 지정하고 스크린샷을 저장한다 (`chrome.exe --headless=new --remote-debugging-port=9333` 실행 후 `powershell -File web/scripts/screenshot.ps1 -Url http://127.0.0.1:47120/#dashboard -Out shot.png -Width 400 -Dark`). `--screenshot` 플래그만 쓰면 창 최소 너비(약 500px) 때문에 400px 레이아웃을 볼 수 없다.
