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
  /** 1-based stage index for agent steps, 0 for compile/route/aggregate */
  stage: number
  dependsOn: string[]
  status: StepStatus
  startedAt: string | null
  finishedAt: string | null
  error: string | null
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

export interface StageInfo {
  name: string
  role: string
  models: string[]
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

export interface Catalog {
  flows: FlowInfo[]
  options: OptionSpec[]
  modules: ModuleInfo[]
  roles: RoleInfo[]
}

/** Editable config (GET/PUT /api/config/routing). A stage is one role run by 1+ models in parallel. */
export interface StageDto {
  name: string | null
  role: string
  /** empty = the flow's defaultModule */
  models: string[]
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

export interface Settings {
  dataDir: string
  workspace: string
  routingFile: string
  concurrency: number
  moduleTimeoutSeconds: number
  idleWarningSeconds: number
}

export interface PlanStep {
  id: string
  label: string
  moduleName: string | null
  role: string | null
  stage: number
  dependsOn: string[]
}
