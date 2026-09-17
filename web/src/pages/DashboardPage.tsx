import { useQuery } from '@tanstack/react-query'
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts'
import { api } from '../lib/api'
import type { Job } from '../lib/types'
import { StatTile } from '../components/StatTile'
import { formatCost, formatTokens } from '../lib/format'

export function DashboardPage({ jobs }: { jobs: Job[] }) {
  const dash = useQuery({ queryKey: ['dashboard'], queryFn: api.dashboard, refetchInterval: 15_000 })
  const d = dash.data

  const running = jobs.filter((j) => j.status === 'RUNNING').length
  const queued = jobs.filter((j) => j.status === 'QUEUED').length
  const failed = jobs.filter((j) => j.status === 'FAILED' || j.status === 'TIMEOUT').length

  const claude = d?.status.modules.find((m) => m.name === 'claude')
  const codex = d?.status.modules.find((m) => m.name === 'codex')
  const remote = d?.status.anthropic
  const remoteTone = !remote || remote.indicator === 'unknown' || remote.indicator === 'unreachable' ? 'warn'
    : remote.indicator === 'none' ? 'ok' : remote.indicator === 'minor' ? 'warn' : 'bad'

  return (
    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
      <StatTile
        label="오늘 토큰 사용량"
        value={d ? formatTokens(d.usage.today.inputTokens + d.usage.today.outputTokens) : '…'}
        hint={d ? `입력 ${formatTokens(d.usage.today.inputTokens)} · 출력 ${formatTokens(d.usage.today.outputTokens)} · ${formatCost(d.usage.today.costUsd)}` : undefined}
      />
      <StatTile
        label="누적 토큰"
        value={d ? formatTokens(d.usage.total.inputTokens + d.usage.total.outputTokens) : '…'}
        hint={d ? Object.entries(d.usage.byModule).map(([m, u]) => `${m} ${formatTokens(u.inputTokens + u.outputTokens)}`).join(' · ') || '모듈별 기록 없음' : undefined}
      />
      <StatTile
        label="Claude 상태"
        tone={claude?.available ? remoteTone : 'bad'}
        value={claude ? (claude.available ? (claude.mode === 'cli' ? 'CLI 사용 가능' : '스텁 모드') : '설치 안 됨') : '…'}
        hint={
          <>
            {claude?.version && <div>{claude.version}</div>}
            {remote && <div>Anthropic: {remote.description || remote.indicator}</div>}
            {codex && <div>codex: {codex.available ? (codex.mode === 'cli' ? 'CLI 사용 가능' : '스텁') : '설치 안 됨'}</div>}
          </>
        }
      />
      <StatTile
        label="작업 진행"
        tone={failed ? 'warn' : running ? 'ok' : 'default'}
        value={`${running} 실행 중`}
        hint={`대기 ${queued} · 실패 ${failed} · 동시 실행 상한 ${d?.concurrency ?? '-'}`}
      />
      <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm md:col-span-2 xl:col-span-4 dark:border-slate-800 dark:bg-slate-900">
        <div className="mb-2 text-xs font-medium uppercase tracking-wide text-slate-500">최근 7일 토큰</div>
        <div className="h-40">
          {d && (
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={d.usage.last7Days} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
                <XAxis dataKey="date" tickFormatter={(v: string) => v.slice(5)} fontSize={11} />
                <YAxis fontSize={11} tickFormatter={(v: number) => formatTokens(v)} />
                <Tooltip formatter={(v) => formatTokens(Number(v))} labelFormatter={(l) => String(l)} />
                <Bar dataKey="inputTokens" name="입력" stackId="a" fill="#0ea5e9" radius={[0, 0, 0, 0]} />
                <Bar dataKey="outputTokens" name="출력" stackId="a" fill="#6366f1" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>
    </div>
  )
}
