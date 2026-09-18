import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FolderOpen, Save } from 'lucide-react'
import { FolderPicker } from './FolderPicker'
import { api } from '../lib/api'
import type { Settings } from '../lib/types'
import { ErrorBox, Loading, Message } from './Feedback'
import { errorMessage } from '../lib/errors'

/**
 * Runtime settings editor. Saved values go to <data-dir>/settings.yml and are
 * applied at once: new jobs use the new workspace/timeouts/modules, and the
 * queue picks up the new concurrency immediately.
 */
export function SettingsPanel() {
  const qc = useQueryClient()
  const query = useQuery({ queryKey: ['settings'], queryFn: api.settings })
  const [draft, setDraft] = useState<Settings | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => { if (query.data && !draft) setDraft(structuredClone(query.data)) }, [query.data, draft])
  const [picking, setPicking] = useState(false)

  const save = useMutation({
    mutationFn: () => api.saveSettings(draft!),
    onSuccess: (d) => { setDraft(structuredClone(d)); setMessage('저장했습니다. 새 작업부터 적용됩니다.'); qc.invalidateQueries({ queryKey: ['settings'] }); qc.invalidateQueries({ queryKey: ['catalog'] }); qc.invalidateQueries({ queryKey: ['dashboard'] }) },
    onError: (e: Error) => setMessage(`저장 실패: ${errorMessage(e)}`),
  })

  if (!draft) {
    if (query.isError) return <ErrorBox title="서버 설정을 불러오지 못했습니다" message={errorMessage(query.error)} onRetry={() => query.refetch()} />
    return <Loading label="서버 설정 불러오는 중…" />
  }
  const d = draft
  const set = (patch: Partial<Settings>) => setDraft({ ...d, ...patch })
  const setModule = (name: string, patch: Partial<Settings['modules'][string]>) => setDraft({ ...d, modules: { ...d.modules, [name]: { ...d.modules[name], ...patch } } })
  const setIso = (patch: Partial<Settings['isolation']>) => setDraft({ ...d, isolation: { ...d.isolation, ...patch } })
  const lines = (v: string[]) => v.join('\n')
  const fromLines = (v: string) => v.split('\n').map((l) => l.trim()).filter(Boolean)
  const dirty = JSON.stringify(draft) !== JSON.stringify(query.data)
  const input = 'w-full rounded border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900'
  const label = 'flex flex-col gap-1 text-xs text-slate-500'

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div className="text-sm font-semibold">서버 설정</div>
        <span className="min-w-0 truncate text-xs text-slate-500" title={d.settingsFile}>저장하면 즉시 반영 · <span className="mono">{d.settingsFile}</span></span>
        <button onClick={() => save.mutate()} disabled={!dirty || save.isPending} className="ml-auto inline-flex items-center gap-1 rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white disabled:opacity-40 dark:bg-slate-100 dark:text-slate-900"><Save size={14} /> {save.isPending ? '저장 중…' : '설정 저장'}</button>
        {dirty && <button onClick={() => setDraft(structuredClone(query.data!))} className="rounded-md border border-slate-300 px-2 py-1.5 text-xs dark:border-slate-700">되돌리기</button>}
        {message && <span className="w-full"><Message text={message} /></span>}
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-[minmax(0,2fr)_repeat(3,minmax(0,1fr))]">
        <label className={label}>작업 공간 (AI가 읽고 수정하는 프로젝트 경로)
          <span className="flex gap-1">
            <input value={d.workspace} onChange={(e) => set({ workspace: e.target.value })} className={`${input} mono min-w-0 flex-1`} />
            <button type="button" onClick={() => setPicking(true)} title="서버 PC의 폴더를 화면에서 고릅니다" className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded border border-slate-300 px-2 py-1 text-sm text-slate-700 hover:bg-slate-100 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800">
              <FolderOpen size={14} /> 찾아보기
            </button>
            {picking && <FolderPicker initial={d.workspace} onPick={(path) => { set({ workspace: path }); setPicking(false); setMessage(`폴더를 선택했습니다: ${path} — "설정 저장"을 눌러야 적용됩니다.`) }} onClose={() => setPicking(false)} />}
          </span>
        </label>
        <label className={label}>동시 실행 수
          <input type="number" min={1} max={16} value={d.concurrency} onChange={(e) => set({ concurrency: Number(e.target.value) })} className={input} />
        </label>
        <label className={label}>모듈 타임아웃 (초)
          <input type="number" min={30} step={30} value={d.moduleTimeoutSeconds} onChange={(e) => set({ moduleTimeoutSeconds: Number(e.target.value) })} className={input} />
        </label>
        <label className={label}>유휴 경고 (초)
          <input type="number" min={5} step={5} value={d.idleWarningSeconds} onChange={(e) => set({ idleWarningSeconds: Number(e.target.value) })} className={input} />
        </label>
      </div>

      <div className="mt-4 grid gap-4 md:grid-cols-2">
        {Object.entries(d.modules).map(([name, m]) => (
          <div key={name} className="rounded-lg border border-slate-200 p-3 dark:border-slate-800">
            <div className="mb-2 flex items-center gap-2"><span className="mono text-sm font-semibold">{name}</span><span className="text-xs text-slate-500">CLI 모듈</span></div>
            <div className="grid grid-cols-2 gap-2">
              <label className={label}>모드
                <select value={m.mode} onChange={(e) => setModule(name, { mode: e.target.value as Settings['modules'][string]['mode'] })} className={input}>
                  <option value="AUTO">AUTO (설치돼 있으면 CLI)</option><option value="CLI">CLI</option><option value="STUB">STUB (호출 안 함)</option>
                </select>
              </label>
              <label className={label}>실행 파일 (이름 또는 절대 경로. 자동으로 못 찾을 때 <span className="mono">where claude</span> 결과를 붙여 넣기)
                <input id={`module-${name}-command`} value={m.command} onChange={(e) => setModule(name, { command: e.target.value })} placeholder={name} className={`${input} mono`} />
              </label>
              <label className={label}>기본 모델 (칩에 지정 없을 때)
                <input value={m.model ?? ''} onChange={(e) => setModule(name, { model: e.target.value || null })} placeholder="CLI 기본값" className={`${input} mono`} />
              </label>
              <label className={label}>에이전트당 예산 상한 ($)
                <input type="number" min={0} step={0.5} value={m.maxBudgetUsd ?? ''} onChange={(e) => setModule(name, { maxBudgetUsd: e.target.value === '' ? null : Number(e.target.value) })} placeholder="없음" className={input} />
              </label>
              <label className={`${label} col-span-2`}>허용 도구 (한 줄에 하나, 목록 밖 셸 명령은 거부됨)
                <textarea rows={4} value={lines(m.allowedTools)} onChange={(e) => setModule(name, { allowedTools: fromLines(e.target.value) })} className={`${input} mono`} />
              </label>
              <label className={`${label} col-span-2`}>추가 인자 (한 줄에 하나, 예: --max-turns / 30)
                <textarea rows={2} value={lines(m.extraArgs)} onChange={(e) => setModule(name, { extraArgs: fromLines(e.target.value) })} className={`${input} mono`} />
              </label>
            </div>
          </div>
        ))}
      </div>

      <div className="mt-4 rounded-lg border border-violet-200 p-3 dark:border-violet-900">
        <div className="mb-2 flex items-center gap-2"><span className="text-sm font-semibold">경쟁 모드 격리</span><span className="text-xs text-slate-500">코더 2개 이상일 때 git worktree로 분리</span></div>
        <div className="flex flex-wrap items-center gap-4 text-sm">
          <label className="flex items-center gap-1.5"><input type="checkbox" checked={d.isolation.enabled} onChange={(e) => setIso({ enabled: e.target.checked })} /> 격리 사용</label>
          <label className="flex items-center gap-1.5"><input type="checkbox" checked={d.isolation.autoApply} onChange={(e) => setIso({ autoApply: e.target.checked })} /> 채택 후보 자동 적용</label>
          <label className="flex items-center gap-1.5"><input type="checkbox" checked={d.isolation.keepWorktrees} onChange={(e) => setIso({ keepWorktrees: e.target.checked })} /> worktree 보존 (디버깅)</label>
          <label className="flex items-center gap-1.5 text-xs text-slate-500">프롬프트 diff 최대 글자
            <input type="number" min={1000} step={5000} value={d.isolation.maxPatchChars} onChange={(e) => setIso({ maxPatchChars: Number(e.target.value) })} className={`${input} w-28`} />
          </label>
        </div>
        <div className="mt-2 grid gap-2 md:grid-cols-2">
          <label className={label}>링크할 ignore 디렉터리 (한 줄에 하나)
            <textarea rows={2} value={lines(d.isolation.linkDirs)} onChange={(e) => setIso({ linkDirs: fromLines(e.target.value) })} className={`${input} mono`} />
          </label>
          <label className={label}>후보에서 제외할 패턴 (한 줄에 하나)
            <textarea rows={2} value={lines(d.isolation.exclude)} onChange={(e) => setIso({ exclude: fromLines(e.target.value) })} className={`${input} mono`} />
          </label>
        </div>
      </div>
    </div>
  )
}
