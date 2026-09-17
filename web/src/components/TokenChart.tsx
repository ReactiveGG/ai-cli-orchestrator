import { useEffect, useState } from 'react'
import { AreaChart, Area, XAxis, YAxis, Tooltip, CartesianGrid, ResponsiveContainer } from 'recharts'
import type { DailyUsage } from '../lib/types'
import { formatTokens } from '../lib/format'

/**
 * 7-day token usage as two small multiples with their own scales: input tokens
 * (prompt + cache) are ~100× the output tokens, so one shared axis would flatten
 * the output line. Series colours are the validated blue/orange pair, re-stepped
 * for the dark surface; text uses the theme's ink tokens, never the series colour.
 */
const LIGHT = { input: '#2a78d6', output: '#eb6834', grid: '#e2e8f0', ink: '#64748b', surface: '#ffffff' }
const DARK = { input: '#3987e5', output: '#d95926', grid: '#334155', ink: '#94a3b8', surface: '#0f172a' }

function useTheme() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))
  useEffect(() => {
    const obs = new MutationObserver(() => setDark(document.documentElement.classList.contains('dark')))
    obs.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
    return () => obs.disconnect()
  }, [])
  return dark ? DARK : LIGHT
}

type Row = DailyUsage & { label: string }

export function TokenChart({ data }: { data: DailyUsage[] }) {
  const c = useTheme()
  const rows: Row[] = data.map((d) => ({ ...d, label: d.date.slice(5).replace('-', '/') }))
  const total = (key: 'inputTokens' | 'outputTokens') => rows.reduce((a, r) => a + r[key], 0)
  return (
    <div className="grid h-full gap-3 md:grid-cols-2">
      <Panel title="입력 토큰" hint="프롬프트 + 캐시 읽기/생성" total={total('inputTokens')} rows={rows} dataKey="inputTokens" color={c.input} id="tok-in" c={c} />
      <Panel title="출력 토큰" hint="모델이 생성한 텍스트" total={total('outputTokens')} rows={rows} dataKey="outputTokens" color={c.output} id="tok-out" c={c} />
    </div>
  )
}

function Panel({ title, hint, total, rows, dataKey, color, id, c }: {
  title: string; hint: string; total: number; rows: Row[]; dataKey: 'inputTokens' | 'outputTokens'; color: string; id: string; c: typeof LIGHT
}) {
  const max = Math.max(1, ...rows.map((r) => r[dataKey]))
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="mb-1 flex items-baseline gap-2 text-xs">
        <span className="inline-block h-2 w-2 rounded-full" style={{ background: color }} />
        <span className="font-medium text-slate-700 dark:text-slate-200">{title}</span>
        <span className="text-slate-400">{hint}</span>
        <span className="ml-auto tabular-nums text-slate-500">7일 합계 {formatTokens(total)}</span>
      </div>
      <div className="min-h-0 flex-1 [&_.recharts-wrapper:focus]:outline-none [&_.recharts-surface:focus]:outline-none [&_svg:focus]:outline-none">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={rows} margin={{ top: 6, right: 10, left: 0, bottom: 0 }} accessibilityLayer={false}>
            <defs>
              <linearGradient id={id} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={color} stopOpacity={0.3} /><stop offset="100%" stopColor={color} stopOpacity={0.02} /></linearGradient>
            </defs>
            <CartesianGrid vertical={false} stroke={c.grid} strokeDasharray="2 4" />
            <XAxis dataKey="label" tick={{ fill: c.ink, fontSize: 11 }} axisLine={{ stroke: c.grid }} tickLine={false} />
            <YAxis tick={{ fill: c.ink, fontSize: 11 }} axisLine={false} tickLine={false} width={44} tickFormatter={(v: number) => formatTokens(v)} domain={[0, Math.ceil(max * 1.15)]} />
            <Tooltip
              cursor={{ stroke: c.ink, strokeWidth: 1, strokeDasharray: '3 3' }}
              contentStyle={{ background: c.surface, border: `1px solid ${c.grid}`, borderRadius: 8, fontSize: 12, color: c.ink }}
              formatter={(v) => [formatTokens(Number(v)) + ' 토큰', title]}
              labelFormatter={(l, payload) => { const p = payload?.[0]?.payload as Row | undefined; return p ? `${p.date} · 작업 ${p.jobs}개` : String(l) }}
            />
            <Area type="monotone" dataKey={dataKey} stroke={color} strokeWidth={2} fill={`url(#${id})`} dot={{ r: 3, fill: c.surface, stroke: color, strokeWidth: 2 }} activeDot={{ r: 5, stroke: c.surface, strokeWidth: 2 }} isAnimationActive={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
