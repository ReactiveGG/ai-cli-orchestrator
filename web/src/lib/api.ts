import type {
  Catalog, Dashboard, FlowConfig, Job, JobEvent, JobRequest, LogLevel, PlanStep, Settings, StatusReport,
} from './types'
import { mockApi, mockSubscribe } from './mock'

/** `vite --mode mock` (see .env.mock) swaps the server for an in-browser simulation. */
export const MOCK = import.meta.env.VITE_MOCK === '1'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.error) message = body.error
    } catch {
      // keep default message
    }
    throw new Error(message)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const realApi = {
  dashboard: () => request<Dashboard>('/api/dashboard'),
  status: () => request<StatusReport>('/api/status'),
  catalog: () => request<Catalog>('/api/catalog'),
  jobs: () => request<Job[]>('/api/jobs'),
  job: (id: string) => request<Job>(`/api/jobs/${id}`),
  submit: (body: JobRequest) => request<Job>('/api/jobs', { method: 'POST', body: JSON.stringify(body) }),
  submitBatch: (body: JobRequest[]) => request<Job[]>('/api/jobs/batch', { method: 'POST', body: JSON.stringify(body) }),
  preview: (body: JobRequest) => request<PlanStep[]>('/api/jobs/preview', { method: 'POST', body: JSON.stringify(body) }),
  cancel: (id: string) => request<Job>(`/api/jobs/${id}/cancel`, { method: 'POST' }),
  applyCandidate: (id: string, candidate: number) => request<Job>(`/api/jobs/${id}/apply?candidate=${candidate}`, { method: 'POST' }),
  candidatePatch: async (id: string, candidate: number) => {
    const res = await fetch(`/api/jobs/${id}/candidates/${candidate}/patch`)
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
    return res.text()
  },
  remove: (id: string) => request<void>(`/api/jobs/${id}`, { method: 'DELETE' }),
  logs: (id: string, level: LogLevel, after = 0) => request<JobEvent[]>(`/api/jobs/${id}/logs?level=${level}&after=${after}`),
  routing: () => request<FlowConfig>('/api/config/routing'),
  saveRouting: (body: FlowConfig) => request<FlowConfig>('/api/config/routing', { method: 'PUT', body: JSON.stringify(body) }),
  resetRouting: (preset?: string) => request<FlowConfig>('/api/config/routing/reset', { method: 'POST', body: JSON.stringify(preset ? { preset } : {}) }),
  routingYaml: async () => (await fetch('/api/config/routing.yaml')).text(),
  settings: () => request<Settings>('/api/config/settings'),
  saveSettings: (body: Settings) => request<Settings>('/api/config/settings', { method: 'PUT', body: JSON.stringify(body) }),
}

export const api: typeof realApi = MOCK ? mockApi : realApi

/** Opens an SSE stream; returns a close function. */
function realSubscribe(
  url: string,
  handlers: Record<string, (data: unknown) => void>,
  onError?: () => void,
): () => void {
  const source = new EventSource(url)
  for (const [name, handler] of Object.entries(handlers)) {
    source.addEventListener(name, (event) => {
      try {
        handler(JSON.parse((event as MessageEvent).data))
      } catch {
        // ignore malformed frames
      }
    })
  }
  source.onerror = () => onError?.()
  return () => source.close()
}

export const subscribe: typeof realSubscribe = MOCK ? (url, handlers) => mockSubscribe(url, handlers) : realSubscribe
