import { useMemo, useState } from 'react'
import type { Job, JobStatus } from '../lib/types'
import { statusLabel } from '../lib/format'
import { JobList } from '../components/JobList'
import { JobDetail } from '../components/JobDetail'
import { Loading } from '../components/Feedback'

const PAGE = 30
const STATUSES: JobStatus[] = ['RUNNING', 'QUEUED', 'SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELLED']

export function JobsPage({ jobs, loading = false, filterable = false, selectedId, onSelect, onCancel, onDelete }: {
  jobs: Job[]
  loading?: boolean
  /** Search box, status filter and paging (the full history view; the dashboard shows a short list without them). */
  filterable?: boolean
  selectedId: string | null
  onSelect: (id: string | null) => void
  onCancel: (id: string) => void
  onDelete: (id: string) => void
}) {
  const [q, setQ] = useState('')
  const [status, setStatus] = useState<JobStatus | ''>('')
  const [limit, setLimit] = useState(PAGE)
  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return jobs.filter((j) => (!status || j.status === status) && (!needle || j.command.toLowerCase().includes(needle) || j.id.includes(needle) || j.flow.toLowerCase().includes(needle) || j.flowLabel.toLowerCase().includes(needle)))
  }, [jobs, q, status])
  const shown = filterable ? filtered.slice(0, limit) : jobs
  return (
    <div className="grid min-h-0 flex-1 grid-cols-1 content-start gap-3 lg:grid-cols-[minmax(320px,2fr)_minmax(0,3fr)] lg:content-stretch">
      <div className="min-w-0 lg:min-h-0 lg:overflow-auto">
        {filterable && jobs.length > 0 && (
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <input value={q} onChange={(e) => { setQ(e.target.value); setLimit(PAGE) }} placeholder="명령·프리셋·id 검색" aria-label="작업 검색" className="min-w-0 flex-1 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900" />
            <select value={status} onChange={(e) => { setStatus(e.target.value as JobStatus | ''); setLimit(PAGE) }} aria-label="상태 필터" className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900">
              <option value="">모든 상태</option>
              {STATUSES.map((s) => <option key={s} value={s}>{statusLabel[s]}</option>)}
            </select>
            <span className="whitespace-nowrap text-xs text-slate-500">{filtered.length === jobs.length ? `${jobs.length}개` : `${filtered.length} / ${jobs.length}개`}</span>
          </div>
        )}
        {loading && jobs.length === 0
          ? <Loading label="작업 목록 불러오는 중…" className="rounded-lg border border-dashed border-slate-300 dark:border-slate-700" />
          : filterable && jobs.length > 0 && filtered.length === 0
            ? <div className="rounded-lg border border-dashed border-slate-300 p-6 text-center text-sm text-slate-400 dark:border-slate-700">검색 조건에 맞는 작업이 없습니다</div>
            : <JobList jobs={shown} selectedId={selectedId} onSelect={onSelect} onCancel={onCancel} onDelete={onDelete} />}
        {filterable && filtered.length > shown.length && (
          <button onClick={() => setLimit((l) => l + PAGE)} className="mt-2 w-full rounded-md border border-slate-300 py-1.5 text-sm text-slate-600 hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">
            더 보기 ({filtered.length - shown.length}개 남음)
          </button>
        )}
      </div>
      <div className="min-w-0 lg:min-h-[480px]">
        {selectedId ? (
          <JobDetail jobId={selectedId} onCancel={onCancel} onBack={() => onSelect(null)} />
        ) : (
          <div className="flex h-full min-h-40 items-center justify-center rounded-lg border border-dashed border-slate-300 p-6 text-center text-sm text-slate-400 lg:min-h-[480px] dark:border-slate-700">
            {jobs.length ? '작업을 선택하면 프로세스 흐름과 로그가 표시됩니다' : '명령을 실행하면 여기에 프로세스 흐름과 로그가 표시됩니다'}
          </div>
        )}
      </div>
    </div>
  )
}
