# v1 완성 TODO

기준: "매일 실제 프로젝트에 쓸 수 있는 v1". 항목을 끝내면 체크박스를 채우고 진행률을 갱신한다.
마지막 갱신: 2026-09-18 (v0.1.3 릴리스 후) · 가중 완성도 **약 96%** · 릴리스: v0.1.0 → v0.1.3 (Windows 앱 zip 첨부)

| # | 프로세스 | 가중치 | 진행 | 상태 |
|---|---|---|---|---|
| 1 | 핵심 엔진 | 15 | 100% | 실모델로 4단계 체인 확인. 이전 결과 프롬프트 상한(결과당 24k·합계 60k자). 역할 지시문 튜닝: 고정 판정 줄, 코더 요약 형식, 붙여넣기 금지, diff 직접 읽기 |
| 2 | Claude CLI 실연동 검증 | 15 | 100% | default 1-1-1-1 실제 실행 성공(2m13s, $1.72). allowed-tools, 로그인 실패·강제 종료 진단, 대시보드 로그인 상태·구독 사용량 창(5시간/7일, rate_limit_event) 완료 |
| 3 | 병렬 코더 작업 공간 격리 | 12 | 100% | 경쟁 모드 완료: worktree 격리, 1:1 리뷰, 채택·적용, 후보 표, 실모델 검증 |
| 4 | 서버 (Job 큐·SSE·저장) | 10 | 100% | SSE 하트비트·`after`/`Last-Event-ID` 이어받기, 보존 정책(개수·기간, 시작·완료 시 정리), 목록 검색·상태 필터·페이지네이션(API + 화면) |
| 5 | 웹 UI | 12 | 100% | Chrome 헤드리스 QA(라이트/다크·1280/400px) + 실기기 Edge 확인 완료. 오류·로딩·빈 상태·삭제 확인·알림 권한 흐름 정리 |
| 6 | 에이전트별 모델 선택 | 6 | 100% | 칩마다 모델·에포트 지정 → `--model`/`--effort` 전달, 실모델 확인 완료 |
| 7 | 설정 UI | 4 | 100% | 구성 탭 하단 "서버 설정"에서 편집, settings.yml 저장·즉시 반영 |
| 8 | Codex CLI 연동 검증 | 4 | 20% | 미설치, 스텁으로만 확인 |
| 9 | Windows 네이티브 지원 | 6 | 90% | .cmd 셈 실행·PATHEXT·설치 위치 탐색·정션 폴백·run.cmd, 데스크톱 앱(v0.1.1~)을 실제 Windows에서 실행·로그인·폴더 선택 확인. 경쟁 모드 1회 확인만 남음 |
| 10 | 보안 | 5 | 100% | loopback 바인딩(포트 47120), Host/Origin 검사, 설치별 API 토큰, 작업 공간 루트 제한 |
| 11 | 테스트·CI | 5 | 100% | GitHub Actions(Java: Ubuntu+Windows, 웹: 테스트+빌드), Java 52개(스텁 e2e 2개 포함), vitest 9개 |
| 12 | 배포·실행 편의 | 3 | 100% | run.cmd/run.sh, 단일 jar, IntelliJ 실행 구성, Windows 데스크톱 앱(jpackage, 59MB zip, 트레이·아이콘) + `v*` 태그 릴리스 자동 첨부 |
| 13 | 문서 | 3 | 100% | README, 운영 가이드(docs/operations.md), 이 TODO를 릴리스(v0.1.3)에 맞춰 갱신 |

## 남은 일

계획했던 진행 순서(#2 실연동 → #3 격리 → #6·#7 → #10·#9·#11)는 모두 끝났다. v1은 기능 완료 상태이며 아래만 남아 있다.

1. **#9 경쟁 모드를 실제 Windows에서 1회 확인** — 코더 2개 프리셋으로 worktree 생성·patch 적용·정리가 Windows(정션 폴백 포함)에서 도는지. 사용자가 데스크톱 앱에서 직접 실행하면 된다.
2. **#10 토큰 파일 권한(Windows)** — `%USERPROFILE%\.ai-orchestrator\api-token`이 NTFS 기본 ACL로 본인만 읽는지 `icacls`로 확인.
3. **#8 Codex CLI 연동** — CLI가 설치된 환경이 생기면 `codex exec --json -` 이벤트 형식에 맞춰 파서·usage 집계 확인. 그 전까지 스텁.

## 상세 체크리스트

### 1. 핵심 엔진 (core)
- [x] 고정 파이프라인 플래너 → 코더 → 리뷰어 → 검증자
- [x] 프리셋 = 단계별 에이전트 수 (1-1-1-1, 1-1-2-1, 1-3-3-1), 서명 계산
- [x] 단계 내 병렬 실행, 이전 단계 결과 전부 전달
- [x] 취소, 하드 타임아웃, 유휴 경고, 시간 초과 시 폴백 모듈
- [x] YAML 설정 (flows/roles/fallback)
- [x] 이전 단계 결과 프롬프트 상한 `PromptLimits`(결과당 24,000자·단계 합계 60,000자 ≈ 20k 토큰, 200k 컨텍스트의 10% 이하): 앞 70%·뒤 30% 유지, 잘리면 요약 로그에 기록, `orchestrator.prompt.*`로 조정, 0 = 무제한 (테스트 5개)
- [x] 역할 지시문 튜닝(실행 기록 기반): 플래너는 파일 먼저 읽기·40줄 이내·완료 기준(테스트 명령); 코더는 테스트 실제 실행·고정 요약 형식(변경 파일/실행한 테스트/달라진 점/남은 일); 리뷰어는 요약 대신 diff·테스트 직접 확인·심각도 표기·20줄·`판정: 승인|수정 필요`; 검증자는 요청 항목별 대조·`동의/반대/남은 위험`·`판정: 승인|반려`·`채택: 후보 N`. 모든 역할에 파일 내용 붙여넣기 금지(프롬프트 상한과 짝). 테스트 3개, mock 동기화

### 2. Claude CLI 실연동 검증
- [x] `claude -p --output-format stream-json --verbose` 실제 실행, 이벤트 파싱 확인 (system/init, rate_limit_event, assistant, result)
- [x] `result` 이벤트의 usage/total_cost_usd 집계가 대시보드에 맞게 들어오는지 (에러 결과는 assistant 메시지 usage로 대체)
- [x] 코더 단계의 `--permission-mode acceptEdits` 동작과 파일 수정 범위 확인 (greeting.py 수정, test_greeting.py 생성)
- [x] 작업 공간(`orchestrator.workspace`) 지정 시 CLI가 그 폴더에서 도는지
- [x] 셸 명령 권한: 비대화형에서 Bash가 전부 거부되던 문제 → `allowed-tools`(`--allowedTools`) 설정 추가, 실검증 완료
- [x] 예산 상한(`max-budget-usd`) 전달과 `error_max_budget_usd` 실패 처리
- [x] `extra-args`(예: `--max-turns`) 전달 확인 (리플레이 테스트)
- [x] Claude Code 세션 안에서 띄울 때 중첩 세션 환경 변수 제거
- [x] 실패 케이스: 로그인 안 됨 → CLI 출력·result 오류에서 인식해 "Claude CLI 로그인이 필요합니다 – /login" 안내(원문 유지), 대시보드가 `claude auth status`로 "로그인 필요" 표시. 외부 강제 종료(137/143/130) → "프로세스가 외부에서 강제 종료됨"으로 사용자 취소(CANCELLED)·시간 초과(TIMEOUT)와 구분. 취소 시 프로세스 트리 종료는 ProcessRunnerTest.killsOnCancel, 대기 중 취소는 JobServiceTest에서 확인 (테스트 6개 추가)
- [x] 대시보드 구독 사용량 타일: CLI `rate_limit_event`의 unifiedWindows(5시간/7일 사용률·초기화 시각·status)를 컨텍스트 → 관찰자 → `SubscriptionUsage`(`<data-dir>/subscription-usage.json`, 재시작 유지) → `/api/dashboard.subscription`. 75% 주황·90% 빨강, 한도 임박/도달 표시 (테스트 3개)
- [x] allowed-tools 적용 상태로 1-1-1-1 재실행해 리뷰어·검증자가 테스트를 실제로 돌리는지 확인 (2차 실행: 리뷰어·검증자가 unittest 직접 실행, 검증자 판정 "승인", $1.53)

### 3. 병렬 코더 작업 공간 격리 (경쟁 모드, docs/design-competition-isolation.md)
- [x] 설계 문서
- [x] core: GitWorktreeIsolation (기준 커밋, worktree, patch 추출, 적용, 정리) + 테스트 6개
- [x] core: 실행 관리자 통합 — 코더 격리, 후보 산출물, 리뷰어 1:1 매핑(수가 다르면 전체 검토), 검증자 `채택: 후보 N` 판독, 자동 적용, 정리, 후보 일부 실패 허용 + 테스트 5개
- [x] 1-1-x-1(코더 1개)은 격리 없이 기존 경로 유지
- [x] 실패·취소 시 worktree 정리 (finally)
- [x] server: orchestrator.isolation 설정, jobId를 runId로 전달, patch는 jobs/<id>/candidates/
- [x] server: 스냅샷에 후보 목록(파일/줄 수, patch 경로, 채택·적용)·결정 메모, 수동 적용 API `POST /api/jobs/{id}/apply?candidate=k`, patch 조회 `GET /api/jobs/{id}/candidates/{k}/patch`, 재시작 후 복원
- [x] web: 작업 상세 후보 표(변경 파일/줄 수, 채택·적용 표시, patch 보기, 이 후보 적용), 목록에 후보 배지, mock 모드 후보 시뮬레이션
- [x] 실모델 E2E: 1-2-2-1 sonnet/low — 코더 2개 병렬(worktree c1/c2), 리뷰어 1:1로 각 worktree에서 실행, 검증자 `채택: 후보 1` → 작업 공간 적용, worktree 정리, patch 보존 (1m31s, $0.56). 후속: `__pycache__` 등 기본 제외 패턴 추가, 1:1 리뷰어 안내 문구 보강
- [x] 다이어그램에서 채택 후보 노드 강조(초록 테두리·배지), 탈락 후보 흐리게

### 4. 서버
- [x] Job 큐, 동시 실행 상한, 취소, 상태 저장/복원
- [x] 요약/상세 로그, 작업별·전체 SSE
- [x] 대시보드·카탈로그·구성 API, 저장 시 즉시 반영
- [x] SSE 하트비트(`ping` 15초)와 클라이언트 워치독(40초 무응답 → 재연결), 서버 중단·재시작 시 끊김 표시·자동 복구 확인
- [x] SSE 이어받기: 웹 클라이언트가 스트림을 다시 열 때 `?after=마지막 seq`를 붙이고, 브라우저 자동 재접속의 `Last-Event-ID` 헤더도 서버가 읽어 그 뒤만 재생 (e2e 확인)
- [x] 보존 정책 `orchestrator.retention` (max-jobs 200, max-age 30d): 끝난 작업만, 최신 유지, 시작·완료 시 정리, 정리 후 목록 SSE 갱신 (테스트)
- [x] 작업 이력: `GET /api/jobs?q&status&offset&limit` + `X-Total-Count`, 작업 화면에 검색·상태 필터·30개씩 더 보기 (테스트)

### 5. 웹 UI
- [x] 대시보드, 작업 목록/상세, 구성(프리셋 빌더), 명령창
- [x] 해시 라우팅, mock 모드
- [x] 작업 상세 다이어그램이 실시간 갱신 때 노드를 잃던 문제 수정 (React Flow 노드 상태 관리 + 재fit)
- [x] 대시보드 7일 토큰: 입력/출력을 각자 축을 가진 작은 영역 그래프 2개로(검증된 파랑/주황 팔레트, 다크 대응), 10초 자동 갱신 + 새로고침 버튼, 작업 이벤트마다 갱신
- [x] 푸터(버전·파이프라인·경로·작업 카운트·GitHub)와 하단 여백
- [x] 최종 결과를 접지 않고 로그처럼 펼쳐 표시
- [x] 실제 브라우저 QA: Windows Chrome 헤드리스(DevTools 프로토콜, `web/scripts/screenshot.ps1`)로 라이트/다크 × 1280/400px × 4화면 확인. 고친 것: 상태 배지 줄바꿈, 400px 가로 넘침(그리드 `minmax(0,…)`), 헤더·푸터 줄바꿈, 좁은 화면 목록 높이, 역할 라벨 줄바꿈, OS 다크 모드 전환 반영
- [x] 에러 상태 표시: 서버 끊김 배너(하트비트 기반), 목록·대시보드·구성·설정 로딩 실패 + 다시 시도, 없는 작업 id, 저장·초기화·취소·삭제 실패, 알 수 없는 프리셋·옵션 한국어 메시지
- [x] 삭제 2단계 확인, 빈 상태 문구(작업 없음·로그 없음·대기 중·필터 결과 없음·토큰 기록 없음), 스피너 로딩, 저장 중 버튼 문구
- [x] 브라우저 알림: 헤더 종 버튼으로 켜고 끔(권한 요청은 클릭 시에만), 차단 상태 표시, 알림 클릭 시 작업으로 이동
- [x] 실기기 Edge에서 1회 확인 (2026-09-17)

### 6. 에이전트별 모델 선택
- [x] 에이전트 스펙에 모델명·에포트 추가, `claude --model`/`--effort` 전달 (리플레이 테스트)
- [x] 구성 화면 모델 칩에 모델·에포트 선택
- [x] YAML 형식 확장 (`models: [claude:opus/high, { module: claude, model: sonnet, effort: max }]`)
- [x] 실제 claude로 `--model sonnet --effort low` 조합이 반영되는지 확인 (argv, init 모델 claude-sonnet-5, modelUsage에 sonnet, $0.058)

### 7. 설정 UI
- [x] 설정값 표시
- [x] 작업 공간, 동시 실행 수, 타임아웃, 유휴 경고, 모듈(모드·실행 파일·기본 모델·예산·허용 도구·추가 인자), 격리 옵션을 화면에서 변경·저장 (`<data-dir>/settings.yml`, 즉시 반영, 검증 오류 표시)

### 8. Codex CLI 연동 검증
- [ ] `codex exec --json -` 실제 이벤트 형식 확인 후 파서 수정
- [ ] usage 필드 집계 확인

### 9. Windows 네이티브 지원
- [x] `claude.cmd` 등 PATHEXT 기반 실행 파일 탐색, `.cmd`/`.bat`는 `cmd.exe /c`로 실행 (상태 조회·실행 모두)
- [x] 프로세스 트리 종료: `ProcessHandle.descendants()` 기반이라 OS 공통
- [x] ProcessRunner 테스트를 OS별 명령(cmd.exe / sh)으로 분기, 셈 래핑·PATHEXT 탐색 단위 테스트
- [x] worktree 링크 디렉터리: 심볼릭 링크 실패 시 디렉터리 정션 폴백
- [x] `run.cmd` / `run.sh` 실행 스크립트, README Windows 절
- [x] 실제 Windows에서 데스크톱 앱(v0.1.1~v0.1.3) 실행, claude 탐색·로그인 창·폴더 선택 확인 (사용자)
- [ ] 실제 Windows에서 경쟁 모드(코더 2개) 1회 확인

### 10. 보안
- [x] `server.address=127.0.0.1` 기본값, 포트 47120
- [x] 작업 공간 경로 검증 (허용 루트 밖 경로 거부, 심볼릭 링크 해석)
- [x] `acceptEdits`는 코더 단계에만, 나머지는 읽기 전용 (리플레이 테스트로 확인)
- [x] Host 검사(DNS 리바인딩), Origin 검사(다른 사이트의 변경 요청), 설치별 API 토큰(`<data-dir>/api-token`, 같은 출처 화면만 `/api/session`으로 획득, SSE는 `?token=`)
- [ ] 후속: 토큰 파일 권한이 Windows에서도 소유자 전용인지 확인

### 11. 테스트·CI
- [x] core/cli/server 단위 테스트 (50개)
- [x] 웹 단위 테스트 (vitest): 플랜 생성·후보 번호·서명, 포맷 함수 (9개)
- [x] 스텁 모드 e2e `StubEndToEndTest`: 실제 포트로 기동 → 세션 토큰 → 토큰 없음 401·타 출처 403 → 제출 → 완료 폴링 → 요약/상세 로그·seq → SSE 재생 → 대시보드·목록 → 삭제. 미리보기, 알 수 없는 프리셋 400, 배치 제출(git 저장소 아님 → best-of-3 거부) 포함
- [x] GitHub Actions: Java 테스트 Ubuntu+Windows 매트릭스, 웹 테스트+빌드, wrapper 검증, 실패 시 리포트 업로드

### 12. 배포·실행 편의
- [x] `bootJar`로 단일 jar 실행 문서화 (README·운영 가이드, jar 기동·UI·API 응답 확인)
- [x] 실행 스크립트 (`run.sh` / `run.cmd`)
- [x] IntelliJ 실행 구성: `.run/` 공유 구성(bootRun, ServerApplication 디버그, 테스트) + README 안내
- [x] Windows 앱: `:server:jpackage`(런타임 동봉 앱 폴더 / `-PjpackageType=msi`), 데스크톱 모드(브라우저 자동 열기, 트레이 열기·종료, 중복 실행 시 브라우저만), CI windows-app 잡이 zip·msi 산출물 업로드, `v*` 태그면 릴리스 첨부

### 13. 문서
- [x] README (구성, 프리셋, 실행, API)
- [x] 운영 가이드 `docs/operations.md` (실행 방법, 데이터 디렉터리 구조, 설정 우선순위, 문제 해결 표, 백업·초기화)
- [x] 이 TODO를 릴리스마다 갱신 (v0.1.3 기준)
