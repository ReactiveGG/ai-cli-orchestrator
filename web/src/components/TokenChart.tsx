import { useEffect, useState } from 'react'
import { AreaChart, Area, XAxis, YAxis, Tooltip, CartesianGrid, ResponsiveContainer, Legend } from 'recharts'
import type { DailyUsage } from '../lib/types'
import { formatTokens } from '../lib/format'

/**
 * 7-day token usage as two stacked areas (input, output). Series colours are
 * the validated blue/orange pair, re-stepped for the dark surface; text uses
 * the theme's ink tokens, never the series colour.
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

export function TokenChart({ data }: { data: DailyUsage[] }) {
  const c = useTheme()
  const rows = data.map((d) => ({ ...d, label: d.date.slice(5).replace('-', '/'), total: d.inputTokens + d.outputTokens }))
  const max = Math.max(1, ...rows.map((r) => r.total))
  return (
    <ResponsiveContainer width="100%" height="100%">
      <AreaChart data={rows} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="tok-in" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={c.input} stopOpacity={0.35} /><stop offset="100%" stopColor={c.input} stopOpacity={0.03} /></linearGradient>
          <linearGradient id="tok-out" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={c.output} stopOpacity={0.35} /><stop offset="100%" stopColor={c.output} stopOpacity={0.03} /></linearGradient>
        </defs>
        <CartesianGrid vertical={false} stroke={c.grid} strokeDasharray="2 4" />
        <XAxis dataKey="label" tick={{ fill: c.ink, fontSize: 11 }} axisLine={{ stroke: c.grid }} tickLine={false} />
        <YAxis tick={{ fill: c.ink, fontSize: 11 }} axisLine={false} tickLine={false} width={44} tickFormatter={(v: number) => formatTokens(v)} domain={[0, Math.ceil(max * 1.1)]} />
        <Tooltip
          cursor={{ stroke: c.ink, strokeWidth: 1, strokeDasharray: '3 3' }}
          contentStyle={{ background: c.surface, border: `1px solid ${c.grid}`, borderRadius: 8, fontSize: 12, color: c.ink }}
          formatter={(v, name) => [formatTokens(Number(v)) + ' 토큰', name === 'inputTokens' ? '입력' : '출력']}
          labelFormatter={(l, payload) => { const p = payload?.[0]?.payload as (typeof rows)[number] | undefined; return p ? `${p.date} · 작업 ${p.jobs}개 · 합계 ${formatTokens(p.total)}` : String(l) }}
        />
        <Legend verticalAlign="top" align="right" height={20} iconType="circle" iconSize={8} formatter={(v) => <span style={{ color: c.ink, fontSize: 11 }}>{v === 'inputTokens' ? '입력' : '출력'}</span>} />
        <Area type="monotone" dataKey="inputTokens" stackId="1" stroke={c.input} strokeWidth={2} fill="url(#tok-in)" dot={{ r: 3, fill: c.surface, stroke: c.input, strokeWidth: 2 }} activeDot={{ r: 5, stroke: c.surface, strokeWidth: 2 }} isAnimationActive={false} />
        <Area type="monotone" dataKey="outputTokens" stackId="1" stroke={c.output} strokeWidth={2} fill="url(#tok-out)" dot={{ r: 3, fill: c.surface, stroke: c.output, strokeWidth: 2 }} activeDot={{ r: 5, stroke: c.surface, strokeWidth: 2 }} isAnimationActive={false} />
      </AreaChart>
    </ResponsiveContainer>
  )
}
