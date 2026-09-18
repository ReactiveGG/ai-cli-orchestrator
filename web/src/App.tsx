import { useCallback, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, BellOff, BellRing, Command as CommandIcon, ExternalLink, LayoutDashboard, ListChecks, Settings2 } from 'lucide-react'
import { api, subscribe, MOCK, serverVersion } from './lib/api'
import type { Job } from './lib/types'
import { errorMessage } from './lib/errors'
import { notifyJob, notifyLabel, notifyState, toggleNotify, type NotifyState } from './lib/notify'
import { CommandPalette } from './components/CommandPalette'
import { ErrorBox } from './components/Feedback'
import { DashboardPage } from './pages/DashboardPage'
import { JobsPage } from './pages/JobsPage'
import { ConfigPage } from './pages/ConfigPage'

type View = 'dashboard' | 'jobs' | 'config'
/** connecting: first SSE frame not yet received · live: streaming · down: stream errored, browser is retrying. */
type Conn = 'connecting' | 'live' | 'down'

/** URL hash is the source of truth for view + selected job: #dashboard[/<id>], #jobs[/<id>], #config. #command opens the palette. */
function readHash(): { view: View; selected: string | null; command: boolean } {
  const [view, id] = window.location.hash.replace(/^#/, '').split('/')
  // #config-settings = the config tab scrolled to the server-settings panel (links from the dashboard and the config sidebar)
  const v: View = view === 'jobs' || view === 'config' || view === 'config-settings' ? (view === 'config-settings' ? 'config' : view) : 'dashboard'
  return { view: v, selected: v !== 'config' && id ? decodeURIComponent(id) : null, command: view === 'command' }
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
  // After the config tab renders, bring the server-settings panel into view and focus the claude executable field.
  useEffect(() => {
    if (window.location.hash !== '#config-settings') return
    const t = window.setTimeout(() => {
      document.getElementById('config-settings')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      const field = document.getElementById('module-claude-command') as HTMLInputElement | null
      field?.focus(); field?.select()
    }, 150)
    return () => window.clearTimeout(t)
  }, [route])
  const [paletteOpen, setPaletteOpen] = useState(route.command)
  useEffect(() => { if (route.command) setPaletteOpen(true) }, [route.command])
  const [palettePreset, setPalettePreset] = useState<string | undefined>(undefined)
  const [jobs, setJobs] = useState<Job[]>([])
  const [conn, setConn] = useState<Conn>('connecting')
  const [notify, setNotify] = useState<NotifyState>(notifyState)

  const initial = useQuery({ queryKey: ['jobs'], queryFn: api.jobs })
  const settings = useQuery({ queryKey: ['settings'], queryFn: api.settings, staleTime: 60_000 })
  // version arrives with the session token; MOCK has none
  const version = MOCK ? 'mock' : (settings.data ? serverVersion : '')
  useEffect(() => {
    if (initial.data) setJobs(initial.data)
  }, [initial.data])

  // One global SSE stream keeps the job list current everywhere. EventSource reconnects by itself;
  // a fresh `jobs` snapshot arrives on every (re)connect, which is what flips the state back to live.
  useEffect(() => {
    const close = subscribe(
      '/api/events',
      {
        jobs: (data) => { setJobs(data as Job[]); setConn('live'); qc.invalidateQueries({ queryKey: ['dashboard'] }) },
        // server pushes this when a login it opened completes (or the CLI state changes): refetch the tile, no reload
        status: () => { qc.invalidateQueries({ queryKey: ['dashboard'] }); qc.invalidateQueries({ queryKey: ['catalog'] }) },
        job: (data) => {
          const job = data as Job
          setJobs((prev) => {
            const idx = prev.findIndex((j) => j.id === job.id)
            if (idx < 0) return [job, ...prev]
            const next = [...prev]
            next[idx] = job
            return next
          })
          qc.invalidateQueries({ queryKey: ['dashboard'] })
          if (job.status !== 'RUNNING' && job.status !== 'QUEUED') notifyJob(job)
        },
      },
      () => setConn('down'),
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

  const [actionError, setActionError] = useState<string | null>(null)
  const cancel = useMutation({ mutationFn: api.cancel, onError: (e) => setActionError(`취소 실패: ${errorMessage(e)}`), onSuccess: () => setActionError(null) })
  const remove = useMutation({
    mutationFn: api.remove,
    onError: (e) => setActionError(`삭제 실패: ${errorMessage(e)}`),
    onSuccess: (_, id) => {
      setActionError(null)
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
  const connText = { connecting: '연결 중…', live: '실시간 연결', down: '서버 연결 끊김' }[conn]
  const connTone = { connecting: 'text-amber-600 dark:text-amber-400', live: 'text-emerald-600 dark:text-emerald-400', down: 'text-rose-600 dark:text-rose-400' }[conn]
  const connDot = { connecting: 'bg-amber-500 animate-pulse', live: 'bg-emerald-500', down: 'bg-rose-500' }[conn]
  const BellIcon = notify === 'on' ? BellRing : notify === 'off' ? Bell : BellOff

  return (
    <div className="flex h-full flex-col">
      <header className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-slate-200 bg-white px-4 py-2 dark:border-slate-800 dark:bg-slate-900">
        <a href="#dashboard" onClick={(e) => { e.preventDefault(); setView('dashboard') }} title="대시보드로" className="flex items-center gap-2 whitespace-nowrap font-semibold hover:opacity-80">
          <img src="/favicon.png" alt="" width={22} height={22} className="rounded-md" />
          AI CLI Orchestrator{MOCK && <span className="ml-1 rounded-full border border-slate-300 px-2 text-[10px] font-medium uppercase tracking-wide text-slate-500 dark:border-slate-600">mock</span>}
        </a>
        <nav className="order-last flex w-full gap-1 sm:order-none sm:ml-4 sm:w-auto">
          {nav.map((n) => (
            <button
              key={n.key}
              onClick={() => setView(n.key)}
              className={`flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-sm ${view === n.key ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'}`}
            >
              {n.icon}
              {n.label}
              {n.key === 'jobs' && (running + queued > 0) && <span className="rounded-full bg-sky-500 px-1.5 text-[10px] text-white">{running + queued}</span>}
            </button>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-3">
          <span className={`flex items-center gap-1 whitespace-nowrap text-xs ${connTone}`} title={conn === 'down' ? '브라우저가 자동으로 다시 연결합니다' : undefined}>
            <span className={`inline-block h-2 w-2 rounded-full ${connDot}`} />
            {connText}
          </span>
          <button
            onClick={() => { toggleNotify().then(setNotify) }}
            disabled={notify === 'unsupported'}
            title={notifyLabel[notify]}
            aria-label="완료 알림"
            aria-pressed={notify === 'on'}
            className={`rounded-md border p-1.5 disabled:opacity-40 ${notify === 'on' ? 'border-sky-400 bg-sky-50 text-sky-700 dark:border-sky-700 dark:bg-sky-950 dark:text-sky-300' : notify === 'blocked' ? 'border-rose-300 text-rose-600 dark:border-rose-800 dark:text-rose-400' : 'border-slate-300 text-slate-500 hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800'}`}
          >
            <BellIcon size={14} />
          </button>
          <button onClick={() => setPaletteOpen(true)} className="flex items-center gap-2 whitespace-nowrap rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">
            <CommandIcon size={14} /> 명령 <kbd className="hidden rounded border border-slate-300 px-1 text-[10px] sm:inline dark:border-slate-600">Ctrl K</kbd>
          </button>
        </div>
      </header>

      <main className="flex min-h-0 flex-1 flex-col gap-3 overflow-auto px-4 pb-12 pt-4">
        {conn === 'down' && !MOCK && (
          <ErrorBox title="서버 연결이 끊겼습니다" message="브라우저가 자동으로 다시 연결합니다. 서버가 꺼졌다면 run.cmd(Windows) 또는 run.sh로 다시 실행하세요. 화면의 작업 상태는 마지막으로 받은 값입니다." />
        )}
        {initial.isError && (
          <ErrorBox title="작업 목록을 불러오지 못했습니다" message={errorMessage(initial.error)} onRetry={() => initial.refetch()} />
        )}
        {actionError && <ErrorBox title="요청 실패" message={actionError} onRetry={() => setActionError(null)} />}
        {view === 'dashboard' && (
          <>
            <DashboardPage jobs={jobs} />
            <div className="text-sm font-semibold">최근 작업</div>
            <JobsPage jobs={jobs.slice(0, 8)} loading={initial.isPending} selectedId={selected} onSelect={(id) => setSelected(id)} onCancel={(id) => cancel.mutate(id)} onDelete={(id) => remove.mutate(id)} />
          </>
        )}
        {view === 'jobs' && (
          <JobsPage jobs={jobs} loading={initial.isPending} filterable selectedId={selected} onSelect={setSelected} onCancel={(id) => cancel.mutate(id)} onDelete={(id) => remove.mutate(id)} />
        )}
        {view === 'config' && <ConfigPage />}
      </main>

      <footer className="border-t border-slate-200 bg-white px-4 py-3 text-xs text-slate-500 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-400">
        <div className="mx-auto flex max-w-[1400px] flex-wrap items-center justify-between gap-x-6 gap-y-1">
          <span className="whitespace-nowrap font-medium text-slate-700 dark:text-slate-200">AI CLI Orchestrator <span className="mono font-normal text-slate-400">{version ? `v${version}` : ''}</span></span>
          <a href="https://github.com/ReactiveGG/ai-cli-orchestrator" target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 whitespace-nowrap hover:text-slate-800 dark:hover:text-slate-100">제작 ReactiveGG <ExternalLink size={11} /></a>
        </div>
      </footer>

      <CommandPalette open={paletteOpen} initialPreset={palettePreset} onClose={() => { setPaletteOpen(false); setPalettePreset(undefined); if (route.command) window.location.hash = 'dashboard' }} onSubmitted={onSubmitted} />
    </div>
  )
}

