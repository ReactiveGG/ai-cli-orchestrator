import type { Job } from '../lib/types'
import { JobList } from '../components/JobList'
import { JobDetail } from '../components/JobDetail'
import { Loading } from '../components/Feedback'

export function JobsPage({ jobs, loading = false, selectedId, onSelect, onCancel, onDelete }: {
  jobs: Job[]
  loading?: boolean
  selectedId: string | null
  onSelect: (id: string | null) => void
  onCancel: (id: string) => void
  onDelete: (id: string) => void
}) {
  return (
    <div className="grid min-h-0 flex-1 grid-cols-1 content-start gap-3 lg:grid-cols-[minmax(320px,2fr)_minmax(0,3fr)] lg:content-stretch">
      <div className="min-w-0 lg:min-h-0 lg:overflow-auto">
        {loading && jobs.length === 0
          ? <Loading label="작업 목록 불러오는 중…" className="rounded-lg border border-dashed border-slate-300 dark:border-slate-700" />
          : <JobList jobs={jobs} selectedId={selectedId} onSelect={onSelect} onCancel={onCancel} onDelete={onDelete} />}
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
