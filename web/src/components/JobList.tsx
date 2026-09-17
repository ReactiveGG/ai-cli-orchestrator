import type { Job } from '../lib/types'
import { StatusBadge } from './StatusBadge'
import { formatDuration, formatTokens, progress } from '../lib/format'
import { useNow } from '../lib/useNow'

export function JobList({ jobs, selectedId, onSelect, onCancel, onDelete }: {
  jobs: Job[]
  selectedId: string | null
  onSelect: (id: string) => void
  onCancel: (id: string) => void
  onDelete: (id: string) => void
}) {
  const now = useNow()
  if (!jobs.length) {
    return <div className="rounded-lg border border-dashed border-slate-300 p-6 text-center text-sm text-slate-400 dark:border-slate-700">아직 작업이 없습니다. Ctrl+K로 명령을 실행하세요.</div>
  }
  return (
    <ul className="divide-y divide-slate-200 rounded-lg border border-slate-200 bg-white dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-900">
      {jobs.map((job) => {
        const pct = progress(job)
        const active = job.status === 'RUNNING' || job.status === 'QUEUED'
        const idleMs = job.status === 'RUNNING' && job.lastOutputAt ? now - new Date(job.lastOutputAt).getTime() : 0
        return (
          <li
            key={job.id}
            onClick={() => onSelect(job.id)}
            className={`cursor-pointer px-3 py-2 hover:bg-slate-50 dark:hover:bg-slate-800/60 ${selectedId === job.id ? 'bg-sky-50 dark:bg-slate-800' : ''}`}
          >
            <div className="flex items-center gap-2">
              <StatusBadge status={job.status} />
              <span className="mono truncate text-sm">{job.command}</span>
              <span className="ml-auto shrink-0 text-xs text-slate-500 tabular-nums">
                {formatTokens(job.usage.inputTokens + job.usage.outputTokens)} tok · {formatDuration(job.startedAt, job.finishedAt, now)}
              </span>
              {active ? (
                <button onClick={(e) => { e.stopPropagation(); onCancel(job.id) }} className="shrink-0 rounded border border-rose-300 px-2 py-0.5 text-xs text-rose-700 hover:bg-rose-50 dark:border-rose-800 dark:text-rose-300">취소</button>
              ) : (
                <button onClick={(e) => { e.stopPropagation(); onDelete(job.id) }} className="shrink-0 rounded border border-slate-300 px-2 py-0.5 text-xs text-slate-500 hover:bg-slate-100 dark:border-slate-700">삭제</button>
              )}
            </div>
            <div className="mt-1.5 flex items-center gap-2">
              <div className="h-1.5 flex-1 overflow-hidden rounded bg-slate-200 dark:bg-slate-700">
                <div className={`h-full ${job.status === 'FAILED' || job.status === 'TIMEOUT' ? 'bg-rose-500' : 'bg-sky-500'}`} style={{ width: `${pct}%` }} />
              </div>
              <span className="w-8 text-right text-xs text-slate-500 tabular-nums">{pct}%</span>
              {idleMs > 30_000 && <span className="text-xs text-amber-600">출력 없음 {Math.floor(idleMs / 1000)}s</span>}
            </div>
          </li>
        )
      })}
    </ul>
  )
}
