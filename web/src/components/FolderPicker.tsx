import { useEffect, useState } from 'react'
import { ArrowUp, Check, Folder, GitBranch, X } from 'lucide-react'
import { api } from '../lib/api'
import type { DirListing } from '../lib/types'
import { errorMessage } from '../lib/errors'

/**
 * In-page folder picker over the server's disk (allowed workspace roots only).
 * Replaces the native dialog, which could open behind the browser and look "stuck".
 */
export function FolderPicker({ initial, onPick, onClose }: { initial: string; onPick: (path: string) => void; onClose: () => void }) {
  const [listing, setListing] = useState<DirListing | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const go = async (path?: string) => {
    setBusy(true)
    try { const l = await api.listDirs(path); setListing(l); setError(l.error) } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  useEffect(() => { void go(initial || undefined) }, [initial])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={onClose}>
      <div className="flex max-h-[80vh] w-full max-w-xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl dark:border-slate-700 dark:bg-slate-900" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 border-b border-slate-200 px-3 py-2 dark:border-slate-800">
          <Folder size={16} className="text-slate-500" />
          <span className="text-sm font-semibold">작업 공간 폴더 선택</span>
          <button onClick={onClose} className="ml-auto rounded p-1 text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800" aria-label="닫기"><X size={16} /></button>
        </div>
        <div className="flex flex-wrap items-center gap-1 border-b border-slate-200 px-3 py-2 text-xs dark:border-slate-800">
          <span className="text-slate-500">루트</span>
          {listing?.roots.map((r) => <button key={r.path} onClick={() => go(r.path)} className="mono rounded border border-slate-300 px-1.5 py-0.5 hover:bg-slate-100 dark:border-slate-700 dark:hover:bg-slate-800">{r.path}</button>)}
        </div>
        <div className="flex items-center gap-2 px-3 py-2">
          <button onClick={() => listing?.parent && go(listing.parent)} disabled={!listing?.parent || busy} title="상위 폴더" className="rounded border border-slate-300 p-1 text-slate-600 disabled:opacity-40 dark:border-slate-700 dark:text-slate-300"><ArrowUp size={14} /></button>
          <input value={listing?.path ?? ''} onChange={(e) => setListing((l) => l ? { ...l, path: e.target.value } : l)} onKeyDown={(e) => { if (e.key === 'Enter') go((e.target as HTMLInputElement).value) }} className="mono min-w-0 flex-1 rounded border border-slate-300 bg-transparent px-2 py-1 text-sm dark:border-slate-700" aria-label="현재 경로" />
        </div>
        <div className="min-h-40 flex-1 overflow-auto px-2 pb-2">
          {error && <div className="px-2 py-2 text-sm text-rose-600 dark:text-rose-300">{error}</div>}
          {!error && listing && listing.dirs.length === 0 && <div className="px-2 py-3 text-sm text-slate-400">하위 폴더가 없습니다</div>}
          <ul>
            {listing?.dirs.map((d) => (
              <li key={d.path}>
                <button onDoubleClick={() => go(d.path)} onClick={() => go(d.path)} className="flex w-full items-center gap-2 rounded px-2 py-1 text-left text-sm hover:bg-slate-100 dark:hover:bg-slate-800">
                  <Folder size={14} className="shrink-0 text-amber-500" />
                  <span className="truncate">{d.name}</span>
                  {d.gitRepo && <span className="ml-auto inline-flex shrink-0 items-center gap-1 text-[10px] text-slate-400"><GitBranch size={10} /> git</span>}
                </button>
              </li>
            ))}
          </ul>
        </div>
        <div className="flex items-center gap-2 border-t border-slate-200 px-3 py-2 text-xs dark:border-slate-800">
          <span className="min-w-0 flex-1 truncate text-slate-500">{listing?.path}{listing && listing.allowed && !listing.error ? '' : ''}</span>
          <button onClick={onClose} className="rounded-md border border-slate-300 px-3 py-1.5 text-sm dark:border-slate-700">취소</button>
          <button onClick={() => listing && listing.allowed && !listing.error && onPick(listing.path)} disabled={!listing || !listing.allowed || !!listing.error} className="inline-flex items-center gap-1 rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white disabled:opacity-40 dark:bg-slate-100 dark:text-slate-900"><Check size={14} /> 이 폴더 선택</button>
        </div>
      </div>
    </div>
  )
}
