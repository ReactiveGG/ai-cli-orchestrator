import type { JobStatus, StepStatus } from './types'

export const statusLabel: Record<JobStatus, string> = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '성공',
  FAILED: '실패',
  CANCELLED: '취소됨',
  TIMEOUT: '시간 초과',
}

export const statusTone: Record<JobStatus, string> = {
  QUEUED: 'bg-slate-200 text-slate-700 dark:bg-slate-700 dark:text-slate-200',
  RUNNING: 'bg-sky-100 text-sky-800 dark:bg-sky-900 dark:text-sky-200',
  SUCCEEDED: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-200',
  FAILED: 'bg-rose-100 text-rose-800 dark:bg-rose-900 dark:text-rose-200',
  CANCELLED: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
  TIMEOUT: 'bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200',
}

export const stepTone: Record<StepStatus, { bg: string; border: string; text: string }> = {
  PENDING: { bg: '#f1f5f9', border: '#cbd5e1', text: '#475569' },
  RUNNING: { bg: '#e0f2fe', border: '#0284c7', text: '#075985' },
  DONE: { bg: '#dcfce7', border: '#16a34a', text: '#166534' },
  FAILED: { bg: '#fee2e2', border: '#dc2626', text: '#991b1b' },
  SKIPPED: { bg: '#f5f5f4', border: '#a8a29e', text: '#57534e' },
}

export function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

export function formatCost(usd: number): string {
  return usd === 0 ? '$0' : `$${usd.toFixed(usd < 1 ? 3 : 2)}`
}

export function formatTime(iso: string | null): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export function formatDuration(start: string | null, end: string | null, now = Date.now()): string {
  if (!start) return '-'
  const ms = (end ? new Date(end).getTime() : now) - new Date(start).getTime()
  if (ms < 1000) return `${ms}ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  return `${m}m ${s % 60}s`
}

export function progress(job: { steps: { status: StepStatus }[]; status: JobStatus }): number {
  if (!job.steps.length) return job.status === 'SUCCEEDED' ? 100 : 0
  const done = job.steps.filter((s) => s.status === 'DONE').length
  return Math.round((100 * done) / job.steps.length)
}
