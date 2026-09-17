# 설계: 경쟁 모드(best-of-N) 코더의 작업 공간 격리

상태: 초안 (2026-09-17) · 대상: v1 TODO #3

## 목표

코더 단계에 에이전트가 N개(N ≥ 2)일 때, 각 코더가 **같은 계획**을 받아 **서로 다른 작업 공간**에서 끝까지 구현하고, 리뷰어·검증자가 후보 중 하나를 **채택**해 원본 작업 공간에 적용한다. 병합은 하지 않는다. 코더가 1개면 지금처럼 원본 작업 공간에서 바로 작업한다.

## 원칙

1. 원본 작업 공간(`orchestrator.workspace`)은 검증자가 채택한 후보를 적용하기 전까지 **절대 수정되지 않는다**.
2. 후보는 **git worktree**로 만든다. 같은 기준 커밋에서 출발하므로 후보 간 비교가 공정하고, diff 추출·적용·정리가 git 명령 하나씩으로 끝난다.
3. 사용자의 커밋되지 않은 변경도 후보의 출발점에 포함한다. 단, 사용자의 인덱스·브랜치는 건드리지 않는다.
4. 후보의 diff(patch)는 작업 디렉터리에 파일로 남긴다. 자동 적용이 실패하거나 사람이 다른 후보를 고르고 싶을 때 수동 적용할 수 있다.

## 전제와 제약

- 작업 공간이 **git 저장소**여야 한다. 아니면 코더가 2개 이상인 프리셋은 작업 시작 시점에 거부한다(토큰을 쓰기 전에). 메시지: "병렬 코더는 git 저장소에서만 쓸 수 있습니다. `git init` 하거나 코더를 1개로 줄이세요."
- `.gitignore`된 파일(node_modules, .venv, build 산출물)은 worktree에 없다. 설정 `isolation.link-dirs`에 적힌 디렉터리는 원본에서 **심볼릭 링크**로 연결한다(기본: `node_modules`, `.venv`, `venv`, `target`, `build`, `.gradle`).
- worktree는 저장소 밖 `<data-dir>/worktrees/<jobId>/c<k>`에 만든다. 작업 공간 안에 흔적을 남기지 않는다.

## 흐름

```
플래너(원본, 읽기 전용)
   │ 계획
   ▼
[기준 커밋 만들기]  ← 원본의 HEAD + 작업 트리(추적/미추적 포함, ignore 제외)를 임시 인덱스로 커밋. 사용자 인덱스·브랜치 불변
   │
   ├─ worktree c1 ── 코더 1 (acceptEdits) ── 커밋 "candidate 1" ── patch c1
   ├─ worktree c2 ── 코더 2 ─────────────── 커밋 "candidate 2" ── patch c2
   └─ worktree c3 ── 코더 3 ─────────────── 커밋 "candidate 3" ── patch c3
   │
   ▼
리뷰어 단계
   • 리뷰어 수 == 후보 수 → 리뷰어 k는 worktree k에서 실행(테스트 가능), 후보 k만 상세 검토 + 다른 후보의 요약(파일 수·줄 수)만 참고
   • 그 외 → 리뷰어는 원본에서 실행, 모든 후보의 patch를 받고 후보별로 평가
   ▼
검증자(원본, 읽기 전용)
   • 모든 후보의 patch + 리뷰 + 코더 요약을 받음
   • 마지막 줄에 `채택: 후보 2` 형식으로 결정 (지시문에 고정)
   ▼
[적용]  채택 후보의 patch를 원본 작업 공간에 `git apply --3way` (인덱스는 건드리지 않음)
   • 성공: 요약 로그 "후보 2 적용됨 (파일 3, +48 -2)", 선택적으로 `isolation.verify-command` 실행 결과 기록
   • 실패(원본이 그 사이 바뀜 등): 작업은 SUCCEEDED 유지, "적용 실패 — 수동 적용 필요"로 표시, patch 파일 경로 안내
   • 결정 판독 실패: 적용하지 않고 "채택 후보를 판독하지 못함"으로 표시, UI에서 후보를 골라 적용
   ▼
[정리]  worktree 전부 제거(`git worktree remove --force` + `prune`), patch 파일은 작업 디렉터리에 보존
```

## 기준 커밋 만드는 법 (사용자 상태 불변)

```bash
export GIT_INDEX_FILE=<jobDir>/base.index
git read-tree HEAD
git add -A                     # 미추적 포함, .gitignore 존중
tree=$(git write-tree)
base=$(git commit-tree "$tree" -p HEAD -m "orchestrator base <jobId>")
unset GIT_INDEX_FILE
git worktree add --detach <worktrees>/<jobId>/c1 "$base"
```

HEAD가 없는 빈 저장소면 `-p` 없이 커밋한다. 커밋 객체는 브랜치에 연결되지 않으므로 사용자에겐 보이지 않고 나중에 gc로 사라진다.

## 후보 산출물

코더 k가 끝나면 worktree에서:

```bash
git add -A && git commit -q -m "candidate k"      # 실패해도(변경 없음) 계속
git diff --stat  <base> HEAD                      # 요약
git diff         <base> HEAD  > <jobDir>/candidates/c<k>.patch
```

리뷰어·검증자 프롬프트에 넣는 patch는 `isolation.max-patch-chars`(기본 40,000자)까지만 넣고, 넘치면 stat 전체 + 앞부분만 넣고 "전체는 <경로>"를 적는다. 1:1 리뷰어는 worktree 안이므로 파일을 직접 읽으면 된다.

## 데이터 모델 변경

### core
- `WorkspaceIsolation` 인터페이스: `prepare(jobId, count) → List<Candidate>`, `capture(candidate) → CandidatePatch`, `apply(patch, workspace)`, `cleanup(jobId)`. 구현 `GitWorktreeIsolation`.
- `ExecutionContext.workingDirectory()` 추가(null이면 모듈 기본). `CliAiModule`은 이 값을 우선 사용.
- `ExecutionResult`에 `candidate`(번호, worktree 경로, stat, patch 경로) 추가. 코더 단계에서 N ≥ 2일 때만 채워짐.
- `ExecutionReport`에 `decision`(채택 후보 번호 또는 null, 판독 실패 사유) 추가.
- `PromptCompiler.compileForStage`: 이전 단계가 후보를 만들었으면 "## 후보 k (claude opus/high)" 헤더 + 코더 요약 + stat + patch(제한) 형식으로 넣는다. 1:1 리뷰어에게는 자기 후보 전체 + 다른 후보의 stat 한 줄씩.
- `ExecutionManager`: 코더 단계 시작 전 `prepare`, 각 코더에 worktree 경로 전달, 종료 후 `capture`; 리뷰어 매핑 규칙 적용; 검증자 출력에서 결정 판독; 경쟁 단계의 실패 정책은 "후보 하나라도 성공하면 계속"(실패 후보는 FAILED 표시, 후보 목록에서 제외).
- 역할 지시문: 코더에 "다른 후보와 경쟁 중이다. 계획을 끝까지 구현하고 테스트를 통과시켜라", 검증자에 "마지막 줄에 `채택: 후보 N`을 반드시 쓴다".

### server
- 설정 `orchestrator.isolation`: `enabled`(기본 true), `link-dirs`, `keep-worktrees`(기본 false), `auto-apply`(기본 true), `max-patch-chars`, `verify-command`(선택).
- `JobSnapshot.candidates`: `[{index, agent, status, filesChanged, insertions, deletions, patchPath, chosen, applied}]`, `decision`.
- 적용 결과 이벤트: 요약 로그 + `applied` 필드.
- API: `POST /api/jobs/{id}/apply?candidate=k`(수동 적용·다른 후보 선택), `GET /api/jobs/{id}/candidates/{k}/patch`(patch 원문).
- 작업 종료(성공·실패·취소·시간 초과) 시 `cleanup`.

### web
- 작업 상세에 **후보 표**: 번호, 모델, 변경 파일/줄 수, 리뷰 한 줄 요약, 채택 표시, "patch 보기", "이 후보 적용" 버튼.
- 다이어그램의 코더·리뷰어 노드 라벨에 "후보 k" 배지.
- 대시보드 최근 작업에 채택 후보 표시.

## 실패 처리

| 상황 | 처리 |
|---|---|
| 작업 공간이 git 아님 | 작업 시작 전 FAILED, 메시지로 안내 |
| worktree 생성 실패 | 작업 FAILED, 만들어진 worktree 정리 |
| 코더 일부 실패 | 해당 후보 FAILED, 나머지로 진행. 전부 실패면 작업 FAILED |
| 후보 변경 없음(diff 비어 있음) | 후보 목록에 "변경 없음"으로 남기되 검증자에 전달 |
| 검증자 결정 판독 실패 | 적용 안 함, UI에서 수동 선택 |
| patch 적용 충돌 | 적용 안 함, patch 경로 안내, 수동 적용 |
| 취소·시간 초과 | worktree 정리, 이미 만든 patch는 보존 |

## 테스트 계획

- core 단위: 임시 git 저장소 + 파일을 쓰는 테스트 모듈로 `prepare → 코더 2개 → capture → apply → cleanup` 검증. 미추적 파일 포함 여부, 사용자 인덱스 불변, apply 후 원본 상태, cleanup 후 worktree 없음.
- 결정 판독: `채택: 후보 2`, `채택 후보: 2`, 영문 `ADOPT: candidate 2` 허용, 없으면 null.
- server: JobService에서 후보·결정·적용 이벤트가 스냅샷에 실리는지, 수동 apply API.
- 실모델 E2E: 1-2-2-1 프리셋을 sonnet/low로 샘플 프로젝트에 실행(약 $0.5), 후보 2개 patch 생성·리뷰어 1:1·검증자 채택·적용까지 확인.

## 구현 순서

1. core: `GitWorktreeIsolation` + 단위 테스트
2. core: ExecutionManager 통합(격리·후보·리뷰어 매핑·결정 판독) + 지시문 수정
3. server: 설정·스냅샷·적용·정리·API
4. web: 후보 표와 적용 버튼
5. 실모델 E2E, README·TODO 갱신
