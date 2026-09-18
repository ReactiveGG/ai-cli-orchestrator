import { useQuery } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { TokenChart } from '../components/TokenChart'
import { api } from '../lib/api'
import type { Job, RateLimitInfo } from '../lib/types'
import { StatTile } from '../components/StatTile'
import { formatCost, formatTime, formatTokens } from '../lib/format'
import { useNow } from '../lib/useNow'
import { ErrorBox } from '../components/Feedback'
import { errorMessage } from '../lib/errors'

export function DashboardPage({ jobs }: { jobs: Job[] }) {
  const dash = useQuery({ queryKey: ['dashboard'], queryFn: api.dashboard, refetchInterval: 10_000 })
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
    <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-5">
      {dash.isError && <ErrorBox className="md:col-span-2 xl:col-span-5" title="대시보드를 불러오지 못했습니다" message={errorMessage(dash.error)} onRetry={() => dash.refetch()} />}
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
        tone={claude?.available ? (claude.loggedIn === false ? 'bad' : remoteTone) : 'bad'}
        value={claude ? (claude.available ? (claude.mode === 'cli' ? (claude.loggedIn === false ? '로그인 필요' : 'CLI 사용 가능') : '스텁 모드') : '설치 안 됨') : '…'}
        hint={
          <>
            {claude?.version && <div>{claude.version}{claude.loggedIn === true && claude.authMethod ? ` · 로그인: ${claude.authMethod}` : ''}</div>}
            {claude?.loggedIn === false && <div className="text-rose-600 dark:text-rose-300">터미널에서 <span className="mono">claude</span> 실행 후 /login 하면 작업을 실행할 수 있습니다</div>}
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
      <SubscriptionTile info={d?.subscription ?? null} loading={!d} />
      <div className="min-w-0 rounded-xl border border-slate-200 bg-white p-4 shadow-sm md:col-span-2 xl:col-span-5 dark:border-slate-800 dark:bg-slate-900">
        <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
          <div className="text-xs font-medium uppercase tracking-wide text-slate-500">최근 7일 토큰</div>
          <span className="text-xs text-slate-400">{d ? `갱신 ${formatTime(d.generatedAt)} · 10초마다 자동` : '불러오는 중…'}</span>
          <button onClick={() => dash.refetch()} disabled={dash.isFetching} title="새로고침" className="ml-auto inline-flex items-center gap-1 rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">
            <RefreshCw size={12} className={dash.isFetching ? 'animate-spin' : ''} /> 새로고침
          </button>
        </div>
        <div className="h-56 md:h-56">
          {d ? (d.usage.last7Days.some((r) => r.inputTokens + r.outputTokens > 0) ? <TokenChart data={d.usage.last7Days} /> : <div className="flex h-full items-center justify-center text-sm text-slate-400">아직 토큰 사용 기록이 없습니다. 첫 작업을 실행하면 여기에 쌓입니다.</div>)
            : dash.isError ? null : <div className="flex h-full items-center justify-center text-sm text-slate-400">불러오는 중…</div>}
        </div>
      </div>
    </div>
  )
}

/**
 * Subscription windows (5-hour / 7-day) from the CLI's rate_limit_event. On a
 * subscription plan these, not dollars, are the budget that runs out.
 */
function SubscriptionTile({ info, loading }: { info: RateLimitInfo | null; loading: boolean }) {
  const now = useNow()
  const worst = Math.max(info?.fiveHourUtilization ?? 0, info?.sevenDayUtilization ?? 0)
  const blocked = !!info && info.status !== null && info.status !== 'allowed'
  const tone = !info ? 'default' : blocked || worst >= 0.9 ? 'bad' : worst >= 0.75 ? 'warn' : 'ok'
  const ageMin = info ? Math.max(0, Math.round((now - new Date(info.observedAt).getTime()) / 60000)) : 0
  return (
    <StatTile
      label="구독 사용량"
      tone={tone}
      value={loading ? '…' : !info ? '관측 없음' : blocked ? (info.status === 'allowed_warning' ? '한도 임박' : '한도 도달') : `${Math.round(worst * 100)}%`}
      hint={!info
        ? (loading ? undefined : '실제 Claude 작업이 한 번 돌면 5시간·7일 창 사용률이 여기에 표시됩니다')
        : <>
            <Meter label="5시간" value={info.fiveHourUtilization} resetsAt={info.fiveHourResetsAt} now={now} />
            <Meter label="7일" value={info.sevenDayUtilization} resetsAt={info.sevenDayResetsAt} now={now} />
            <div className="mt-1 text-slate-400">{ageMin < 1 ? '방금' : `${ageMin}분 전`} 관측{info.status && info.status !== 'allowed' ? ` · CLI 상태 ${info.status}` : ''}</div>
          </>}
    />
  )
}

function Meter({ label, value, resetsAt, now }: { label: string; value: number | null; resetsAt: string | null; now: number }) {
  if (value === null) return <div className="mt-1">{label}: 보고 없음</div>
  const pct = Math.min(100, Math.max(0, Math.round(value * 100)))
  const color = pct >= 90 ? 'bg-rose-500' : pct >= 75 ? 'bg-amber-500' : 'bg-sky-500'
  const left = resetsAt ? Math.max(0, new Date(resetsAt).getTime() - now) : null
  const leftText = left === null ? '' : left < 3600e3 ? ` · ${Math.ceil(left / 60000)}분 후 초기화` : left < 86400e3 ? ` · ${Math.round(left / 3600e3)}시간 후 초기화` : ` · ${formatTime(resetsAt!)} 초기화`
  return (
    <div className="mt-1">
      <div className="flex justify-between"><span>{label} {pct}%</span><span className="text-slate-400">{leftText.replace(/^ · /, '')}</span></div>
      <div className="mt-0.5 h-1.5 overflow-hidden rounded bg-slate-200 dark:bg-slate-700" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label={`${label} 사용률`}>
        <div className={`h-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}
