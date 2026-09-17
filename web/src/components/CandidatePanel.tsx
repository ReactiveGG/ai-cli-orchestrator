import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Check, FileDiff, Upload } from 'lucide-react'
import { api } from '../lib/api'
import type { Job } from '../lib/types'

/**
 * Competition mode results: one row per candidate coder, the verifier's
 * decision, and manual override ("이 후보 적용") once the job has finished.
 */
export function CandidatePanel({ job }: { job: Job }) {
  const [openPatch, setOpenPatch] = useState<number | null>(null)
  const [patches, setPatches] = useState<Record<number, string>>({})
  const [error, setError] = useState<string | null>(null)
  const terminal = job.status !== 'RUNNING' && job.status !== 'QUEUED'

  const apply = useMutation({
    mutationFn: (index: number) => api.applyCandidate(job.id, index),
    onSuccess: () => setError(null),
    onError: (e: Error) => setError(e.message),
  })

  const togglePatch = async (index: number) => {
    if (openPatch === index) { setOpenPatch(null); return }
    if (!(index in patches)) {
      try { setPatches((p) => ({ ...p, [index]: '' })); const text = await api.candidatePatch(job.id, index); setPatches((p) => ({ ...p, [index]: text })) } catch (e) { setError((e as Error).message) }
    }
    setOpenPatch(index)
  }

  if (!job.candidates.length) return null

  return (
    <div className="rounded-lg border border-violet-200 bg-white p-3 dark:border-violet-900 dark:bg-slate-900">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-semibold">후보 {job.candidates.length}개 (경쟁 모드)</span>
        {job.decisionNote && (
          <span className={`rounded-full px-2 py-0.5 text-xs ${job.applied ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-200' : 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200'}`}>{job.decisionNote}</span>
        )}
        {error && <span className="text-xs text-rose-600">{error}</span>}
      </div>
      <table className="mt-2 w-full text-sm">
        <thead className="text-left text-xs text-slate-500">
          <tr><th className="py-1 pr-2">후보</th><th className="py-1 pr-2">코더</th><th className="py-1 pr-2">변경</th><th className="py-1 pr-2">상태</th><th className="py-1 text-right">동작</th></tr>
        </thead>
        <tbody>
          {job.candidates.map((c) => (
            <>
              <tr key={c.index} className={`border-t border-slate-100 dark:border-slate-800 ${c.chosen ? 'bg-emerald-50/60 dark:bg-emerald-950/30' : ''}`}>
                <td className="py-1.5 pr-2 font-medium">후보 {c.index}</td>
                <td className="mono py-1.5 pr-2 text-xs">{c.agent}</td>
                <td className="py-1.5 pr-2 text-xs tabular-nums">{c.empty ? <span className="text-slate-400">변경 없음</span> : <>{c.filesChanged} files <span className="text-emerald-600">+{c.insertions}</span> <span className="text-rose-600">-{c.deletions}</span></>}</td>
                <td className="py-1.5 pr-2 text-xs">
                  {c.applied ? <span className="inline-flex items-center gap-1 text-emerald-700 dark:text-emerald-300"><Check size={12} /> 적용됨</span> : c.chosen ? <span className="text-amber-700 dark:text-amber-300">채택 (미적용)</span> : <span className="text-slate-400">–</span>}
                </td>
                <td className="py-1.5 text-right">
                  <button onClick={() => togglePatch(c.index)} disabled={c.empty} className="mr-1 inline-flex items-center gap-1 rounded border border-slate-300 px-2 py-0.5 text-xs disabled:opacity-40 dark:border-slate-700"><FileDiff size={12} /> {openPatch === c.index ? '닫기' : 'patch'}</button>
                  <button
                    onClick={() => { if (window.confirm(`후보 ${c.index}의 변경을 작업 공간에 적용할까요?${job.applied && !c.applied ? ' 이미 적용된 후보 위에 덮어씁니다.' : ''}`)) apply.mutate(c.index) }}
                    disabled={!terminal || c.empty || c.applied || apply.isPending}
                    className="inline-flex items-center gap-1 rounded border border-sky-300 px-2 py-0.5 text-xs text-sky-700 hover:bg-sky-50 disabled:opacity-40 dark:border-sky-800 dark:text-sky-300"
                  ><Upload size={12} /> 이 후보 적용</button>
                </td>
              </tr>
              {openPatch === c.index && (
                <tr key={`${c.index}-patch`}><td colSpan={5} className="pb-2">
                  <pre className="mono max-h-72 overflow-auto rounded-md border border-slate-200 bg-slate-50 p-2 text-[11px] leading-4 dark:border-slate-800 dark:bg-slate-950">{patches[c.index] === '' ? '불러오는 중…' : patches[c.index]?.split('\n').map((l, i) => <div key={i} className={l.startsWith('+') && !l.startsWith('+++') ? 'text-emerald-700 dark:text-emerald-300' : l.startsWith('-') && !l.startsWith('---') ? 'text-rose-700 dark:text-rose-300' : l.startsWith('@@') ? 'text-violet-600' : ''}>{l}</div>)}</pre>
                </td></tr>
              )}
            </>
          ))}
        </tbody>
      </table>
      {!terminal && <div className="mt-1 text-xs text-slate-500">작업이 끝나면 후보를 직접 적용할 수 있습니다.</div>}
    </div>
  )
}
