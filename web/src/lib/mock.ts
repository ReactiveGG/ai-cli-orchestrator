// In-browser stand-in for the Spring Boot server. Enabled with `vite --mode mock`
// (see .env.mock). Same shapes as the real API; jobs run on a timer so the UI
// shows progress, streaming logs and cancellation without any backend.
import type {
  AgentDto, Catalog, Dashboard, FlowConfig, FlowDto, FlowInfo, Job, JobEvent, JobRequest, JobStep, LogLevel, PlanStep, StageDto, Settings, StatusReport, TokenUsage,
} from './types'

const OPTIONS: Catalog['options'] = [
  { flag: '--focus', type: 'string', repeatable: true, defaultValue: null, labelKo: '집중 영역', descriptionKo: '프롬프트에 강조할 관점. 여러 번 지정 가능.' },
  { flag: '--language', type: 'string', repeatable: false, defaultValue: 'ko', labelKo: '응답 언어', descriptionKo: 'AI 응답 언어 코드. 기본값 ko.' },
]
const MODULES: StatusReport['modules'] = [
  { name: 'claude', description: 'cli: claude', available: true, version: '2.1.274 (Claude Code)', mode: 'cli' },
  { name: 'codex', description: 'stub', available: false, version: null, mode: 'stub' },
]
const BUILT_IN_ROLES: FlowConfig['roles'] = {
  executor: { label: '실행', builtIn: true, instructions: '' },
  planner: { label: '플래너', builtIn: true, instructions: '역할: 플래너. 요청을 분석해 구현 계획만 작성한다.\n- 코드를 수정하지 않는다.\n- 변경할 파일, 순서, 위험 요소, 검증 방법을 번호 목록으로 정리한다.' },
  coder: { label: '코더', builtIn: true, instructions: '역할: 코더. 이전 단계의 계획을 그대로 구현한다.\n- 계획에 없는 범위를 확장하지 않는다.\n- 변경한 파일과 이유를 마지막에 요약한다.' },
  reviewer: { label: '리뷰어', builtIn: true, instructions: '역할: 리뷰어. 이전 단계의 변경 사항을 검토한다.\n- 정확성/회귀/유지보수성 문제를 심각도 순으로 나열한다.' },
  verifier: { label: '검증자', builtIn: true, instructions: '역할: 검증자. 이전 단계들의 결론을 독립적으로 재검토한다.' },
}
const DEFAULT_FOCUS: Record<string, string[]> = {
  analyze: ['architecture', 'dependencies', 'risks'], implement: ['requirements', 'edge cases', 'tests'],
  review: ['correctness', 'regressions', 'maintainability'], verify: ['cross-check findings', 'test coverage', 'release risk'], custom: ['requirements', 'risks', 'verification'],
}
const RESULTS: Record<string, string> = {
  planner: '1. src/auth/refresh.ts 신설: 만료 검사 + 재발급\n2. session.ts에서 refresh() 호출로 교체\n3. 테스트: 만료 직전/직후, 재사용 토큰 거부',
  coder: '변경 파일:\n- src/auth/refresh.ts (신규)\n- src/auth/session.ts (호출 교체)\n- test/auth/refresh.test.ts\n모든 테스트 통과 (12/12)',
  reviewer: '[중간] refresh.ts:31 만료 시각을 UTC로 통일 필요\n[낮음] session.ts:14 에러 메시지에 토큰 일부 노출\n그 외 승인',
  verifier: '동의: 만료 비교 문제 재현됨\n반대: 토큰 노출은 마스킹 처리되어 있음\n남은 위험: 동시 갱신 경쟁 조건',
  plain: '분석 결과: 의존성 3개, 위험 요소 2개(순환 없음, 미사용 export 4개)',
}
const DETAIL: Record<string, string[]> = {
  claude: ['{"type":"system","subtype":"init","model":"claude-opus-5"}', '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read"}]}}', '{"type":"assistant","message":{"content":[{"type":"text","text":"토큰 갱신 경로에서 만료 검사가 누락되어 있습니다."}]}}', '{"type":"result","subtype":"success","total_cost_usd":0.0412}'],
  codex: ['{"type":"thread.started"}', '{"type":"item.completed","item":{"type":"command_execution","command":"rg -n refresh src/"}}', '{"type":"item.completed","item":{"type":"agent_message","text":"순환 의존성이 없습니다."}}', '{"type":"turn.completed","usage":{"input_tokens":2210,"output_tokens":418}}'],
}

const CONCURRENCY = 2
const KEY = 'orch-mock-flows'

const ag = (module: string, model: string | null = null, effort: string | null = null): AgentDto => ({ module, model, effort })
const st = (role: string, models: AgentDto[] = []): StageDto => ({ name: null, role, models })
const PIPELINE = ['planner', 'coder', 'reviewer', 'verifier']
const preset = (label: string, module: string, counts: number[]): FlowDto => ({ label, task: 'custom', defaultModule: module, stages: PIPELINE.map((r, i) => st(r, Array.from({ length: counts[i] ?? 1 }, () => ag(module)))) })
const describeAgent = (a: AgentDto) => a.model || a.effort ? `${a.module} ${a.model ?? '기본'}${a.effort ? '/' + a.effort : ''}` : a.module
function singleConfig(module: string): FlowConfig {
  return {
    flows: {
      default: preset('기본', module, [1, 1, 1, 1]),
      'cross-review': preset('교차 리뷰', module, [1, 1, 2, 1]),
      'best-of-3': preset('3안 비교', module, [1, 3, 3, 1]),
    },
    roles: structuredClone(BUILT_IN_ROLES), fallback: {},
  }
}
const defaultConfig = () => singleConfig('claude')
export const signatureOf = (f: FlowDto) => f.stages.map((s) => Math.max(1, s.models.length)).join('-')
function loadConfig(): FlowConfig {
  try { const raw = localStorage.getItem(KEY); if (raw) return JSON.parse(raw) } catch { /* ignore */ }
  return defaultConfig()
}
function persist() { try { localStorage.setItem(KEY, JSON.stringify(config)) } catch { /* ignore */ } }

let config = loadConfig()
let seq = 0
let idCounter = 1
type Listener = (name: string, data: unknown) => void
const listeners = new Map<string, Set<Listener>>()
interface MockJob extends Job { events: JobEvent[]; ticks: number; stuck?: boolean; patches: Record<number, string> }
const jobs: MockJob[] = []

const modelsOf = (s: StageDto, flow: FlowDto): AgentDto[] => (s.models.length ? s.models : [ag(flow.defaultModule ?? 'claude')])
const stageLabel = (s: StageDto) => s.name ?? config.roles[s.role]?.label ?? s.role
const passThrough = (role: string) => !(config.roles[role]?.instructions ?? '').trim()
function describe(flow: FlowDto): string {
  return flow.stages.map((s) => `${stageLabel(s)}(${modelsOf(s, flow).map(describeAgent).join(' ∥ ')})`).join(' → ')
}
function flowInfo(name: string, flow: FlowDto): FlowInfo {
  return { name, label: flow.label ?? name, signature: signatureOf(flow), task: flow.task ?? 'custom', description: describe(flow), stages: flow.stages.map((s) => ({ name: stageLabel(s), role: s.role, models: modelsOf(s, flow) })), defaultFocus: DEFAULT_FOCUS[flow.task ?? 'custom'] ?? DEFAULT_FOCUS.custom }
}
function planFor(flowName: string): PlanStep[] {
  const flow = config.flows[flowName]
  if (!flow) throw new Error(`Unknown flow: ${flowName}`)
  const steps: PlanStep[] = []
  let prev: string[] = []
  flow.stages.forEach((stage, si) => {
    const ids: string[] = []
    const seen = new Map<string, number>()
    const role = passThrough(stage.role) ? null : stage.role
    for (const a of modelsOf(stage, flow)) {
      const m = a.module
      const key = role ? `${role}@${m}` : m
      const dup = seen.get(key) ?? 0
      seen.set(key, dup + 1)
      const id = `s${si + 1}/${key}${dup ? `#${dup}` : ''}`
      const competing = stage.role === 'coder' && modelsOf(stage, flow).length > 1
      const k = ids.length + 1
      steps.push({ id, label: `${stageLabel(stage)} (${describeAgent(a)})${competing ? ` · 후보 ${k}` : ''}`, moduleName: m, role, stage: si + 1, candidate: competing ? k : 0, dependsOn: prev })
      ids.push(id)
    }
    prev = ids
  })
  return steps
}

function tokenize(line: string): string[] {
  const out: string[] = []
  let cur = ''; let q: string | null = null; let inTok = false
  for (const c of line) {
    if (q) { if (c === q) q = null; else cur += c }
    else if (c === '"' || c === "'") { q = c; inTok = true }
    else if (/\s/.test(c)) { if (inTok) { out.push(cur); cur = ''; inTok = false } }
    else { cur += c; inTok = true }
  }
  if (inTok) out.push(cur)
  return out
}
interface Parsed { flow: string; target: string; focus: string[]; language: string }
function parse(req: JobRequest): Parsed {
  let flow = req.flow ?? ''
  let target: string[] = []; const focus: string[] = []
  let language = 'ko'
  if (req.commandLine?.trim()) {
    const t = tokenize(req.commandLine)
    let start = t[0]?.toLowerCase() === 'run' ? 1 : 0
    for (let i = start; i < t.length; i++) {
      const tok = t[i]
      const need = () => { if (i + 1 >= t.length) throw new Error(`${tok} needs a value`); return t[++i] }
      if (tok === '--preset' || tok === '-p') { const p = need(); if (!req.flow) flow = p }
      else if (tok === '--focus') focus.push(need())
      else if (tok === '--language') language = need()
      else if (tok.startsWith('--preset=')) { if (!req.flow) flow = tok.slice(9) }
      else if (tok.startsWith('--')) throw new Error(`Unknown option: ${tok}`)
      else target.push(tok)
    }
  } else {
    target = req.target?.trim() ? [req.target.trim()] : []
    focus.push(...(req.focus ?? [])); language = req.language || 'ko'
  }
  if (!target.length) throw new Error('Target is required')
  if (!flow) flow = config.flows.default ? 'default' : Object.keys(config.flows)[0]
  if (!config.flows[flow]) throw new Error(`Unknown preset: ${flow}`)
  return { flow, target: target.join(' '), focus, language }
}
function display(r: Parsed): string {
  const p = [r.target.includes(' ') ? `"${r.target}"` : r.target]
  if (r.flow !== 'default') p.push('--preset', r.flow)
  r.focus.forEach((f) => p.push('--focus', f))
  if (r.language !== 'ko') p.push('--language', r.language)
  return p.join(' ')
}

const zero = (): TokenUsage => ({ inputTokens: 0, outputTokens: 0, costUsd: 0 })
const snapshot = (j: MockJob): Job => { const { events: _e, ticks: _t, stuck: _s, patches: _p, ...rest } = j; return { ...rest, eventCount: j.events.length } }
const FAKE_PATCH = (k: number) => `diff --git a/greeting.py b/greeting.py\n--- a/greeting.py\n+++ b/greeting.py\n@@ -1,4 +1,${4 + k} @@\n def greet(name):\n+    if name is None or not str(name).strip():\n+        return "Hello, stranger!"  # candidate ${k}\n     return f"Hello, {name}!"\n`
function addCandidate(job: MockJob, s: JobStep) {
  const k = s.candidate
  job.patches[k] = FAKE_PATCH(k)
  job.candidates = [...job.candidates, { index: k, agent: `${s.module}/coder`, stepId: s.id, filesChanged: 1 + (k % 2), insertions: 2 + k, deletions: k - 1, stat: ` greeting.py | ${2 + k} ++-\n 1 file changed`, patchFile: `~/.ai-orchestrator/jobs/${job.id}/candidates/c${k}.patch`, empty: false, chosen: false, applied: false }].sort((a, b) => a.index - b.index)
  log(job, 'SUMMARY', s.id, `후보 ${k} 변경 추출: ${1 + (k % 2)} files, +${2 + k} -${k - 1}`)
}
function decide(job: MockJob) {
  if (!job.candidates.length) return
  const chosen = job.candidates.length >= 2 ? 2 : 1
  job.chosenCandidate = chosen; job.applied = true
  job.decisionNote = `후보 ${chosen} 적용됨 (${job.candidates[chosen - 1].filesChanged} files, +${job.candidates[chosen - 1].insertions} -${job.candidates[chosen - 1].deletions})`
  job.candidates = job.candidates.map((c) => ({ ...c, chosen: c.index === chosen, applied: c.index === chosen }))
  log(job, 'SUMMARY', null, job.decisionNote)
}
const emit = (key: string, name: string, data: unknown) => listeners.get(key)?.forEach((l) => l(name, data))
function log(job: MockJob, level: LogLevel, stepId: string | null, message: string, at = Date.now()) {
  const e: JobEvent = { seq: ++seq, at: new Date(at).toISOString(), jobId: job.id, level, stepId, message }
  job.events.push(e); job.lastOutputAt = e.at; emit(job.id, 'log', e)
}
function publish(job: MockJob) { const s = snapshot(job); emit(job.id, 'job', s); emit('all', 'job', s) }
function makeJob(r: Parsed, createdAt = Date.now()): MockJob {
  const flow = config.flows[r.flow]
  const steps: JobStep[] = planFor(r.flow).map((s) => ({ id: s.id, label: s.label, module: s.moduleName, role: s.role, stage: s.stage, candidate: s.candidate, dependsOn: s.dependsOn, status: 'PENDING', startedAt: null, finishedAt: null, error: null }))
  const job: MockJob = {
    id: `mock-${(idCounter++).toString().padStart(4, '0')}`, status: 'QUEUED', command: display(r), flow: r.flow, flowLabel: flow.label ?? r.flow, target: r.target, focus: r.focus, language: r.language,
    createdAt: new Date(createdAt).toISOString(), startedAt: null, finishedAt: null, lastOutputAt: null, steps, usage: zero(), usageByModule: {}, error: null, result: null, eventCount: 0, events: [], ticks: 0,
    candidates: [], chosenCandidate: 0, applied: false, decisionNote: null, patches: {},
  }
  log(job, 'SUMMARY', null, `대기열에 추가됨: ${job.command}`, createdAt)
  return job
}
function finish(job: MockJob, status: Job['status'], error: string | null = null) {
  job.status = status; job.finishedAt = new Date().toISOString(); job.error = error
  job.steps.forEach((s) => { if (s.status === 'RUNNING') s.status = 'FAILED'; else if (s.status === 'PENDING') s.status = 'SKIPPED' })
  log(job, 'SUMMARY', null, ({ SUCCEEDED: '성공', CANCELLED: '취소됨: 사용자 취소', TIMEOUT: `시간 초과: ${error}`, FAILED: `실패: ${error}` } as Record<string, string>)[status] ?? status)
  publish(job)
}
function addUsage(job: MockJob, module: string) {
  const inp = 1800 + Math.floor(Math.random() * 1800); const out = 300 + Math.floor(Math.random() * 500)
  const cost = module === 'claude' ? +(inp * 0.000015 + out * 0.000075).toFixed(4) : 0
  job.usage = { inputTokens: job.usage.inputTokens + inp, outputTokens: job.usage.outputTokens + out, costUsd: job.usage.costUsd + cost }
  const u = job.usageByModule[module] ?? zero()
  job.usageByModule = { ...job.usageByModule, [module]: { inputTokens: u.inputTokens + inp, outputTokens: u.outputTokens + out, costUsd: u.costUsd + cost } }
  return { inp, out }
}
function completeAgent(job: MockJob, s: JobStep, at?: number) {
  const { inp, out } = addUsage(job, s.module!)
  log(job, 'SUMMARY', s.id, `${s.module} 완료: 입력 ${inp} / 출력 ${out} 토큰`, at)
  const text = RESULTS[s.role ?? 'plain'] ?? RESULTS.plain
  log(job, 'DETAIL', s.id, `--- ${s.module}${s.role ? '/' + s.role : ''} 결과 ---`, at)
  text.split('\n').forEach((l) => log(job, 'DETAIL', s.id, l, at))
  job.result = text
}
/** Whole stages advance together: all agents of the running stage finish on the same tick. */
function tick() {
  let running = jobs.filter((j) => j.status === 'RUNNING').length
  for (const job of [...jobs].sort((a, b) => a.createdAt.localeCompare(b.createdAt))) {
    if (job.status === 'QUEUED' && running < CONCURRENCY) { job.status = 'RUNNING'; job.startedAt = new Date().toISOString(); running++; log(job, 'SUMMARY', null, '실행 시작'); publish(job) }
    if (job.status !== 'RUNNING' || job.stuck) continue
    const current = job.steps.filter((s) => s.status === 'RUNNING')
    if (!current.length) {
      const next = job.steps.find((s) => s.status === 'PENDING')
      if (!next) { finish(job, 'SUCCEEDED'); continue }
      const batch = next.stage ? job.steps.filter((s) => s.stage === next.stage && s.status === 'PENDING') : [next]
      job.ticks = 0
      for (const s of batch) {
        s.status = 'RUNNING'; s.startedAt = new Date().toISOString()
        if (s.module) { log(job, 'SUMMARY', s.id, `${s.module} 시작 (${MODULES.find((m) => m.name === s.module)?.mode === 'cli' ? 'cli: ' + s.module : 'stub'})`); log(job, 'DETAIL', s.id, s.module === 'claude' ? '$ claude -p --output-format stream-json --verbose' : '$ codex exec --json -') }
      }
      publish(job); continue
    }
    job.ticks++
    for (const s of current) if (s.module && job.ticks <= 3) log(job, 'DETAIL', s.id, DETAIL[s.module]?.[job.ticks - 1] ?? '')
    if (job.ticks >= 4) {
      for (const s of current) { s.status = 'DONE'; s.finishedAt = new Date().toISOString(); completeAgent(job, s); if (s.candidate) addCandidate(job, s) }
      if (!job.steps.some((s) => s.status === 'PENDING')) { decide(job); log(job, 'SUMMARY', null, `완료: 에이전트 ${job.steps.length}개, 토큰 ${job.usage.inputTokens + job.usage.outputTokens}`) }
      publish(job)
    }
  }
}

function seed() {
  const now = Date.now()
  const done = (line: string, minutesAgo: number, after?: (j: MockJob) => void) => {
    const j = makeJob(parse({ commandLine: line }), now - minutesAgo * 60000)
    let t = now - minutesAgo * 60000 + 1500
    j.status = 'RUNNING'; j.startedAt = new Date(t).toISOString(); log(j, 'SUMMARY', null, '실행 시작', t)
    log(j, 'SUMMARY', null, `프리셋 ${j.flowLabel} ${signatureOf(config.flows[j.flow])}: ${describe(config.flows[j.flow])}`, t)
    let lastStage = -1
    for (const s of j.steps) {
      if (s.stage !== lastStage) { t += 45000; lastStage = s.stage }
      s.status = 'DONE'; s.startedAt = new Date(t - 40000).toISOString(); s.finishedAt = new Date(t).toISOString()
      if (s.module) { log(j, 'SUMMARY', s.id, `${s.module} 시작 (cli: ${s.module})`, t - 40000); DETAIL[s.module]?.forEach((l, i) => log(j, 'DETAIL', s.id, l, t - 38000 + i * 8000)); completeAgent(j, s, t); if (s.candidate) addCandidate(j, s) }
    }
    decide(j)
    j.status = 'SUCCEEDED'; j.finishedAt = new Date(t).toISOString(); log(j, 'SUMMARY', null, '성공', t)
    after?.(j); jobs.push(j)
  }
  done('src/auth/ --focus security --focus architecture', 52)
  done('"jwt refresh token flow" --preset cross-review', 38)
  done('loginService.ts --preset best-of-3', 21)
  done('src/payments/ --focus correctness', 15, (j) => { j.status = 'FAILED'; j.error = 'claude exited with code 1: rate limit reached'; const s = j.steps.find((x) => x.role === 'reviewer')!; s.status = 'FAILED'; s.error = 'exit 1'; j.steps.filter((x) => x.stage > 3).forEach((x) => { x.status = 'SKIPPED' }); j.events = j.events.filter((e) => e.message !== '성공'); log(j, 'SUMMARY', null, `실패: ${j.error}`) })
  done('"배치 리포트 내보내기"', 9, (j) => { j.status = 'TIMEOUT'; j.error = 'claude exceeded timeout of 600s'; const c = j.steps.find((x) => x.module)!; c.status = 'FAILED'; j.events = j.events.filter((e) => e.message !== '성공'); log(j, 'SUMMARY', c.id, '경고: claude 출력이 62초 동안 없습니다'); log(j, 'SUMMARY', null, `시간 초과: ${j.error}`) })
  const stuck = makeJob(parse({ commandLine: 'src/legacy/ --focus maintainability' }), now - 4 * 60000)
  stuck.status = 'RUNNING'; stuck.startedAt = new Date(now - 4 * 60000).toISOString(); stuck.stuck = true
  stuck.steps[0].status = 'DONE'; stuck.steps[1].status = 'RUNNING'
  log(stuck, 'SUMMARY', null, '실행 시작', now - 4 * 60000); log(stuck, 'SUMMARY', null, '프리셋 기본 1-1-1-1: 플래너(claude) → 코더(claude) → 리뷰어(claude) → 검증자(claude)', now - 239000); log(stuck, 'SUMMARY', stuck.steps[1].id, 'claude 시작 (cli: claude)', now - 238000)
  log(stuck, 'SUMMARY', stuck.steps[1].id, '경고: claude 출력이 61초 동안 없습니다', now - 47000)
  stuck.lastOutputAt = new Date(now - 110000).toISOString()
  jobs.push(stuck)
}
seed()
setInterval(tick, 900)

const delay = <T,>(v: T, ms = 60): Promise<T> => new Promise((res) => setTimeout(() => res(v), ms))
let mockSettings: Settings = {
  dataDir: '~/.ai-orchestrator', routingFile: '~/.ai-orchestrator/orchestrator.yml', settingsFile: '~/.ai-orchestrator/settings.yml',
  workspace: 'C:\\dev\\my-service', concurrency: CONCURRENCY, moduleTimeoutSeconds: 600, idleWarningSeconds: 60,
  modules: {
    claude: { mode: 'AUTO', command: 'claude', model: null, maxBudgetUsd: 2, allowedTools: ['Bash(git status*)', 'Bash(git diff*)', 'Bash(python3 -m pytest*)', 'Bash(npm test*)'], extraArgs: [] },
    codex: { mode: 'AUTO', command: 'codex', model: null, maxBudgetUsd: null, allowedTools: [], extraArgs: [] },
  },
  isolation: { enabled: true, autoApply: true, keepWorktrees: false, maxPatchChars: 40000, linkDirs: ['node_modules', '.venv', 'target', 'build'], exclude: ['__pycache__', '*.pyc', '.DS_Store'] },
}
const find = (id: string): MockJob => { const j = jobs.find((x) => x.id === id); if (!j) throw new Error(`No job ${id}`); return j }
const sorted = () => [...jobs].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(snapshot)
const sum = (list: Job[]): TokenUsage => list.reduce((a, j) => ({ inputTokens: a.inputTokens + j.usage.inputTokens, outputTokens: a.outputTokens + j.usage.outputTokens, costUsd: a.costUsd + j.usage.costUsd }), zero())
const status = (): StatusReport => ({ modules: MODULES, anthropic: { indicator: 'none', description: 'All Systems Operational', checkedAt: new Date().toISOString() } })

export const mockApi = {
  dashboard: (): Promise<Dashboard> => {
    const all = sorted(); const today = new Date().toDateString()
    const byModule: Record<string, TokenUsage> = {}
    all.forEach((j) => Object.entries(j.usageByModule).forEach(([m, u]) => { const b = byModule[m] ?? zero(); byModule[m] = { inputTokens: b.inputTokens + u.inputTokens, outputTokens: b.outputTokens + u.outputTokens, costUsd: b.costUsd + u.costUsd } }))
    const hist = [[18400, 4100], [22100, 5200], [9700, 2600], [31200, 7900], [26800, 6100], [12900, 3300]]
    const last7Days = Array.from({ length: 7 }, (_, k) => { const i = 6 - k; const d = new Date(); d.setDate(d.getDate() - i); const t = i ? { inputTokens: hist[6 - i][0], outputTokens: hist[6 - i][1], costUsd: 0 } : sum(all); return { date: d.toISOString().slice(0, 10), inputTokens: t.inputTokens, outputTokens: t.outputTokens, costUsd: t.costUsd, jobs: i ? 3 : all.length } })
    const jobCounts = { QUEUED: 0, RUNNING: 0, SUCCEEDED: 0, FAILED: 0, CANCELLED: 0, TIMEOUT: 0 } as Dashboard['jobCounts']
    all.forEach((j) => { jobCounts[j.status]++ })
    return delay({ usage: { today: sum(all.filter((j) => new Date(j.createdAt).toDateString() === today)), total: sum(all), byModule, last7Days }, status: status(), jobCounts, concurrency: CONCURRENCY, running: jobCounts.RUNNING, recentJobs: all.slice(0, 10), generatedAt: new Date().toISOString() })
  },
  status: (): Promise<StatusReport> => delay(status()),
  catalog: (): Promise<Catalog> => delay({ flows: Object.entries(config.flows).map(([n, f]) => flowInfo(n, f)), options: OPTIONS, modules: MODULES.map((m) => ({ name: m.name, description: m.description, available: m.available })), roles: Object.entries(config.roles).map(([name, r]) => ({ name, label: r.label, instructions: r.instructions })) }),
  jobs: (): Promise<Job[]> => delay(sorted()),
  job: (id: string): Promise<Job> => delay(snapshot(find(id))),
  submit: (body: JobRequest): Promise<Job> => { const j = makeJob(parse(body)); jobs.push(j); publish(j); setTimeout(tick, 200); return delay(snapshot(j)) },
  submitBatch: (body: JobRequest[]): Promise<Job[]> => { const made = body.map(parse).map((p) => makeJob(p)); jobs.push(...made); made.forEach(publish); setTimeout(tick, 200); return delay(made.map(snapshot)) },
  preview: (body: JobRequest): Promise<PlanStep[]> => delay(planFor(parse(body).flow)),
  applyCandidate: (id: string, candidate: number): Promise<Job> => { const j = find(id); const c = j.candidates.find((x) => x.index === candidate); if (!c) throw new Error(`후보 ${candidate}이(가) 없습니다`); j.chosenCandidate = candidate; j.applied = true; j.decisionNote = `후보 ${candidate} 수동 적용됨 (${c.filesChanged} files, +${c.insertions} -${c.deletions})`; j.candidates = j.candidates.map((x) => ({ ...x, chosen: x.index === candidate, applied: x.index === candidate })); log(j, 'SUMMARY', null, j.decisionNote); publish(j); return delay(snapshot(j)) },
  candidatePatch: (id: string, candidate: number): Promise<string> => delay(find(id).patches[candidate] ?? ''),
  cancel: (id: string): Promise<Job> => { const j = find(id); if (j.status === 'QUEUED') { log(j, 'SUMMARY', null, '대기 중 취소됨'); finish(j, 'CANCELLED') } else if (j.status === 'RUNNING') { log(j, 'SUMMARY', null, '취소 요청됨, 실행 중인 프로세스를 종료합니다'); j.stuck = false; finish(j, 'CANCELLED') } return delay(snapshot(j)) },
  remove: (id: string): Promise<void> => { const i = jobs.findIndex((j) => j.id === id); if (i >= 0) jobs.splice(i, 1); return delay(undefined) },
  logs: (id: string, level: LogLevel, after = 0): Promise<JobEvent[]> => delay(find(id).events.filter((e) => e.level === level && e.seq > after)),
  routing: (): Promise<FlowConfig> => delay(structuredClone(config)),
  saveRouting: (body: FlowConfig): Promise<FlowConfig> => { config = structuredClone(body); persist(); return delay(structuredClone(config)) },
  resetRouting: (preset?: string): Promise<FlowConfig> => { config = preset?.startsWith('single:') ? singleConfig(preset.slice(7)) : defaultConfig(); persist(); return delay(structuredClone(config)) },
  routingYaml: async (): Promise<string> => {
    let y = 'flows:\n'
    for (const [n, f] of Object.entries(config.flows)) {
      y += `  ${n}:\n    label: ${f.label ?? n}\n` + (f.defaultModule ? `    defaultModule: ${f.defaultModule}\n` : '') + '    stages:\n'
      for (const s of f.stages) y += `      - role: ${s.role}\n` + (s.models.length ? `        models: [${s.models.map((a) => a.model || a.effort ? `${a.module}${a.model ? ':' + a.model : ''}${a.effort ? '/' + a.effort : ''}` : a.module).join(', ')}]\n` : '')
    }
    y += Object.keys(config.fallback).length ? 'fallback:\n' + Object.entries(config.fallback).map(([k, v]) => `  ${k}: ${v}`).join('\n') + '\n' : 'fallback: {}\n'
    return y
  },
  settings: (): Promise<Settings> => delay(structuredClone(mockSettings)),
  saveSettings: (body: Settings): Promise<Settings> => { mockSettings = structuredClone(body); return delay(structuredClone(mockSettings)) },
}

/** Timer-driven replacement for the SSE stream. */
export function mockSubscribe(url: string, handlers: Record<string, (data: unknown) => void>): () => void {
  const m = url.match(/^\/api\/jobs\/([^/]+)\/events/)
  const key = m ? m[1] : 'all'
  const listener: Listener = (name, data) => handlers[name]?.(data)
  if (!listeners.has(key)) listeners.set(key, new Set())
  listeners.get(key)!.add(listener)
  setTimeout(() => {
    if (key === 'all') handlers.jobs?.(sorted())
    else { try { const j = find(key); handlers.job?.(snapshot(j)); j.events.forEach((e) => handlers.log?.(e)) } catch { /* removed */ } }
  }, 0)
  return () => { listeners.get(key)?.delete(listener) }
}
