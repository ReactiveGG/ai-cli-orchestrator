# 운영 가이드

매일 쓰는 사람을 위한 실행·파일 위치·문제 해결 안내. 구성 개념은 [README](../README.md), 남은 일은 [v1-todo](v1-todo.md)를 본다.

## 1. 실행 방법

| 방법 | 명령 | 언제 |
|---|---|---|
| 스크립트 | `run.cmd` (Windows) / `./run.sh` (Linux·macOS·WSL) | 평소. 소스에서 바로 실행, 인자는 그대로 서버에 전달 |
| 단일 jar | `./gradlew :server:bootJar -PskipWeb` → `java -jar server/build/libs/server-0.1.0.jar` | 다른 PC에 복사해 실행. jar 하나에 서버와 웹 UI(`web/dist`)가 들어 있다(약 25MB) |
| IntelliJ | 실행 구성 `server: bootRun (47120)` | IDE에서. `.run/` 폴더에 공유 구성이 있어 프로젝트를 열면 바로 보인다 |
| IntelliJ 디버그 | 실행 구성 `server: ServerApplication (debug)` | 브레이크포인트가 필요할 때. Program arguments에 `--orchestrator.workspace=...` |
| 테스트 | `./gradlew test -PskipWeb` 또는 실행 구성 `tests: gradlew test -PskipWeb` | CI와 같은 명령 |

접속: http://localhost:47120 (127.0.0.1에만 바인딩). 자주 쓰는 인자:

```bash
./run.sh --orchestrator.workspace=/path/to/project     # AI가 읽고 고칠 프로젝트
./run.sh --server.port=47130                            # 포트 변경
./run.sh --orchestrator.data-dir=/other/dir             # 데이터 디렉터리 변경
java -jar server-0.1.0.jar --orchestrator.workspace=C:\dev\my-service
```

`buildDirBase`를 쓰는 환경(WSL, README 개발 메모 참고)에서는 jar가 `<buildDirBase>/server/libs/`에 생긴다.

## 2. 파일 위치

데이터 디렉터리 기본값은 `~/.ai-orchestrator` (Windows: `%USERPROFILE%\.ai-orchestrator`). `--orchestrator.data-dir`로 바꾼다.

```
~/.ai-orchestrator/
├── api-token            # 설치별 API 토큰 (자동 생성, 소유자만 읽기). 지우면 다음 시작 때 새로 만든다
├── orchestrator.yml     # 프리셋·역할·폴백 (구성 화면 "프리셋 저장 및 적용"이 쓰는 파일)
├── settings.yml         # 서버 설정 화면에서 저장한 값. application.yml 기본값보다 우선
├── jobs/<jobId>/
│   ├── job.json         # 스냅샷 (상태, 단계, 토큰, 후보, 결정). 재시작 후 목록 복원에 쓴다
│   ├── summary.log      # 요약 로그 (한 줄 = "시각 단계id 메시지", 단계 없는 줄은 "-")
│   ├── detail.log       # 상세 로그 (CLI 이벤트 단위)
│   └── candidates/cN.patch   # 경쟁 모드 후보 N의 diff
└── worktrees/<jobId>/   # 경쟁 모드 임시 worktree. 정상 종료 시 지워지고, "worktree 보존"을 켜면 남는다
```

- 로그 파일은 화면의 "파일로 열기"로도 열린다. 요약 로그는 줄마다 flush되므로 실행 중에도 tail로 볼 수 있다.
- 서버 자체 로그는 표준 출력으로만 나간다. 파일로 남기려면 `./run.sh > server.log 2>&1`처럼 리다이렉트하거나 `--logging.file.name=server.log`를 준다.
- 작업을 지우면(화면 "삭제") `jobs/<jobId>`가 통째로 지워진다.
- 끝난 작업은 `orchestrator.retention`(기본 최근 200개, 30일)을 넘으면 오래된 것부터 로그와 함께 자동 삭제된다. 서버 시작 때와 작업이 끝날 때마다 검사하며, 실행 중·대기 중인 작업은 지우지 않는다. 전부 보관하려면 `--orchestrator.retention.max-jobs=0 --orchestrator.retention.max-age=0`.

## 3. 설정 우선순위

1. 명령줄 인자 (`--orchestrator.concurrency=4`)
2. `settings.yml` (서버 설정 화면에서 저장)
3. `server/src/main/resources/application.yml` 기본값

프리셋(`orchestrator.yml`)은 별도 파일이며 구성 화면이나 직접 편집으로 바꾼다. 직접 편집했으면 서버를 다시 시작한다.

## 4. 문제 해결

| 증상 | 원인·조치 |
|---|---|
| 화면 상단 "서버 연결 끊김" | 서버가 내려갔거나 재시작 중. 다시 켜면 몇 초 안에 자동 복구. 서버는 15초마다 `ping`을 보내고 40초 동안 없으면 끊김으로 표시한다 |
| 포트 사용 중 (`Port 47120 was already in use`) | 이전 서버가 살아 있음. 종료하거나 `--server.port=47130`으로 띄운다 |
| 대시보드 Claude 상태 "설치 안 됨" | `claude`가 PATH에 없음. 터미널에서 `claude --version`이 되는지 확인. Windows는 npm이 만든 `claude.cmd`를 서버가 PATHEXT로 찾는다. 서버 설정 화면 "실행 파일"에 절대 경로를 넣어도 된다 |
| 작업이 곧바로 실패, 로그에 로그인 관련 메시지 | CLI 로그인이 안 됨. 터미널에서 `claude` 한 번 실행해 로그인한다. 서버는 로그인 화면을 띄울 수 없다 |
| 리뷰어·검증자가 테스트를 "실행할 수 없다"고 함 | 비대화형 실행에서는 허용 목록 밖의 셸 명령이 거부된다. 서버 설정 "허용 도구"에 `Bash(pytest*)`처럼 추가한다 |
| `error_max_budget_usd`로 실패 | 에이전트당 예산 상한 초과. 서버 설정 "에이전트당 예산 상한"을 올린다. 프롬프트 캐시 때문에 가장 짧은 실행도 약 $0.2가 든다 |
| 요약 로그에 "이전 결과 '…' N자 → M자로 잘라 전달" | 이전 단계 출력이 상한(결과당 24,000자·합계 60,000자)을 넘어 앞 70%·뒤 30%만 넘어감. 전문은 상세 로그에 있다. 더 넘기려면 `--orchestrator.prompt.max-result-chars=`, `max-total-chars=`(0 = 무제한) |
| "출력 없음 Ns" 경고가 계속 | 모델이 오래 생각 중이거나 CLI가 멈춤. 모듈 타임아웃(기본 10분)이 지나면 프로세스 트리를 죽이고 시간 초과로 끝난다. 필요하면 "취소" |
| 401 / "인증 토큰이 맞지 않습니다" | 데이터 디렉터리를 바꿔 토큰이 달라짐. 페이지를 새로고침하면 새 토큰을 받는다 |
| 403 / "허용되지 않은 요청" | 다른 출처(다른 포트·호스트)에서 온 변경 요청이거나 Host 헤더가 loopback이 아님. 반드시 `localhost` 또는 `127.0.0.1`로 연다 |
| 작업 공간을 저장할 수 없음 | 허용 루트(기본 홈 디렉터리) 밖 경로. `--orchestrator.security.allowed-workspace-roots=/srv,/home/me`로 넓힌다 |
| 경쟁 모드가 시작 전에 거부됨 | 작업 공간이 git 저장소가 아니거나 격리가 꺼짐. `git init`하거나 코더가 1개인 프리셋을 쓴다 |
| 후보 적용 실패 (`git apply`) | 작업 공간이 기준 커밋에서 바뀌었음. 작업 상세 "patch"로 diff를 받아 수동 적용하거나 변경을 커밋한 뒤 "이 후보 적용" |
| WSL에서 띄웠는데 Windows 브라우저에서 안 열림 | JVM이 IPv6 매핑 주소에 바인딩해 WSL 포워딩이 안 됨. `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true ./run.sh` |
| 작업 목록에서 옛 작업이 안 보임 | 보존 한도(기본 200개·30일)를 넘어 정리됨. 작업 화면의 검색·상태 필터로 먼저 찾아보고, 더 오래 보관하려면 `retention` 값을 올린다 |
| 화면이 옛 버전으로 보임 | 서버는 `index.html`을 항상 재검증하도록 보내지만, 프록시가 끼면 Ctrl+F5. `web/dist`를 다시 빌드했으면 서버 재시작 |

## 5. 백업·초기화

- 백업: `~/.ai-orchestrator` 전체를 복사하면 프리셋, 설정, 작업 이력이 함께 보존된다.
- 프리셋만 초기화: 구성 화면 "기본 프리셋으로" 또는 `orchestrator.yml` 삭제 후 재시작.
- 완전 초기화: 서버를 끄고 `~/.ai-orchestrator`를 지운다. 토큰도 새로 생긴다.
