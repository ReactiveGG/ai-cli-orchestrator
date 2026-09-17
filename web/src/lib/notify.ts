import type { Job } from './types'

/**
 * Desktop notifications for finished jobs. Off until the user turns them on
 * (the permission prompt only appears from that click, never on page load),
 * remembered per browser in localStorage.
 */
export type NotifyState = 'unsupported' | 'blocked' | 'off' | 'on'
const KEY = 'orchestrator.notify'

function wanted(): boolean {
  try { return localStorage.getItem(KEY) === '1' } catch { return false }
}
function remember(on: boolean) {
  try { localStorage.setItem(KEY, on ? '1' : '0') } catch { /* private window: forget on close */ }
}

export function notifyState(): NotifyState {
  if (typeof Notification === 'undefined') return 'unsupported'
  if (Notification.permission === 'denied') return 'blocked'
  return wanted() && Notification.permission === 'granted' ? 'on' : 'off'
}

/** Call from a click handler: flips the preference, asking for permission when needed. */
export async function toggleNotify(): Promise<NotifyState> {
  const state = notifyState()
  if (state === 'unsupported' || state === 'blocked') return state
  if (state === 'on') { remember(false); return 'off' }
  if (Notification.permission !== 'granted') {
    const p = await Notification.requestPermission().catch(() => 'denied' as NotificationPermission)
    if (p !== 'granted') return notifyState()
  }
  remember(true)
  return 'on'
}

export const notifyLabel: Record<NotifyState, string> = {
  unsupported: '이 브라우저는 알림을 지원하지 않습니다',
  blocked: '알림이 브라우저에서 차단됨 · 주소창 자물쇠 아이콘에서 허용',
  off: '작업이 끝나면 데스크톱 알림 받기',
  on: '완료 알림 켜짐 · 클릭하면 끔',
}

const titles: Partial<Record<Job['status'], string>> = { SUCCEEDED: '작업 완료', FAILED: '작업 실패', TIMEOUT: '작업 시간 초과', CANCELLED: '작업 취소됨' }

export function notifyJob(job: Job) {
  if (notifyState() !== 'on') return
  const title = titles[job.status]
  if (!title) return
  try {
    const n = new Notification(title, { body: job.command, tag: job.id })
    n.onclick = () => { window.focus(); window.location.hash = `jobs/${job.id}`; n.close() }
  } catch { /* notifications unsupported in this context (e.g. non-secure origin) */ }
}
