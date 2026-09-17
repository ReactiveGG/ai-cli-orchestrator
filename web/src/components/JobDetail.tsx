import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, subscribe } from '../lib/api'
import type { Job, JobEvent } from '../lib/types'
import { FlowDiagram } from './FlowDiagram'
import { LogView } from './LogView'
import { StatusBadge } from './StatusBadge'
import { formatCost, formatDuration, formatTokens } from '../lib/format'
import { useNow } from '../lib/useNow'

export function JobDetail({ jobId, onCancel }: { jobId: string; onCancel: (id: string) => void }) {
  const initial = useQuery({ queryKey: ['job', jobId], queryFn: () => api.job(jobId) })
  const [job, setJob] = useState<Job | null>(null)
  const [events, setEvents] = useState<JobEvent[]>([])
  const [connected, setConnected] = useState(false)
  const now = useNow()

  useEffect(() => {
    setJob(null)
    setEvents([])
    const close = subscribe(
      `/api/jobs/${jobId}/events`,
      {
        job: (data) => setJob(data as Job),
        log: (data) => setEvents((prev) => {
          const e = data as JobEvent
          if (prev.length && prev[prev.length - 1].seq >= e.seq) return prev
          return [...prev, e]
        }),
      },
      () => setConnected(false),
    )
    setConnected(true)
    return close
  }, [jobId])

  const current = job ?? initial.data
  if (!current) return <div className="p-6 text-sm text-slate-400">불러오는 중…</div>

  const active = current.status === 'RUNNING' || current.status === 'QUEUED'
  const idleSec = current.status === 'RUNNING' && current.lastOutputAt ? Math.floor((now - new Date(current.lastOutputAt).getTime()) / 1000) : 0

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="rounded-lg border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-slate-900">
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={current.status} />
          <span className="rounded-full bg-violet-100 px-2 py-0.5 text-xs text-violet-800 dark:bg-violet-900 dark:text-violet-200">{current.flowLabel}</span>
          <span className="mono text-sm font-medium">{current.command}</span>
          <span className="ml-auto text-xs text-slate-500">
            {formatDuration(current.startedAt, current.finishedAt, now)}
            {idleSec > 30 && <span className="ml-2 text-amber-600">출력 없음 {idleSec}s</span>}
            {!connected && active && <span className="ml-2 text-rose-600">스트림 끊김</span>}
          </span>
          {active && (
            <button onClick={() => onCancel(current.id)} className="rounded border border-rose-300 px-2 py-1 text-xs text-rose-700 hover:bg-rose-50 dark:border-rose-800 dark:text-rose-300">
              취소
            </button>
          )}
        </div>
        <div className="mt-2 flex flex-wrap gap-4 text-xs text-slate-600 dark:text-slate-300">
          <span>입력 {formatTokens(current.usage.inputTokens)} · 출력 {formatTokens(current.usage.outputTokens)} · {formatCost(current.usage.costUsd)}</span>
          {Object.entries(current.usageByModule).map(([m, u]) => (
            <span key={m}>{m}: {formatTokens(u.inputTokens + u.outputTokens)}</span>
          ))}
          {current.error && <span className="text-rose-600">{current.error}</span>}
        </div>
        <div className="mt-3">
          <FlowDiagram
            height={Math.max(220, Math.min(520, 130 + 90 * Math.max(1, ...Object.values(current.steps.reduce<Record<number, number>>((acc, s) => { acc[s.stage] = (acc[s.stage] ?? 0) + 1; return acc }, {})))))}
            steps={current.steps.map((s) => ({ id: s.id, label: s.label, module: s.module, role: s.role, dependsOn: s.dependsOn, status: s.status }))}
          />
        </div>
      </div>
      <div className="min-h-64 flex-1">
        <LogView events={events} jobId={current.id} />
      </div>
      {current.result && (
        <details className="rounded-lg border border-slate-200 bg-white p-3 text-sm dark:border-slate-800 dark:bg-slate-900">
          <summary className="cursor-pointer font-medium">최종 결과</summary>
          <pre className="mono mt-2 max-h-72 overflow-auto whitespace-pre-wrap text-xs">{current.result}</pre>
        </details>
      )}
    </div>
  )
}
