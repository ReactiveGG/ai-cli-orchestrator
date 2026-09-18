import { useMemo, useState, useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DndContext, DragOverlay, PointerSensor, useSensor, useSensors, useDraggable, useDroppable, pointerWithin, rectIntersection, type CollisionDetection, type DragEndEvent, type DragStartEvent } from '@dnd-kit/core'
import { Copy, GripVertical, Plus, Save, Trash2, X } from 'lucide-react'
import { api } from '../lib/api'
import type { AgentDto, FlowConfig, FlowDto, StageDto } from '../lib/types'
import { FlowDiagram, type FlowStep } from '../components/FlowDiagram'
import { agent, buildPlan, signatureOf } from '../lib/plan'
import { SettingsPanel } from '../components/SettingsPanel'
import { ErrorBox, Loading, Message } from '../components/Feedback'
import { errorMessage } from '../lib/errors'

/** The pipeline is fixed; a preset only decides how many models run each stage. */
const PIPELINE = ['planner', 'coder', 'reviewer', 'verifier'] as const

/** Model aliases the Claude CLI accepts for --model, and the --effort levels. */
const MODEL_CHOICES: Record<string, string[]> = { claude: ['fable', 'opus', 'sonnet', 'haiku'], codex: ['gpt-5-codex', 'o3'] }
const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max']

/**
 * Preset editor. Every preset runs planner → coder → reviewer → verifier; drag
 * model blocks into a stage column to run more agents there in parallel
 * (1-1-2-1 = two reviewers cross-check, 1-3-3-1 = three implementations, three
 * reviews, the verifier picks one). The same model may be dropped several times.
 */
/** Drop only where the pointer actually is; the closest-center fallback used to "drop" a barely-moved block into the nearest column. */
const dropWherePointerIs: CollisionDetection = (args) => {
  const within = pointerWithin(args)
  return within.length ? within : rectIntersection(args)
}

export function ConfigPage() {
  const qc = useQueryClient()
  const routing = useQuery({ queryKey: ['routing'], queryFn: api.routing })
  const catalog = useQuery({ queryKey: ['catalog'], queryFn: api.catalog })
  const settings = useQuery({ queryKey: ['settings'], queryFn: api.settings })

  const [cfg, setCfg] = useState<FlowConfig | null>(null)
  const [selected, setSelected] = useState('')
  const [openRole, setOpenRole] = useState<string | null>(null)
  const [newPreset, setNewPreset] = useState('')
  const [dragging, setDragging] = useState<string | null>(null)
  const [yaml, setYaml] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    if (routing.data && !cfg) { setCfg(normalize(routing.data)); setSelected(Object.keys(routing.data.flows)[0] ?? '') }
  }, [routing.data, cfg])

  const modules = catalog.data?.modules.map((m) => m.name) ?? ['claude', 'codex']
  const preset = cfg?.flows[selected]

  const adopt = (data: FlowConfig, msg: string) => {
    const n = normalize(data); setCfg(n)
    if (!n.flows[selected]) setSelected(Object.keys(n.flows)[0] ?? '')
    setMessage(msg); setYaml(null)
    qc.invalidateQueries({ queryKey: ['catalog'] }); qc.invalidateQueries({ queryKey: ['routing'] })
  }
  const save = useMutation({ mutationFn: () => api.saveRouting(cfg!), onSuccess: (d) => adopt(d, '저장했습니다. 새 작업부터 적용됩니다.'), onError: (e: Error) => setMessage(`저장 실패: ${errorMessage(e)}`) })
  const reset = useMutation({ mutationFn: () => api.resetRouting(), onSuccess: (d) => adopt(d, '기본 프리셋 3개로 초기화했습니다.'), onError: (e: Error) => setMessage(`초기화 실패: ${errorMessage(e)}`) })
  const showYaml = async () => { if (yaml) { setYaml(null); return } try { setYaml(await api.routingYaml()) } catch (e) { setMessage(`YAML을 불러오지 못했습니다: ${errorMessage(e)}`) } }
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }))   // a click is not a drag

  const updatePreset = (fn: (p: FlowDto) => FlowDto) => setCfg((c) => c && ({ ...c, flows: { ...c.flows, [selected]: fn(structuredClone(c.flows[selected])) } }))
  const setModels = (si: number, fn: (models: AgentDto[]) => AgentDto[]) => updatePreset((p) => { p.stages[si].models = fn(p.stages[si].models.map((a) => ({ ...a }))); return p })

  const onDragEnd = (e: DragEndEvent) => {
    setDragging(null)
    const activeId = String(e.active.id); const overId = e.over ? String(e.over.id) : null
    if (!overId || !overId.startsWith('stage:')) return
    const target = Number(overId.slice(6))
    if (activeId.startsWith('palette:')) { const m = activeId.slice(8); setModels(target, (ms) => [...ms, agent(m)]); return }
    if (activeId.startsWith('model:')) {
      const [, s, i] = activeId.split(':'); const from = Number(s); const idx = Number(i)
      if (from === target) return
      updatePreset((p) => { const [m] = p.stages[from].models.splice(idx, 1); p.stages[target].models.push(m); return p })
    }
  }

  const plan = useMemo<FlowStep[]>(() => (preset && cfg ? buildPlan(preset, cfg) : []), [preset, cfg])
  const dirty = useMemo(() => !!cfg && !!routing.data && JSON.stringify(cfg) !== JSON.stringify(normalize(routing.data)), [cfg, routing.data])
  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  if (!cfg) {
    if (routing.isError) return <ErrorBox title="프리셋 구성을 불러오지 못했습니다" message={errorMessage(routing.error)} onRetry={() => routing.refetch()} />
    return <Loading label="프리셋 구성 불러오는 중…" />
  }

  // The typed name is the label (Korean is fine); the id is an ASCII slug for YAML keys, URLs and `--preset`.
  const addPreset = () => {
    const labelText = newPreset.trim()
    if (!labelText) { setMessage('프리셋 이름을 입력하세요.'); return }
    let name = labelText.toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '')
    if (!name) name = `preset-${Object.keys(cfg.flows).length + 1}`
    let unique = name; let k = 2
    while (cfg.flows[unique]) unique = `${name}-${k++}`
    const base = cfg.flows[selected] ?? emptyPreset(modules[0] ?? 'claude')
    setCfg({ ...cfg, flows: { ...cfg.flows, [unique]: { ...structuredClone(base), label: labelText } } }); setSelected(unique); setNewPreset('')
    setMessage(`'${labelText}' 프리셋을 추가했습니다 (id: ${unique}). 단계를 고친 뒤 저장하세요.`)
  }
  const duplicate = () => { let n = `${selected}-copy`; let k = 2; while (cfg.flows[n]) n = `${selected}-copy${k++}`; setCfg({ ...cfg, flows: { ...cfg.flows, [n]: { ...structuredClone(cfg.flows[selected]), label: `${cfg.flows[selected].label ?? selected} (복사)` } } }); setSelected(n) }
  const remove = () => { if (Object.keys(cfg.flows).length <= 1) { setMessage('프리셋은 최소 1개 필요합니다.'); return } const f = { ...cfg.flows }; delete f[selected]; setCfg({ ...cfg, flows: f }); setSelected(Object.keys(f)[0]) }

  return (
    <DndContext sensors={sensors} collisionDetection={dropWherePointerIs} onDragStart={(e: DragStartEvent) => setDragging(String(e.active.id))} onDragEnd={onDragEnd}>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[240px_minmax(0,1fr)]">
        <aside className="min-w-0 space-y-4">
          <div className="rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-1 text-sm font-semibold">프리셋</div>
            <div className="mb-2 text-xs text-slate-500">플래너-코더-리뷰어-검증자 각 단계의 에이전트 수</div>
            <div className="space-y-1">
              {Object.entries(cfg.flows).map(([name, f]) => (
                <button key={name} onClick={() => setSelected(name)} className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm ${selected === name ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'hover:bg-slate-100 dark:hover:bg-slate-800'}`}>
                  <span className="mono w-14 shrink-0 text-xs">{signatureOf(f)}</span><span className="truncate">{f.label ?? name}</span>
                </button>
              ))}
            </div>
            <div className="mt-2 flex gap-1">
              <input value={newPreset} onChange={(e) => setNewPreset(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && !e.nativeEvent.isComposing && addPreset()} placeholder="새 프리셋 이름 (한글 가능)" className="min-w-0 flex-1 rounded border border-slate-300 bg-transparent px-2 py-1 text-xs dark:border-slate-700" />
              <button onClick={addPreset} className="rounded border border-slate-300 px-2 text-xs dark:border-slate-700" title="현재 프리셋을 복사해 추가"><Plus size={14} /></button>
            </div>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-slate-900">
            <div className="text-sm font-semibold">모델 블록</div>
            <div className="mb-2 text-xs text-slate-500">단계 칸에 놓을 때마다 에이전트가 하나 늘어납니다. 같은 모델을 여러 번 놓아도 됩니다.</div>
            <div className="space-y-1.5">
              {modules.map((m) => <PaletteBlock key={m} id={`palette:${m}`} label={m} sub={catalog.data?.modules.find((x) => x.name === m)?.available ? '사용 가능' : '설치 안 됨 → 스텁'} />)}
            </div>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white p-3 text-xs text-slate-500 dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-1 font-medium text-slate-700 dark:text-slate-200">파일</div>
            <div>프리셋 <span className="mono break-all">{settings.data?.routingFile}</span></div>
            <div className="mt-1">설정 <span className="mono break-all">{settings.data?.settingsFile}</span></div>
            <div className="mt-2">작업 공간 <span className="mono break-all">{settings.data?.workspace}</span></div>
            <a href="#config-settings" className="mt-2 block text-sky-600 hover:underline">서버 설정 편집 ↓</a>
          </div>
        </aside>

        <section className="min-w-0 space-y-4">
          {preset && (
            <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
              <div className="flex flex-wrap items-center gap-3">
                <input value={preset.label ?? ''} onChange={(e) => updatePreset((p) => ({ ...p, label: e.target.value }))} className="min-w-40 rounded border border-slate-300 bg-transparent px-2 py-1 text-base font-semibold dark:border-slate-700" />
                <span className="mono rounded bg-violet-100 px-2 py-0.5 text-sm text-violet-800 dark:bg-violet-900 dark:text-violet-200">{signatureOf(preset)}</span>
                <span className="mono text-xs text-slate-500">{selected}</span>
                <label className="flex items-center gap-1 text-xs text-slate-500">빈 단계의 기본 모델
                  <select value={preset.defaultModule ?? ''} onChange={(e) => updatePreset((p) => ({ ...p, defaultModule: e.target.value }))} className="rounded border border-slate-300 bg-transparent px-1 py-0.5 dark:border-slate-700">
                    {modules.map((m) => <option key={m} value={m}>{m}</option>)}
                  </select>
                </label>
                <div className="ml-auto flex gap-1">
                  {dirty && <span className="self-center rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-800 dark:bg-amber-900 dark:text-amber-200">저장 안 됨</span>}
                  <button onClick={() => save.mutate()} disabled={save.isPending || !dirty} className="flex items-center gap-1 rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-700 disabled:opacity-40 dark:bg-slate-100 dark:text-slate-900"><Save size={14} /> {save.isPending ? '저장 중…' : '프리셋 저장'}</button>
                  <button onClick={duplicate} title="복제" className="rounded-md border border-slate-300 px-2 py-1.5 text-sm dark:border-slate-700"><Copy size={14} /></button>
                  <button onClick={remove} title="삭제" className="rounded-md border border-slate-300 px-2 py-1.5 text-sm text-rose-600 dark:border-slate-700"><Trash2 size={14} /></button>
                </div>
              </div>

              <div className="mt-4 grid gap-2 md:grid-cols-4">
                {preset.stages.map((stage, si) => (
                  <StageColumn key={si} index={si} stage={stage} roleLabel={cfg.roles[stage.role]?.label ?? stage.role} roleHint={cfg.roles[stage.role]?.instructions.split('\n')[0] ?? ''} defaultModule={preset.defaultModule ?? modules[0]} modules={modules}
                    onAdd={(m) => setModels(si, (ms) => [...ms, agent(m)])} onRemove={(mi) => setModels(si, (ms) => ms.filter((_, k) => k !== mi))}
                    onChange={(mi, patch) => setModels(si, (ms) => { ms[mi] = { ...ms[mi], ...patch }; return ms })} last={si === preset.stages.length - 1} />
                ))}
              </div>
              <datalist id="slash-commands">
                {(catalog.data?.commands ?? []).map((c) => <option key={c.name} value={c.argument ? `${c.name} ` : c.name}>{c.description}</option>)}
              </datalist>
              <details className="mt-2 text-xs text-slate-500">
                <summary className="cursor-pointer">쓸 수 있는 슬래시 명령 {catalog.data?.commands.length ?? 0}개 (칩의 /명령 칸에 입력)</summary>
                <ul className="mt-1 space-y-0.5">
                  {(catalog.data?.commands ?? []).map((c) => (
                    <li key={c.name} className="flex flex-wrap gap-x-2"><span className="mono text-slate-700 dark:text-slate-200">{c.name}{c.argument ? ` ${c.argument}` : ''}</span><span className="rounded bg-slate-100 px-1 text-[10px] dark:bg-slate-800">{{ builtin: '내장', project: '프로젝트 명령', user: '내 명령', skill: '스킬' }[c.kind]}</span><span className="min-w-0">{c.description}</span></li>
                  ))}
                  {(catalog.data?.commands.length ?? 0) <= 3 && <li className="text-slate-400">작업 공간의 <span className="mono">.claude/commands/*.md</span>나 <span className="mono">.claude/skills/*/SKILL.md</span>를 만들면 여기에 나타납니다.</li>}
                </ul>
              </details>
              <p className="mt-2 text-xs text-slate-500">단계는 고정입니다. 칸에 모델을 더 놓을수록 그 단계가 병렬로 돌고, 다음 단계는 결과를 전부 받습니다. 칩마다 사용 모델(fable/opus/sonnet…)과 에포트, 슬래시 명령을 정할 수 있고, 비우면 CLI 기본값입니다. `/plan`은 대화형의 /plan처럼 계획 전용 모드로 돌고, 코더에 주면 계획(plan 모드) → 같은 세션 이어받아 구현(acceptEdits) 두 번으로 실행합니다. `/resume 세션id`·`/continue`는 그 세션을 이어받고(세션 id는 요약 로그에 남음), `/review` 같은 이름은 프롬프트 첫 줄에 들어가 작업 공간의 `.claude/commands`·스킬을 실행합니다.</p>

              <div className="mt-4">
                <div className="mb-1 text-xs text-slate-500">실행 흐름 미리보기</div>
                <FlowDiagram steps={plan} height={Math.max(220, Math.min(520, 130 + 90 * Math.max(1, ...preset.stages.map((s) => Math.max(1, s.models.length)))))} />
              </div>
            </div>
          )}

          <RolesEditor roles={cfg.roles} open={openRole} setOpen={setOpenRole} onChange={(roles) => setCfg({ ...cfg, roles })} />

          <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-2 text-sm font-semibold">시간 초과 폴백</div>
            <div className="flex flex-wrap gap-4 text-sm">
              {modules.map((m) => (
                <label key={m} className="flex items-center gap-2"><span className="mono">{m}</span> 시간 초과 시 →
                  <select value={cfg.fallback[m] ?? ''} onChange={(e) => { const f = { ...cfg.fallback }; if (e.target.value) f[m] = e.target.value; else delete f[m]; setCfg({ ...cfg, fallback: f }) }} className="rounded border border-slate-300 bg-transparent px-2 py-1 dark:border-slate-700">
                    <option value="">없음</option>{modules.filter((o) => o !== m).map((o) => <option key={o} value={o}>{o}</option>)}
                  </select>
                </label>
              ))}
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button onClick={() => save.mutate()} disabled={save.isPending || !dirty} className="rounded-md bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-50 dark:bg-slate-100 dark:text-slate-900">{save.isPending ? '저장 중…' : dirty ? '프리셋 저장 및 적용' : '저장됨'}</button>
            <button onClick={() => reset.mutate()} className="rounded-md border border-slate-300 px-3 py-2 text-sm dark:border-slate-700">기본 프리셋으로 (1-1-1-1 · 1-1-2-1 · 1-3-3-1)</button>
            <button onClick={showYaml} className="rounded-md border border-slate-300 px-3 py-2 text-sm dark:border-slate-700">{yaml ? 'YAML 닫기' : '저장된 YAML 보기'}</button>
            <Message text={message} />
          </div>
          {yaml && <pre className="mono max-w-full overflow-auto rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs dark:border-slate-800 dark:bg-slate-950">{yaml}</pre>}
          <div id="config-settings" />
          <SettingsPanel />
        </section>
      </div>
      <DragOverlay>{dragging && <div className="mono rounded-lg border border-sky-400 bg-white px-3 py-1.5 text-sm shadow-lg dark:bg-slate-800">{dragging.split(':').pop()}</div>}</DragOverlay>
    </DndContext>
  )
}

/** Guarantees every preset has exactly the four pipeline stages in order. */
function normalize(cfg: FlowConfig): FlowConfig {
  const flows: FlowConfig['flows'] = {}
  for (const [name, f] of Object.entries(cfg.flows)) {
    const byRole = new Map(f.stages.map((s) => [s.role, s]))
    flows[name] = { ...f, stages: PIPELINE.map((r) => byRole.get(r) ?? { name: null, role: r, models: [] }) }
  }
  return { ...cfg, flows }
}
function emptyPreset(module: string): FlowDto {
  return { label: '', task: 'custom', defaultModule: module, stages: PIPELINE.map((r) => ({ name: null, role: r, models: [] })) }
}

function PaletteBlock({ id, label, sub }: { id: string; label: string; sub: string }) {
  const { attributes, listeners, setNodeRef, transform } = useDraggable({ id })
  return (
    <div ref={setNodeRef} {...listeners} {...attributes} style={{ transform: transform ? `translate(${transform.x}px, ${transform.y}px)` : undefined }} className="flex cursor-grab items-center gap-2 rounded-lg border border-sky-300 bg-sky-50 px-2 py-1.5 text-sm active:cursor-grabbing dark:border-sky-800 dark:bg-sky-950">
      <GripVertical size={14} className="text-slate-400" /><span className="mono font-medium">{label}</span><span className="ml-auto text-xs text-slate-500">{sub}</span>
    </div>
  )
}

function StageColumn({ index, stage, roleLabel, roleHint, defaultModule, modules, onAdd, onRemove, onChange, last }: {
  index: number; stage: StageDto; roleLabel: string; roleHint: string; defaultModule: string; modules: string[]
  onAdd: (m: string) => void; onRemove: (mi: number) => void; onChange: (mi: number, patch: Partial<AgentDto>) => void; last: boolean
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `stage:${index}` })
  const n = Math.max(1, stage.models.length)
  return (
    <div className="relative rounded-lg border border-violet-200 bg-violet-50/40 p-2 dark:border-violet-900 dark:bg-violet-950/20">
      <div className="flex items-center gap-2">
        <span className="mono text-xs text-slate-400">{index + 1}</span>
        <span className="font-semibold text-violet-800 dark:text-violet-200">{roleLabel}</span>
        <span className={`ml-auto rounded-full px-2 text-xs ${n > 1 ? 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200' : 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'}`}>{n}{n > 1 ? ' 병렬' : ''}</span>
      </div>
      <div className="mb-2 truncate text-[11px] text-slate-500" title={roleHint}>{roleHint}</div>
      <div ref={setNodeRef} className={`flex min-h-24 flex-col gap-1.5 rounded-md border-2 border-dashed p-2 ${isOver ? 'border-sky-400 bg-sky-50/60 dark:bg-sky-950/30' : 'border-slate-300 dark:border-slate-700'}`}>
        {stage.models.map((a, mi) => <ModelChip key={`${index}:${mi}`} id={`model:${index}:${mi}`} agent={a} onRemove={() => onRemove(mi)} onChange={(patch) => onChange(mi, patch)} />)}
        {stage.models.length === 0 && <span className="text-xs text-slate-400">비어 있음 → 기본 모델 <span className="mono">{defaultModule}</span> 1개</span>}
        <div className="mt-auto flex flex-wrap gap-1 pt-1">
          {modules.map((m) => <button key={m} onClick={() => onAdd(m)} className="mono rounded border border-slate-300 px-1.5 text-[11px] text-slate-600 hover:bg-white dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">+ {m}</button>)}
        </div>
      </div>
      {!last && <span className="absolute -right-3 top-1/2 hidden -translate-y-1/2 text-slate-400 md:block">→</span>}
    </div>
  )
}

function ModelChip({ id, agent: a, onRemove, onChange }: { id: string; agent: AgentDto; onRemove: () => void; onChange: (patch: Partial<AgentDto>) => void }) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({ id })
  const choices = MODEL_CHOICES[a.module] ?? []
  const stop = (e: React.SyntheticEvent) => e.stopPropagation()
  return (
    <div ref={setNodeRef} style={{ transform: transform ? `translate(${transform.x}px, ${transform.y}px)` : undefined, opacity: isDragging ? 0.4 : 1 }} className="rounded-lg border border-sky-300 bg-sky-50 px-2 py-1 text-sm dark:border-sky-800 dark:bg-sky-950">
      <div className="flex items-center gap-1.5">
        <span {...listeners} {...attributes} className="cursor-grab text-slate-400 active:cursor-grabbing"><GripVertical size={14} /></span>
        <span className="mono font-medium">{a.module}</span>
        <button onClick={onRemove} className="ml-auto text-slate-400 hover:text-rose-600"><X size={14} /></button>
      </div>
      <div className="mt-1 flex gap-1" onPointerDown={stop}>
        <select value={a.model ?? ''} onChange={(e) => onChange({ model: e.target.value || null })} title="사용 모델 (--model)" className="min-w-0 flex-1 rounded border border-slate-300 bg-white px-1 text-[11px] dark:border-slate-700 dark:bg-slate-900">
          <option value="">모델: 기본</option>
          {choices.map((m) => <option key={m} value={m}>{m}</option>)}
          {a.model && !choices.includes(a.model) && <option value={a.model}>{a.model}</option>}
        </select>
        <select value={a.effort ?? ''} onChange={(e) => onChange({ effort: e.target.value || null })} title="에포트 (--effort)" className="min-w-0 flex-1 rounded border border-slate-300 bg-white px-1 text-[11px] dark:border-slate-700 dark:bg-slate-900">
          <option value="">에포트: 기본</option>
          {EFFORTS.map((e) => <option key={e} value={e}>{e}</option>)}
        </select>
      </div>
      <div className="mt-1" onPointerDown={stop}>
        <input list="slash-commands" value={a.command ?? ''} onChange={(e) => onChange({ command: e.target.value || null })} placeholder="/명령 (예: /plan, /review)" title="슬래시 명령: /plan = 계획 전용 모드(코더는 계획→이어서 구현 2회), /resume 세션id·/continue = 세션 이어받기, 그 밖의 /이름 = 프로젝트 명령·스킬 실행" className="mono w-full rounded border border-slate-300 bg-white px-1 text-[11px] dark:border-slate-700 dark:bg-slate-900" />
      </div>
    </div>
  )
}

function RolesEditor({ roles, open, setOpen, onChange }: { roles: FlowConfig['roles']; open: string | null; setOpen: (n: string | null) => void; onChange: (r: FlowConfig['roles']) => void }) {
  const update = (name: string, patch: Partial<FlowConfig['roles'][string]>) => onChange({ ...roles, [name]: { ...roles[name], ...patch } })
  const shown = PIPELINE.filter((r) => roles[r])
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="mb-2 flex items-center gap-2"><div className="text-sm font-semibold">단계별 역할 지시문</div><span className="text-xs text-slate-500">각 단계의 에이전트가 프롬프트 앞에 받는 지시. 모든 프리셋에 공통</span></div>
      <div className="divide-y divide-slate-200 dark:divide-slate-800">
        {shown.map((name) => { const r = roles[name]; return (
          <div key={name} className="py-2">
            <button onClick={() => setOpen(open === name ? null : name)} className="flex w-full items-center gap-2 text-left text-sm">
              <span className="mono w-20 shrink-0 text-violet-700 dark:text-violet-300">{name}</span><span className="shrink-0 whitespace-nowrap font-medium">{r.label}</span><span className="min-w-0 truncate text-xs text-slate-500">{r.instructions.split('\n')[0]}</span>
            </button>
            {open === name && (
              <div className="mt-2 grid gap-2">
                <label className="text-xs">표시 이름<input value={r.label} onChange={(e) => update(name, { label: e.target.value })} className="mt-1 w-full rounded border border-slate-300 bg-transparent px-2 py-1 text-sm dark:border-slate-700" /></label>
                <label className="text-xs">지시문<textarea value={r.instructions} onChange={(e) => update(name, { instructions: e.target.value })} rows={6} className="mono mt-1 w-full rounded border border-slate-300 bg-transparent px-2 py-1 text-xs dark:border-slate-700" /></label>
              </div>
            )}
          </div>) })}
      </div>
    </div>
  )
}
