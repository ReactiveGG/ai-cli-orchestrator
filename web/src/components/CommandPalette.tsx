import { useEffect, useMemo, useState } from 'react'
import { Command } from 'cmdk'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import type { Catalog, PlanStep } from '../lib/types'
import { FlowDiagram } from './FlowDiagram'

/**
 * Ctrl+K palette. Pick a preset (how many agents per pipeline stage), then type
 * the target: `"jwt refresh token flow" --focus security`. One line = one job;
 * Shift+Enter adds lines (several jobs). `--preset name` inline also works.
 */
export function CommandPalette({ open, initialPreset, onClose, onSubmitted }: {
  open: boolean
  initialPreset?: string
  onClose: () => void
  onSubmitted: (jobIds: string[]) => void
}) {
  const catalog = useQuery({ queryKey: ['catalog'], queryFn: api.catalog, enabled: open })
  const [text, setText] = useState('')
  const [preset, setPreset] = useState('default')
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<PlanStep[]>([])

  useEffect(() => {
    if (open) { setText(''); if (initialPreset) setPreset(initialPreset) }
    else setError(null)
  }, [open, initialPreset])

  const presets = catalog.data?.flows ?? []
  useEffect(() => {
    if (presets.length && !presets.some((p) => p.name === preset)) setPreset(presets.find((p) => p.name === 'default')?.name ?? presets[0].name)
  }, [presets, preset])
  const active = presets.find((p) => p.name === preset)

  const lines = useMemo(() => text.split('\n').map((l) => l.trim()).filter(Boolean), [text])

  const submit = useMutation({
    mutationFn: async () => {
      if (!lines.length) throw new Error('대상을 입력하세요')
      const jobs = await api.submitBatch(lines.map((line) => ({ commandLine: line, flow: /--preset[ =]|(^|\s)-p\s/.test(line) ? undefined : preset })))
      return jobs.map((j) => j.id)
    },
    onSuccess: (ids) => { setText(''); setError(null); onSubmitted(ids); onClose() },
    onError: (e: Error) => setError(e.message),
  })

  useEffect(() => {
    if (!open || !lines[0]) { setPreview([]); return }
    const handle = window.setTimeout(() => {
      api.preview({ commandLine: lines[0], flow: /--preset[ =]/.test(lines[0]) ? undefined : preset }).then((p) => { setPreview(p); setError(null) }).catch((e: Error) => { setPreview([]); setError(e.message) })
    }, 250)
    return () => window.clearTimeout(handle)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lines[0], preset, open])

  if (!open) return null

  const currentToken = lastToken(text)
  const suggestions = buildSuggestions(catalog.data, active?.defaultFocus ?? [], currentToken, text)
  const apply = (replacement: string) => setText(replaceLastToken(text, replacement))

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-slate-900/40 p-4 pt-[10vh]" onClick={onClose}>
      <div className="w-full max-w-3xl overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl dark:border-slate-700 dark:bg-slate-900" onClick={(e) => e.stopPropagation()}>
        <Command shouldFilter={false} loop>
          <div className="border-b border-slate-200 p-3 dark:border-slate-800">
            <div className="mb-2 flex flex-wrap items-center gap-1.5 text-xs">
              <span className="mr-1 text-slate-500">프리셋</span>
              {presets.map((p) => (
                <button key={p.name} onClick={() => setPreset(p.name)} title={p.description} className={`rounded-full border px-2 py-0.5 ${preset === p.name ? 'border-violet-500 bg-violet-50 text-violet-800 dark:bg-violet-950 dark:text-violet-200' : 'border-slate-300 text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800'}`}>
                  {p.label} <span className="mono opacity-70">{p.signature}</span>
                </button>
              ))}
              {presets.length === 0 && !catalog.isLoading && <span className="text-slate-400">구성 화면에서 프리셋을 먼저 만드세요</span>}
            </div>
            <textarea
              autoFocus
              id="command-input"
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Tab' && suggestions.length) { e.preventDefault(); apply(suggestions[0].value) }
                if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit.mutate() }
                if (e.key === 'Escape') onClose()
              }}
              rows={Math.min(6, Math.max(2, lines.length + 1))}
              placeholder={'"jwt refresh token flow" --focus security\nsrc/auth/ --focus architecture     (Shift+Enter로 줄 추가 = 작업 여러 개)'}
              className="mono w-full resize-none bg-transparent text-sm outline-none placeholder:text-slate-400"
            />
            <div className="mt-1 flex items-center gap-3 text-xs text-slate-500">
              {active && <span>{active.label} {active.signature}: {active.description}</span>}
              <span className="ml-auto text-slate-400">Enter 실행 · Tab 자동완성 · Esc 닫기</span>
            </div>
          </div>

          <Command.List className="max-h-56 overflow-auto p-2">
            {catalog.isLoading && <Command.Loading>불러오는 중…</Command.Loading>}
            {suggestions.length === 0 && <div className="px-2 py-3 text-sm text-slate-400">대상(파일 경로나 요청 문장)을 입력하세요. 옵션: --focus, --language, --preset</div>}
            {suggestions.map((s) => (
              <Command.Item key={s.value + s.label} value={s.value + s.label} onSelect={() => apply(s.value)} className="flex cursor-pointer items-start gap-3 rounded-md px-2 py-1.5 text-sm">
                <span className="mono min-w-28 text-sky-700 dark:text-sky-300">{s.value}</span>
                <span className="font-medium">{s.label}</span>
                <span className="text-slate-500">{s.description}</span>
              </Command.Item>
            ))}
          </Command.List>
        </Command>

        {preview.length > 0 && (
          <div className="border-t border-slate-200 p-3 dark:border-slate-800">
            <div className="mb-1 text-xs text-slate-500">실행 흐름 미리보기 (첫 번째 줄)</div>
            <FlowDiagram height={Math.max(200, Math.min(440, 110 + 80 * maxRows(preview)))} steps={preview.map((s) => ({ id: s.id, label: s.label, module: s.moduleName, role: s.role, dependsOn: s.dependsOn }))} />
          </div>
        )}

        <div className="flex items-center justify-between border-t border-slate-200 px-3 py-2 text-sm dark:border-slate-800">
          <span className="text-rose-600">{error}</span>
          <div className="flex items-center gap-2">
            <span className="text-xs text-slate-500">{lines.length}개 작업</span>
            <button onClick={() => submit.mutate()} disabled={submit.isPending || lines.length === 0} className="rounded-md bg-slate-900 px-3 py-1.5 text-white disabled:opacity-50 dark:bg-slate-100 dark:text-slate-900">
              {lines.length > 1 ? `${lines.length}개 실행` : '실행'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

interface Suggestion { value: string; label: string; description: string }

function maxRows(steps: PlanStep[]): number {
  const counts = new Map<number, number>()
  steps.forEach((s) => counts.set(s.stage, (counts.get(s.stage) ?? 0) + 1))
  return Math.max(1, ...[...counts.entries()].filter(([k]) => k > 0).map(([, v]) => v))
}

function lastToken(text: string): string {
  const line = text.split('\n').pop() ?? ''
  const parts = line.split(/\s+/)
  return parts[parts.length - 1] ?? ''
}

function replaceLastToken(text: string, replacement: string): string {
  const lines = text.split('\n')
  const line = lines.pop() ?? ''
  const idx = line.search(/\S+$/)
  const head = idx >= 0 ? line.slice(0, idx) : line
  lines.push(head + replacement + ' ')
  return lines.join('\n')
}

function buildSuggestions(catalog: Catalog | undefined, defaultFocus: string[], token: string, text: string): Suggestion[] {
  if (!catalog) return []
  const line = text.split('\n').pop() ?? ''
  const lower = token.toLowerCase()
  const out: Suggestion[] = []
  if (lower.startsWith('-')) {
    for (const o of catalog.options) if (o.flag.startsWith(lower)) out.push({ value: o.flag, label: o.labelKo, description: o.descriptionKo + (o.repeatable ? ' (반복 가능)' : '') })
    if ('--preset'.startsWith(lower)) out.push({ value: '--preset', label: '프리셋', description: '이 줄만 다른 프리셋으로 실행' })
  }
  const prev = line.trim().split(/\s+/).slice(-2)[0]
  if (prev === '--focus') for (const item of defaultFocus) if (!lower || item.startsWith(lower)) out.push({ value: item, label: '기본 focus', description: '' })
  if (prev === '--preset' || prev === '-p') for (const p of catalog.flows) if (!lower || p.name.startsWith(lower)) out.push({ value: p.name, label: `${p.label} ${p.signature}`, description: p.description })
  if (prev === '--language') out.push({ value: 'ko', label: '한국어', description: '' }, { value: 'en', label: 'English', description: '' })
  return out
}
