// Mirrors the server's JSON shapes (see server/src/main/java/dev/orchestrator/server).

export type TaskType = 'ANALYZE' | 'IMPLEMENT' | 'REVIEW' | 'VERIFY' | 'CUSTOM'
export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT'
export type StepStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
export type LogLevel = 'SUMMARY' | 'DETAIL'

export interface TokenUsage {
  inputTokens: number
  outputTokens: number
  costUsd: number
}

export interface JobStep {
  id: string
  label: string
  module: string | null
  role: string | null
  /** 1-based stage index */
  stage: number
  /** 1-based competing-candidate number for isolated coders, 0 otherwise */
  candidate: number
  dependsOn: string[]
  status: StepStatus
  startedAt: string | null
  finishedAt: string | null
  error: string | null
}

/** One competing coder's result (competition mode: presets with 2+ coders). */
export interface JobCandidate {
  index: number
  agent: string
  stepId: string
  filesChanged: number
  insertions: number
  deletions: number
  stat: string
  patchFile: string | null
  empty: boolean
  chosen: boolean
  applied: boolean
}

export interface Job {
  id: string
  status: JobStatus
  command: string
  flow: string
  flowLabel: string
  target: string
  focus: string[]
  language: string
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  lastOutputAt: string | null
  steps: JobStep[]
  usage: TokenUsage
  usageByModule: Record<string, TokenUsage>
  error: string | null
  result: string | null
  eventCount: number
  candidates: JobCandidate[]
  chosenCandidate: number
  applied: boolean
  decisionNote: string | null
}

export interface JobEvent {
  seq: number
  at: string
  jobId: string
  level: LogLevel
  stepId: string | null
  message: string
}

export interface JobRequest {
  commandLine?: string
  flow?: string
  target?: string
  focus?: string[]
  language?: string
}

export interface ModuleStatus {
  name: string
  description: string
  available: boolean
  version: string | null
  mode: 'cli' | 'stub'
  /** `claude auth status` result; null when unknown (stub, codex, probe failed) */
  loggedIn: boolean | null
  authMethod: string | null
  /** resolved executable path, null when not found */
  command: string | null
  /** where the server looked when the CLI was not found */
  searched: string | null
}

export interface RemoteStatus {
  indicator: string
  description: string
  checkedAt: string | null
}

export interface StatusReport {
  modules: ModuleStatus[]
  anthropic: RemoteStatus
}

export interface DailyUsage {
  date: string
  inputTokens: number
  outputTokens: number
  costUsd: number
  jobs: number
}

/** Subscription usage windows from the CLI's rate_limit_event (utilization 0..1); null fields = not reported. */
export interface RateLimitInfo {
  status: string | null
  fiveHourUtilization: number | null
  fiveHourResetsAt: string | null
  sevenDayUtilization: number | null
  sevenDayResetsAt: string | null
  rateLimitType: string | null
  observedAt: string
}

export interface Dashboard {
  usage: {
    today: TokenUsage
    total: TokenUsage
    byModule: Record<string, TokenUsage>
    last7Days: DailyUsage[]
  }
  status: StatusReport
  jobCounts: Record<JobStatus, number>
  concurrency: number
  running: number
  recentJobs: Job[]
  generatedAt: string
  /** null until a real Claude run reported its windows */
  subscription: RateLimitInfo | null
}

export interface OptionSpec {
  flag: string
  type: string
  repeatable: boolean
  defaultValue: string | null
  labelKo: string
  descriptionKo: string
}

export interface ModuleInfo {
  name: string
  description: string
  available: boolean
}

export interface RoleInfo {
  name: string
  label: string
  instructions: string
}

export interface AgentInfo {
  module: string
  model: string | null
  effort: string | null
}

export interface StageInfo {
  name: string
  role: string
  models: AgentInfo[]
}

export interface FlowInfo {
  name: string
  label: string
  /** agents per stage, e.g. 1-1-2-1 */
  signature: string
  task: string
  /** e.g. 계획(claude) → 리뷰(claude ∥ codex) */
  description: string
  stages: StageInfo[]
  defaultFocus: string[]
}

/** A slash command an agent chip can start with, and what it does. */
export interface SlashCommand {
  name: string
  kind: 'builtin' | 'project' | 'user' | 'skill'
  description: string
  argument: string | null
  scope: string[]
  source: string
}

export interface Catalog {
  flows: FlowInfo[]
  options: OptionSpec[]
  modules: ModuleInfo[]
  roles: RoleInfo[]
  commands: SlashCommand[]
}

/** One agent of a stage: tool + optional model variant (fable/opus/sonnet…) + optional effort. */
export interface AgentDto {
  module: string
  model: string | null
  effort: string | null
  /** slash command: `/plan` → CLI plan permission mode, `/name args` → first line of the prompt (project command / skill) */
  command: string | null
}

/** Editable config (GET/PUT /api/config/routing). A stage is one role run by 1+ agents in parallel. */
export interface StageDto {
  name: string | null
  role: string
  /** empty = one run on the flow's defaultModule */
  models: AgentDto[]
}

export interface FlowDto {
  label: string | null
  task: string | null
  defaultModule: string | null
  stages: StageDto[]
}

export interface RoleDto {
  label: string
  instructions: string
  builtIn: boolean
}

export interface FlowConfig {
  flows: Record<string, FlowDto>
  roles: Record<string, RoleDto>
  fallback: Record<string, string>
}

export interface ModuleSettings {
  mode: 'AUTO' | 'CLI' | 'STUB'
  command: string
  model: string | null
  maxBudgetUsd: number | null
  allowedTools: string[]
  extraArgs: string[]
}

export interface IsolationSettings {
  enabled: boolean
  autoApply: boolean
  keepWorktrees: boolean
  maxPatchChars: number
  linkDirs: string[]
  exclude: string[]
}

/** Runtime settings (GET/PUT /api/config/settings). dataDir/routingFile/settingsFile are read-only. */
export interface Settings {
  dataDir: string
  routingFile: string
  settingsFile: string
  workspace: string
  concurrency: number
  moduleTimeoutSeconds: number
  idleWarningSeconds: number
  modules: Record<string, ModuleSettings>
  isolation: IsolationSettings
}

export interface PlanStep {
  id: string
  label: string
  moduleName: string | null
  role: string | null
  stage: number
  candidate: number
  dependsOn: string[]
}
