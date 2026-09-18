import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, subscribe } from '../lib/api'
import type { Job, JobEvent } from '../lib/types'
import { FlowDiagram } from './FlowDiagram'
import { LogView } from './LogView'
import { CandidatePanel } from './CandidatePanel'
import { StatusBadge } from './StatusBadge'
import { ErrorBox, Loading } from './Feedback'
import { errorMessage } from '../lib/errors'
import { formatCost, formatDuration, formatTokens } from '../lib/format'
import { useNow } from '../lib/useNow'

export function JobDetail({ jobId, onCancel, onBack }: { jobId: string; onCancel: (id: string) => void; onBack?: () => void }) {
  const initial = useQuery({ queryKey: ['job', jobId], queryFn: () => api.job(jobId), retry: false })
  const [job, setJob] = useState<Job | null>(null)
  const [events, setEvents] = useState<JobEvent[]>([])
  const [connected, setConnected] = useState(false)
  const lastSeq = useRef(0)
  const now = useNow()

  useEffect(() => {
    setJob(null)
    setEvents([])
    lastSeq.current = 0
    const close = subscribe(
      `/api/jobs/${jobId}/events`,
      {
        job: (data) => setJob(data as Job),
        log: (data) => setEvents((prev) => {
          const e = data as JobEvent
          if (prev.length && prev[prev.length - 1].seq >= e.seq) return prev
          lastSeq.current = e.seq
          return [...prev, e]
        }),
      },
      () => setConnected(false),
      () => lastSeq.current,
    )
    setConnected(true)
    return close
  }, [jobId])

  const current = job ?? initial.data
  if (!current && initial.isError) {
    const notFound = /찾을 수 없|404|No job|not found/i.test(errorMessage(initial.error))
    return (
      <div className="space-y-3">
        <ErrorBox title={notFound ? '작업을 찾을 수 없습니다' : '작업을 불러오지 못했습니다'} message={notFound ? `${jobId} — 삭제됐거나 다른 데이터 디렉터리의 작업입니다.` : errorMessage(initial.error)} onRetry={notFound ? undefined : () => initial.refetch()} />
        {onBack && <button onClick={onBack} className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100 dark:border-slate-700 dark:hover:bg-slate-800">목록으로</button>}
      </div>
    )
  }
  if (!current) return <Loading label="작업 불러오는 중…" />

  const active = current.status === 'RUNNING' || current.status === 'QUEUED'
  const idleSec = current.status === 'RUNNING' && current.lastOutputAt ? Math.floor((now - new Date(current.lastOutputAt).getTime()) / 1000) : 0

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="rounded-lg border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-slate-900">
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={current.status} />
          <span className="rounded-full bg-violet-100 px-2 py-0.5 text-xs text-violet-800 dark:bg-violet-900 dark:text-violet-200">{current.flowLabel}</span>
          <span className="mono min-w-0 break-all text-sm font-medium">{current.command}</span>
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
            steps={current.steps.map((s) => ({ id: s.id, label: s.label, module: s.module, role: s.role, dependsOn: s.dependsOn, status: s.status, candidate: s.candidate,
              outcome: s.candidate > 0 && current.chosenCandidate > 0 ? (s.candidate === current.chosenCandidate ? 'chosen' : 'rejected') : undefined }))}
          />
        </div>
      </div>
      <CandidatePanel job={current} />
      <div className="min-h-64 flex-1">
        <LogView events={events} jobId={current.id} queued={current.status === 'QUEUED'} />
      </div>
      {current.result && (
        <div className="rounded-lg border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
          <div className="flex items-center gap-2 border-b border-slate-200 px-3 py-2 text-sm font-medium dark:border-slate-800">최종 결과<span className="text-xs font-normal text-slate-500">마지막 단계(검증자) 출력</span></div>
          <pre className="mono max-h-[28rem] overflow-auto whitespace-pre-wrap p-3 text-xs leading-5">{current.result}</pre>
        </div>
      )}
    </div>
  )
}
