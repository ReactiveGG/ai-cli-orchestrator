import { useCallback, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Command as CommandIcon, LayoutDashboard, ListChecks, Settings2 } from 'lucide-react'
import { api, subscribe, MOCK } from './lib/api'
import type { Job } from './lib/types'
import { CommandPalette } from './components/CommandPalette'
import { DashboardPage } from './pages/DashboardPage'
import { JobsPage } from './pages/JobsPage'
import { ConfigPage } from './pages/ConfigPage'

type View = 'dashboard' | 'jobs' | 'config'

/** URL hash is the source of truth for view + selected job: #dashboard[/<id>], #jobs[/<id>], #config. */
function readHash(): { view: View; selected: string | null } {
  const [view, id] = window.location.hash.replace(/^#/, '').split('/')
  const v: View = view === 'jobs' || view === 'config' ? view : 'dashboard'
  return { view: v, selected: v !== 'config' && id ? decodeURIComponent(id) : null }
}

export default function App() {
  const qc = useQueryClient()
  const [route, setRoute] = useState(readHash)
  const view = route.view
  const selected = route.selected
  // Switching tabs keeps the selected job; selecting a job keeps the current tab (dashboard or jobs).
  const setView = useCallback((v: View) => { window.location.hash = v !== 'config' && route.selected ? `${v}/${route.selected}` : v }, [route.selected])
  const setSelected = useCallback((id: string | null) => { const v = route.view === 'config' ? 'jobs' : route.view; window.location.hash = id ? `${v}/${id}` : v }, [route.view])
  useEffect(() => {
    const onHash = () => setRoute(readHash())
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [palettePreset, setPalettePreset] = useState<string | undefined>(undefined)
  const openPaletteWith = useCallback((preset: string) => { setPalettePreset(preset); setPaletteOpen(true) }, [])
  const [jobs, setJobs] = useState<Job[]>([])
  const [live, setLive] = useState(false)

  const initial = useQuery({ queryKey: ['jobs'], queryFn: api.jobs })
  useEffect(() => {
    if (initial.data) setJobs(initial.data)
  }, [initial.data])

  // One global SSE stream keeps the job list current everywhere.
  useEffect(() => {
    const close = subscribe(
      '/api/events',
      {
        jobs: (data) => { setJobs(data as Job[]); setLive(true) },
        job: (data) => {
          const job = data as Job
          setJobs((prev) => {
            const idx = prev.findIndex((j) => j.id === job.id)
            if (idx < 0) return [job, ...prev]
            const next = [...prev]
            next[idx] = job
            return next
          })
          if (job.status !== 'RUNNING' && job.status !== 'QUEUED') {
            qc.invalidateQueries({ queryKey: ['dashboard'] })
            notify(job)
          }
        },
      },
      () => setLive(false),
    )
    return close
  }, [qc])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen((o) => !o)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const cancel = useMutation({ mutationFn: api.cancel })
  const remove = useMutation({
    mutationFn: api.remove,
    onSuccess: (_, id) => {
      setJobs((prev) => prev.filter((j) => j.id !== id))
      if (selected === id) window.location.hash = view
    },
  })

  const onSubmitted = useCallback((ids: string[]) => {
    window.location.hash = ids[0] ? `jobs/${ids[0]}` : 'jobs'
  }, [])

  const running = jobs.filter((j) => j.status === 'RUNNING').length
  const queued = jobs.filter((j) => j.status === 'QUEUED').length

  const nav: { key: View; label: string; icon: React.ReactNode }[] = [
    { key: 'dashboard', label: '대시보드', icon: <LayoutDashboard size={16} /> },
    { key: 'jobs', label: '작업', icon: <ListChecks size={16} /> },
    { key: 'config', label: '구성', icon: <Settings2 size={16} /> },
  ]

  return (
    <div className="flex h-full flex-col">
      <header className="flex items-center gap-3 border-b border-slate-200 bg-white px-4 py-2 dark:border-slate-800 dark:bg-slate-900">
        <div className="font-semibold">AI CLI Orchestrator{MOCK && <span className="ml-2 rounded-full border border-slate-300 px-2 text-[10px] font-medium uppercase tracking-wide text-slate-500 dark:border-slate-600">mock</span>}</div>
        <nav className="ml-4 flex gap-1">
          {nav.map((n) => (
            <button
              key={n.key}
              onClick={() => setView(n.key)}
              className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm ${view === n.key ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'}`}
            >
              {n.icon}
              {n.label}
              {n.key === 'jobs' && (running + queued > 0) && <span className="rounded-full bg-sky-500 px-1.5 text-[10px] text-white">{running + queued}</span>}
            </button>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-3">
          <span className={`flex items-center gap-1 text-xs ${live ? 'text-emerald-600' : 'text-rose-600'}`}>
            <span className={`inline-block h-2 w-2 rounded-full ${live ? 'bg-emerald-500' : 'bg-rose-500'}`} />
            {live ? '실시간 연결' : '서버 연결 끊김'}
          </span>
          <button onClick={() => setPaletteOpen(true)} className="flex items-center gap-2 rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">
            <CommandIcon size={14} /> 명령 <kbd className="rounded border border-slate-300 px-1 text-[10px] dark:border-slate-600">Ctrl K</kbd>
          </button>
        </div>
      </header>

      <main className="flex min-h-0 flex-1 flex-col gap-3 overflow-auto p-4">
        {view === 'dashboard' && (
          <>
            <DashboardPage jobs={jobs} />
            <div className="text-sm font-semibold">최근 작업</div>
            <JobsPage jobs={jobs.slice(0, 8)} selectedId={selected} onSelect={(id) => setSelected(id)} onCancel={(id) => cancel.mutate(id)} onDelete={(id) => remove.mutate(id)} />
          </>
        )}
        {view === 'jobs' && (
          <JobsPage jobs={jobs} selectedId={selected} onSelect={setSelected} onCancel={(id) => cancel.mutate(id)} onDelete={(id) => remove.mutate(id)} />
        )}
        {view === 'config' && <ConfigPage onRun={(preset) => openPaletteWith(preset)} />}
      </main>

      <CommandPalette open={paletteOpen} initialPreset={palettePreset} onClose={() => { setPaletteOpen(false); setPalettePreset(undefined) }} onSubmitted={onSubmitted} />
    </div>
  )
}

function notify(job: Job) {
  if (typeof Notification === 'undefined') return
  if (Notification.permission === 'default') {
    Notification.requestPermission().catch(() => undefined)
    return
  }
  if (Notification.permission !== 'granted') return
  const title = job.status === 'SUCCEEDED' ? '작업 완료' : job.status === 'TIMEOUT' ? '작업 시간 초과' : job.status === 'CANCELLED' ? '작업 취소됨' : '작업 실패'
  try {
    new Notification(title, { body: job.command })
  } catch {
    // notifications unsupported in this context
  }
}
