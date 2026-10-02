import { readCookieValue } from '@/lib/data-source'

/* ── 세션 힌트 쿠키 `sc_session=1` ───────────────────────────────────────────────────────────────
 *
 * 새로고침하면 메모리의 access token 이 사라진다. refresh 쿠키는 게이트웨이 호스트 · `Path=/api/v1/auth` 전용 HttpOnly 라
 * 화면(웹 오리진)은 그것이 있는지 알 수 없다. 그래서 로그인 · 재발급에 성공한 브라우저에 1st-party 표시를 남기고, 앱을 열 때
 * 이 표시가 있을 때만 재발급을 한 번 해 본다 — 비회원은 쓸데없는 재발급 요청(늘 `AUTH_014`)을 보내지 않는다.
 *
 * 값은 고정값 `1` 뿐이다(토큰 · 회원 아이디 · 시각을 담지 않음). `Path=/` · `SameSite=Lax` · HTTPS 면 `Secure` · 14일.
 * 화면이 읽어야 해 `HttpOnly` 가 아니다. 표시가 남았는데 세션이 없으면 재발급이 `AUTH_014/015` 로 끝나 표시를 지운다.
 */

export const SESSION_HINT_COOKIE = 'sc_session'

/** 14일. 이 기간 안에 재발급(앱 열기 · 요청)이 있으면 다시 늘어난다 */
export const SESSION_HINT_MAX_AGE_SECONDS = 60 * 60 * 24 * 14

/** 이 브라우저에 세션이 있었다는 표시가 있는지. 서버(문서가 없음)에서는 false 다 */
export function hasSessionHint(): boolean {
  if (typeof document === 'undefined') return false
  return readCookieValue(document.cookie, SESSION_HINT_COOKIE) === '1'
}

/** 표시를 남기거나(`true`) 지운다(`false`). 서버에서는 아무것도 하지 않는다 */
export function writeSessionHint(on: boolean): void {
  if (typeof document === 'undefined') return
  const secure = window.location.protocol === 'https:' ? '; Secure' : ''
  const value = on ? `1; Max-Age=${SESSION_HINT_MAX_AGE_SECONDS}` : '; Max-Age=0'
  document.cookie = `${SESSION_HINT_COOKIE}=${value}; Path=/; SameSite=Lax${secure}`
}
