import type { JobStatus } from '../lib/types'
import { statusLabel, statusTone } from '../lib/format'

export function StatusBadge({ status, className = '' }: { status: JobStatus; className?: string }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${statusTone[status]} ${className}`}>
      {status === 'RUNNING' && <span className="mr-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-current" />}
      {statusLabel[status]}
    </span>
  )
}
