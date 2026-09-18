import type {
  Catalog, DirListing, WorkspaceStatus, Dashboard, FlowConfig, Job, JobEvent, JobRequest, LogLevel, PlanStep, Settings, StatusReport,
} from './types'
import { mockApi, mockSubscribe } from './mock'
import { humanize } from './errors'

/** `vite --mode mock` (see .env.mock) swaps the server for an in-browser simulation. */
export const MOCK = import.meta.env.VITE_MOCK === '1'

// ---- API token: generated per install, handed only to this same-origin page via /api/session.
let token: string | null = null
let tokenPromise: Promise<string> | null = null
/** Server version from /api/session ("dev" outside a packaged build); '' until fetched. */
export let serverVersion = ''
async function ensureToken(): Promise<string> {
  if (MOCK) return ''
  if (token !== null) return token
  tokenPromise ??= fetch('/api/session').then(async (r) => {
    const j = r.ok ? await r.json() : { tokenRequired: false, token: '' }
    token = j.tokenRequired ? String(j.token) : ''
    serverVersion = j.version ? String(j.version) : ''
    return token
  }).catch(() => { token = ''; return '' })
  return tokenPromise
}
/** Appends the token to a URL opened outside fetch (EventSource, new-tab links). */
export function withToken(url: string): string {
  return token ? `${url}${url.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}` : url
}

async function request<T>(url: string, init?: RequestInit, retried = false): Promise<T> {
  const t = await ensureToken()
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(t ? { 'X-Orchestrator-Token': t } : {}), ...(init?.headers ?? {}) },
    ...init,
  }).catch((e: Error) => { throw new Error(humanize(e.message)) })
  if (res.status === 401 && !retried) {
    token = null; tokenPromise = null   // token rotated (new data dir / config): fetch it again once
    return request<T>(url, init, true)
  }
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.error) message = body.error
    } catch {
      // keep default message
    }
    throw new Error(humanize(message))
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const realApi = {
  dashboard: () => request<Dashboard>('/api/dashboard'),
  status: () => request<StatusReport>('/api/status'),
  /** Re-probe the CLIs now (after install/login) instead of waiting for the cache to expire. */
  refreshStatus: () => request<StatusReport>('/api/status/refresh', { method: 'POST' }),
  /** `claude auth logout` on the server's machine — logs every terminal on that PC out too. */
  claudeLogout: () => request<{ loggedOut: boolean; exitCode: number; output: string }>('/api/status/logout', { method: 'POST' }),
  /** Opens a terminal on the server's machine running `claude auth login`. */
  claudeLogin: () => request<{ opened: boolean; command: string; terminal: string }>('/api/status/login', { method: 'POST' }),
  catalog: () => request<Catalog>('/api/catalog'),
  jobs: () => request<Job[]>('/api/jobs'),
  job: (id: string) => request<Job>(`/api/jobs/${id}`),
  submit: (body: JobRequest) => request<Job>('/api/jobs', { method: 'POST', body: JSON.stringify(body) }),
  submitBatch: (body: JobRequest[]) => request<Job[]>('/api/jobs/batch', { method: 'POST', body: JSON.stringify(body) }),
  preview: (body: JobRequest) => request<PlanStep[]>('/api/jobs/preview', { method: 'POST', body: JSON.stringify(body) }),
  cancel: (id: string) => request<Job>(`/api/jobs/${id}/cancel`, { method: 'POST' }),
  applyCandidate: (id: string, candidate: number) => request<Job>(`/api/jobs/${id}/apply?candidate=${candidate}`, { method: 'POST' }),
  candidatePatch: async (id: string, candidate: number) => {
    const t = await ensureToken()
    const res = await fetch(`/api/jobs/${id}/candidates/${candidate}/patch`, { headers: t ? { 'X-Orchestrator-Token': t } : {} })
    if (!res.ok) throw new Error(humanize(`${res.status} ${res.statusText}`))
    return res.text()
  },
  remove: (id: string) => request<void>(`/api/jobs/${id}`, { method: 'DELETE' }),
  logs: (id: string, level: LogLevel, after = 0) => request<JobEvent[]>(`/api/jobs/${id}/logs?level=${level}&after=${after}`),
  routing: () => request<FlowConfig>('/api/config/routing'),
  saveRouting: (body: FlowConfig) => request<FlowConfig>('/api/config/routing', { method: 'PUT', body: JSON.stringify(body) }),
  resetRouting: (preset?: string) => request<FlowConfig>('/api/config/routing/reset', { method: 'POST', body: JSON.stringify(preset ? { preset } : {}) }),
  routingYaml: async () => { const t = await ensureToken(); return (await fetch('/api/config/routing.yaml', { headers: t ? { 'X-Orchestrator-Token': t } : {} })).text() },
  settings: () => request<Settings>('/api/config/settings'),
  saveSettings: (body: Settings) => request<Settings>('/api/config/settings', { method: 'PUT', body: JSON.stringify(body) }),
  /** Saved workspace: git repo? git installed? (competition mode needs both) */
  workspaceStatus: () => request<WorkspaceStatus>('/api/config/workspace'),
  /** "준비하기": git init in the saved workspace. */
  gitInit: () => request<WorkspaceStatus>('/api/config/workspace/git-init', { method: 'POST' }),
  /** Directory listing for the in-page folder picker (allowed workspace roots only). */
  listDirs: (path?: string) => request<DirListing>(`/api/fs/dirs${path ? `?path=${encodeURIComponent(path)}` : ''}`),
  /** Opens the OS folder dialog on the server's desktop; resolves to null when the user cancels. */
  browseFolder: (initial: string) => request<{ path: string | null; backend: string }>('/api/config/settings/browse', { method: 'POST', body: JSON.stringify({ initial }) }),
}

export const api: typeof realApi = MOCK ? mockApi : realApi

/** A stream with no frame (event or server ping) for this long is treated as dead and reopened. */
const STALE_MS = 40_000

/**
 * Opens an SSE stream; returns a close function. EventSource reconnects by itself
 * when the socket closes, but a proxy or port relay can keep a dead upstream's
 * connection open forever, so the server pings every 15s and a silent stream is
 * closed and reopened here (onError fires so the UI can show "연결 끊김").
 */
function realSubscribe(
  url: string,
  handlers: Record<string, (data: unknown) => void>,
  onError?: () => void,
  resumeFrom?: () => number,
): () => void {
  let source: EventSource | null = null
  let closed = false
  let lastFrame = Date.now()
  const open = async () => {
    await ensureToken()
    if (closed) return
    source?.close()
    const after = resumeFrom?.() ?? 0
    const target = after > 0 ? `${url}${url.includes('?') ? '&' : '?'}after=${after}` : url   // resume: replay only what this page has not seen
    source = new EventSource(withToken(target))   // EventSource cannot set headers, so the token travels as ?token=
    lastFrame = Date.now()
    source.addEventListener('ping', () => { lastFrame = Date.now() })
    for (const [name, handler] of Object.entries(handlers)) {
      source.addEventListener(name, (event) => {
        lastFrame = Date.now()
        try {
          handler(JSON.parse((event as MessageEvent).data))
        } catch {
          // ignore malformed frames
        }
      })
    }
    source.onerror = () => {
      onError?.()
      // A non-200 answer (proxy 502 while the server restarts) makes EventSource give up for good; retry ourselves.
      if (source?.readyState === EventSource.CLOSED && !closed) window.setTimeout(() => { if (!closed) void open() }, 5_000)
    }
  }
  void open()
  const watchdog = window.setInterval(() => {
    if (closed || Date.now() - lastFrame < STALE_MS) return
    onError?.()
    void open()
  }, 5_000)
  return () => { closed = true; window.clearInterval(watchdog); source?.close() }
}

export const subscribe: typeof realSubscribe = MOCK ? (url, handlers) => mockSubscribe(url, handlers) : realSubscribe

/** Query string for the paged/filtered job list (`GET /api/jobs?q=&status=&offset=&limit=`). */
export function jobsQuery(params: { q?: string; status?: string; offset?: number; limit?: number }): string {
  const p = new URLSearchParams()
  if (params.q) p.set('q', params.q)
  if (params.status) p.set('status', params.status)
  if (params.offset) p.set('offset', String(params.offset))
  if (params.limit) p.set('limit', String(params.limit))
  const s = p.toString()
  return s ? `?${s}` : ''
}
