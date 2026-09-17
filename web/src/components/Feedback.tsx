import { useEffect, useState } from 'react'
import { AlertTriangle, Loader2, RefreshCw } from 'lucide-react'

/** Shared loading / error / confirm pieces so every screen fails the same way. */
export function Loading({ label = '불러오는 중…', className = '' }: { label?: string; className?: string }) {
  return <div className={`flex items-center gap-2 p-6 text-sm text-slate-400 ${className}`}><Loader2 size={16} className="animate-spin" /> {label}</div>
}

export function ErrorBox({ title = '불러오지 못했습니다', message, onRetry, className = '' }: { title?: string; message: string; onRetry?: () => void; className?: string }) {
  return (
    <div className={`flex flex-wrap items-center gap-3 rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-200 ${className}`}>
      <AlertTriangle size={16} className="shrink-0" />
      <span className="font-medium">{title}</span>
      <span className="min-w-0 break-words text-rose-700 dark:text-rose-300">{message}</span>
      {onRetry && <button onClick={onRetry} className="ml-auto inline-flex items-center gap-1 rounded border border-rose-300 px-2 py-0.5 text-xs hover:bg-rose-100 dark:border-rose-700 dark:hover:bg-rose-900"><RefreshCw size={12} /> 다시 시도</button>}
    </div>
  )
}

/** Inline two-step confirmation: first click arms the button for 4 seconds, second click confirms. */
export function ConfirmButton({ label, confirmLabel, onConfirm, className = '', armedClassName = '' }: { label: string; confirmLabel: string; onConfirm: () => void; className?: string; armedClassName?: string }) {
  const [armed, setArmed] = useState(false)
  useEffect(() => {
    if (!armed) return
    const t = window.setTimeout(() => setArmed(false), 4000)
    return () => window.clearTimeout(t)
  }, [armed])
  return (
    <button
      onClick={(e) => { e.stopPropagation(); if (armed) { setArmed(false); onConfirm() } else setArmed(true) }}
      onBlur={() => setArmed(false)}
      title={armed ? '한 번 더 누르면 실행됩니다' : undefined}
      className={`shrink-0 whitespace-nowrap rounded border px-2 py-0.5 text-xs ${armed ? `border-rose-500 bg-rose-600 text-white hover:bg-rose-700 ${armedClassName}` : className}`}
    >
      {armed ? confirmLabel : label}
    </button>
  )
}

/** Status line under a save button: red when the message reports a failure. */
export function Message({ text }: { text: string | null }) {
  if (!text) return null
  const bad = /실패|오류|없습니다|필요/.test(text)
  return <span className={`text-sm ${bad ? 'text-rose-600 dark:text-rose-300' : 'text-emerald-700 dark:text-emerald-300'}`}>{text}</span>
}
