/** Turns server/mock error messages (English, technical) into the Korean the UI shows. */
export function humanize(message: string): string {
  let m: RegExpMatchArray | null
  if ((m = message.match(/^Unknown (?:flow|preset): (.+)$/))) return `알 수 없는 프리셋 '${m[1]}'. 구성 화면에서 만들거나 다른 프리셋을 고르세요.`
  if ((m = message.match(/^Unknown module: (.+)$/))) return `알 수 없는 모듈 '${m[1]}'. 구성 화면의 모델 블록(claude, codex)만 쓸 수 있습니다.`
  if ((m = message.match(/^Unknown option: (.+)$/))) return `알 수 없는 옵션 ${m[1]}. 사용할 수 있는 옵션: --focus, --language, --preset`
  if ((m = message.match(/^(\S+) needs a value$/))) return `${m[1]} 뒤에 값이 필요합니다`
  if (/^(Failed to fetch|NetworkError|Load failed)/.test(message)) return '서버에 연결할 수 없습니다. 서버가 실행 중인지 확인하세요.'
  if (/^404\b/.test(message)) return '찾을 수 없습니다 (삭제됐거나 다른 데이터 디렉터리일 수 있음)'
  if (/^401\b/.test(message)) return '인증 토큰이 맞지 않습니다. 페이지를 새로고침하세요.'
  if (/^403\b/.test(message)) return '허용되지 않은 요청입니다 (Origin/Host 검사에 걸림)'
  if (/^5\d\d\b/.test(message)) return `서버 오류 (${message})`
  return message
}

export function errorMessage(e: unknown): string {
  return humanize(e instanceof Error ? e.message : String(e))
}
