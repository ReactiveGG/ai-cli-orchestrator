import type { ReactNode } from 'react'

export function StatTile({ label, value, hint, tone = 'default', children }: {
  label: string
  value: ReactNode
  hint?: ReactNode
  tone?: 'default' | 'ok' | 'warn' | 'bad'
  children?: ReactNode
}) {
  const toneClass = {
    default: 'border-slate-200 dark:border-slate-800',
    ok: 'border-emerald-300 dark:border-emerald-800',
    warn: 'border-amber-300 dark:border-amber-800',
    bad: 'border-rose-300 dark:border-rose-800',
  }[tone]
  return (
    <div className={`rounded-xl border bg-white p-4 shadow-sm dark:bg-slate-900 ${toneClass}`}>
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">{label}</div>
      <div className="mt-1 text-2xl font-semibold tabular-nums">{value}</div>
      {hint && <div className="mt-1 text-xs text-slate-500 dark:text-slate-400">{hint}</div>}
      {children}
    </div>
  )
}
