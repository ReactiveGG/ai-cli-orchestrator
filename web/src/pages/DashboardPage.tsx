import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { TokenChart } from '../components/TokenChart'
import { api } from '../lib/api'
import type { Job, RateLimitInfo } from '../lib/types'
import { StatTile } from '../components/StatTile'
import { formatCost, formatTime, formatTokens } from '../lib/format'
import { useNow } from '../lib/useNow'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { LogIn, LogOut, RefreshCw as Recheck } from 'lucide-react'
import { ErrorBox } from '../components/Feedback'
import { errorMessage } from '../lib/errors'

export function DashboardPage({ jobs }: { jobs: Job[] }) {
  const dash = useQuery({ queryKey: ['dashboard'], queryFn: api.dashboard, refetchInterval: 10_000 })
  const d = dash.data
  // The refresh button also asks Claude for fresh subscription windows (the CLI reports them only while it runs).
  // The 10s auto-refresh deliberately does not: it would spend usage to measure usage.
  const [probeNote, setProbeNote] = useState<string | null>(null)
  const probe = useMutation({ mutationFn: api.probeUsage, onSuccess: () => { setProbeNote(null); dash.refetch() }, onError: (e) => setProbeNote(errorMessage(e)) })
  const refreshAll = () => { dash.refetch(); if (d?.status.modules.find((m) => m.name === 'claude')?.mode === 'cli') probe.mutate() }

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
            {claude && !claude.available && <div className="text-rose-600 dark:text-rose-300">Claude Code CLI를 찾지 못해 스텁으로 돕니다(실제 호출 없음). 설치돼 있다면 아래 "다시 확인"을 누르거나 구성 탭 → 서버 설정의 "실행 파일명"에 경로를 넣으세요.{claude.searched && <div className="mt-0.5 break-all text-[11px] text-slate-400">찾아본 곳: {claude.searched}</div>}</div>}
            {claude?.available && claude.command && <div className="mono break-all text-[11px] text-slate-400">{claude.command}</div>}
            {claude?.loggedIn === false && <div className="text-rose-600 dark:text-rose-300">로그인이 안 돼 있어 작업이 실패합니다. "로그인 창 열기"를 누르면 이 PC에 터미널이 뜨고 브라우저 로그인으로 이어집니다.</div>}
            {claude && (!claude.available || claude.mode === 'cli') && <ClaudeActions showLogin={claude.available && claude.loggedIn !== true} showLogout={claude.available && claude.loggedIn === true} onDone={() => dash.refetch()} />}
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
      <SubscriptionTile info={d?.subscription ?? null} loading={!d} probing={probe.isPending} note={probeNote} />
      <div className="min-w-0 rounded-xl border border-slate-200 bg-white p-4 shadow-sm md:col-span-2 xl:col-span-5 dark:border-slate-800 dark:bg-slate-900">
        <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
          <div className="text-xs font-medium uppercase tracking-wide text-slate-500">최근 7일 토큰</div>
          <span className="text-xs text-slate-400">{d ? `갱신 ${formatTime(d.generatedAt)} · 10초마다 자동` : '불러오는 중…'}</span>
          <button onClick={() => refreshAll()} disabled={dash.isFetching || probe.isPending} title="토큰 그래프와 구독 사용량을 새로 조회합니다 (Claude에 아주 짧은 호출 1회)" className="ml-auto inline-flex items-center gap-1 rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">
            <RefreshCw size={12} className={dash.isFetching || probe.isPending ? 'animate-spin' : ''} /> {probe.isPending ? '사용량 조회 중…' : '새로고침'}
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
function SubscriptionTile({ info, loading, probing, note }: { info: RateLimitInfo | null; loading: boolean; probing?: boolean; note?: string | null }) {
  const now = useNow()
  const worst = Math.max(info?.fiveHourUtilization ?? 0, info?.sevenDayUtilization ?? 0)
  const blocked = !!info && info.status !== null && info.status !== 'allowed'
  const tone = !info ? 'default' : blocked || worst >= 0.9 ? 'bad' : worst >= 0.75 ? 'warn' : 'ok'
  const ageMin = info ? Math.max(0, Math.round((now - new Date(info.observedAt).getTime()) / 60000)) : 0
  return (
    <StatTile
      label="구독 사용량"
      tone={tone}
      value={loading ? '…' : probing ? '조회 중…' : !info ? '관측 없음' : blocked ? (info.status === 'allowed_warning' ? '한도 임박' : '한도 도달') : `${Math.round(worst * 100)}%`}
      hint={!info
        ? (loading ? undefined : 'Claude 작업이 한 번 돌거나 위 "새로고침"을 누르면 현재 세션(5시간)·이번 주(7일) 사용률이 표시됩니다')
        : <>
            <Meter label="현재 세션 (5시간)" value={info.fiveHourUtilization} resetsAt={info.fiveHourResetsAt} now={now} />
            <Meter label="이번 주 (7일)" value={info.sevenDayUtilization} resetsAt={info.sevenDayResetsAt} now={now} />
            {info.usingOverage && <div className="mt-1 text-amber-700 dark:text-amber-300">플랜 한도를 넘어 추가 크레딧을 쓰는 중</div>}
            <div className="mt-1 text-slate-400">마지막 Claude 호출 기준 · {ageMin < 1 ? '방금' : `${ageMin}분 전`}{info.status && info.status !== 'allowed' ? ` · CLI 상태 ${info.status}` : ''}. 모델별 주간 한도는 CLI가 보고하지 않습니다.</div>
            {note && <div className="mt-1 text-rose-600 dark:text-rose-300">{note}</div>}
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

/** Buttons under the Claude tile: open a login terminal, re-probe now, jump to the executable setting. */
function ClaudeActions({ showLogin, showLogout, onDone }: { showLogin: boolean; showLogout: boolean; onDone: () => void }) {
  const qc = useQueryClient()
  const [note, setNote] = useState<string | null>(null)
  const [waiting, setWaiting] = useState(false)
  const onDoneRef = useRef(onDone)
  onDoneRef.current = onDone
  const login = useMutation({
    mutationFn: api.claudeLogin,
    onSuccess: (r) => { setNote(`터미널을 열었습니다 (${r.command}). 로그인을 마치면 여기가 자동으로 바뀝니다.`); setWaiting(true) },
    onError: (e) => setNote(errorMessage(e)),
  })
  // Belt and braces with the server's SSE push: poll the probe every 3s until the CLI reports a login (max 5 min).
  useEffect(() => {
    if (!waiting) return
    const started = Date.now()
    const timer = window.setInterval(async () => {
      try {
        const r = await api.refreshStatus()
        const c = r.modules.find((m) => m.name === 'claude')
        if (c?.loggedIn) { setWaiting(false); setNote(`로그인 확인됨 · ${c.authMethod ?? ''} ${c.version ?? ''}`.trim()); qc.invalidateQueries({ queryKey: ['dashboard'] }); onDoneRef.current() }
        else if (Date.now() - started > 5 * 60_000) { setWaiting(false); setNote('5분 동안 로그인이 확인되지 않았습니다. 터미널을 확인한 뒤 "다시 확인"을 누르세요.') }
      } catch { /* server hiccup: keep polling */ }
    }, 3000)
    return () => window.clearInterval(timer)
  }, [waiting, qc])
  const logout = useMutation({
    mutationFn: api.claudeLogout,
    onSuccess: (r) => { setNote(r.loggedOut ? '로그아웃했습니다. 이 PC의 터미널 Claude Code도 다시 로그인해야 합니다.' : `로그아웃 명령은 끝났지만 아직 로그인 상태로 보입니다: ${r.output}`); qc.invalidateQueries({ queryKey: ['dashboard'] }); onDoneRef.current() },
    onError: (e) => setNote(errorMessage(e)),
  })
  const confirmLogout = () => {
    if (window.confirm('Claude 로그인을 해제합니다.\n\n이 로그인은 Claude Code CLI의 것이라, 이 PC의 터미널에서 쓰는 claude도 함께 로그아웃되고 다시 /login 해야 합니다.\n\n계속할까요?')) logout.mutate()
  }
  const refresh = useMutation({
    mutationFn: api.refreshStatus,
    onSuccess: (r) => { const c = r.modules.find((m) => m.name === 'claude'); setNote(c?.available ? (c.loggedIn === false ? '아직 로그인되지 않았습니다.' : `확인됨: ${c.version ?? 'CLI'}${c.loggedIn ? ' · 로그인됨' : ''}`) : '여전히 CLI를 찾지 못했습니다.'); qc.invalidateQueries({ queryKey: ['dashboard'] }); qc.invalidateQueries({ queryKey: ['catalog'] }); onDone() },
    onError: (e) => setNote(errorMessage(e)),
  })
  const btn = 'inline-flex items-center gap-1 rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800'
  return (
    <div className="mt-2 flex flex-wrap items-center gap-1.5">
      {showLogin && <button onClick={() => login.mutate()} disabled={login.isPending || waiting} className={btn}><LogIn size={12} className={waiting ? 'animate-pulse' : ''} /> {waiting ? '로그인 대기 중…' : '로그인 창 열기'}</button>}
      {showLogout && <button onClick={confirmLogout} disabled={logout.isPending} className={btn}><LogOut size={12} /> {logout.isPending ? '로그아웃 중…' : '로그아웃'}</button>}
      <button onClick={() => refresh.mutate()} disabled={refresh.isPending} className={btn}><Recheck size={12} className={refresh.isPending ? 'animate-spin' : ''} /> 다시 확인</button>
      {note && <span className="w-full text-[11px] text-slate-500">{note}</span>}
    </div>
  )
}
