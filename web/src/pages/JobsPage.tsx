import type { Job } from '../lib/types'
import { JobList } from '../components/JobList'
import { JobDetail } from '../components/JobDetail'

export function JobsPage({ jobs, selectedId, onSelect, onCancel, onDelete }: {
  jobs: Job[]
  selectedId: string | null
  onSelect: (id: string | null) => void
  onCancel: (id: string) => void
  onDelete: (id: string) => void
}) {
  return (
    <div className="grid min-h-0 flex-1 gap-3 lg:grid-cols-[minmax(320px,2fr)_3fr]">
      <div className="min-h-0 overflow-auto">
        <JobList jobs={jobs} selectedId={selectedId} onSelect={onSelect} onCancel={onCancel} onDelete={onDelete} />
      </div>
      <div className="min-h-[480px]">
        {selectedId ? (
          <JobDetail jobId={selectedId} onCancel={onCancel} />
        ) : (
          <div className="flex h-full items-center justify-center rounded-lg border border-dashed border-slate-300 text-sm text-slate-400 dark:border-slate-700">
            작업을 선택하면 프로세스 흐름과 로그가 표시됩니다
          </div>
        )}
      </div>
    </div>
  )
}
