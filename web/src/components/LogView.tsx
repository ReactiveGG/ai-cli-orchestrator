import { useEffect, useRef, useState } from 'react'
import type { JobEvent, LogLevel } from '../lib/types'
import { formatTime } from '../lib/format'
import { withToken } from '../lib/api'

/** Two tabs over one event stream: 요약 (SUMMARY) and 상세 (DETAIL). */
export function LogView({ events, jobId }: { events: JobEvent[]; jobId: string }) {
  const [level, setLevel] = useState<LogLevel>('SUMMARY')
  const [follow, setFollow] = useState(true)
  const [filter, setFilter] = useState('')
  const bottom = useRef<HTMLDivElement>(null)

  const visible = events.filter((e) => e.level === level && (!filter || e.message.toLowerCase().includes(filter.toLowerCase())))

  useEffect(() => {
    if (follow) bottom.current?.scrollIntoView({ block: 'end' })
  }, [visible.length, follow, level])

  const counts = {
    SUMMARY: events.filter((e) => e.level === 'SUMMARY').length,
    DETAIL: events.filter((e) => e.level === 'DETAIL').length,
  }

  return (
    <div className="flex h-full flex-col rounded-lg border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      <div className="flex flex-wrap items-center gap-2 border-b border-slate-200 px-3 py-2 dark:border-slate-800">
        {(['SUMMARY', 'DETAIL'] as LogLevel[]).map((l) => (
          <button
            key={l}
            onClick={() => setLevel(l)}
            className={`rounded-md px-3 py-1 text-sm ${level === l ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'}`}
          >
            {l === 'SUMMARY' ? '요약 로그' : '상세 로그'} <span className="opacity-60">{counts[l]}</span>
          </button>
        ))}
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="필터"
          className="ml-auto w-40 rounded-md border border-slate-200 bg-transparent px-2 py-1 text-sm dark:border-slate-700"
        />
        <label className="flex items-center gap-1 text-xs text-slate-500">
          <input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} /> 따라가기
        </label>
        <a
          href={withToken(`/api/jobs/${jobId}/logs/file?level=${level}`)}
          target="_blank"
          rel="noreferrer"
          className="text-xs text-sky-600 hover:underline"
        >
          파일로 열기
        </a>
      </div>
      <div className="mono min-h-0 flex-1 overflow-auto p-3 text-xs leading-5">
        {visible.length === 0 && <div className="text-slate-400">로그가 없습니다</div>}
        {visible.map((e) => (
          <div key={e.seq} className="log-line flex gap-2">
            <span className="shrink-0 text-slate-400">{formatTime(e.at)}</span>
            {e.stepId && <span className="shrink-0 text-violet-600 dark:text-violet-300">{e.stepId}</span>}
            <span className={e.message.startsWith('경고') || e.message.includes('실패') ? 'text-rose-600 dark:text-rose-300' : ''}>{e.message}</span>
          </div>
        ))}
        <div ref={bottom} />
      </div>
    </div>
  )
}
