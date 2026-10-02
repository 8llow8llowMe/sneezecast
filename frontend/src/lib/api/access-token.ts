/**
 * access token 공급자 · 갈아 끼우기를 끼우는 자리.
 *
 * access token 은 **메모리에만** 둔다 — 브라우저 저장소(`localStorage` · `sessionStorage`)에 두지 않는다. refresh 토큰은
 * HttpOnly 쿠키라 화면이 다루지 않는다(backend/docs/modules.md "화면 계약").
 *
 * 이 모듈은 토큰을 들지 않고 "토큰을 줄 함수" 와 "거절된 토큰을 갈아 끼울 함수" 만 기억한다. 세션 저장소(끼우는 쪽)가
 * 자신을 끼운다. 공급자가 없거나 null 을 주면 `Authorization` 없이 보낸다(비로그인 둘러보기).
 */

/** 지금 쓸 access token. 없으면 null. 만료 전 재발급을 기다려야 하면 Promise 를 돌려준다 */
export type AccessTokenProvider = () => string | null | Promise<string | null>

/**
 * 서버가 거절한 토큰(`rejected`, 401 `reissue` 갈래)을 갈아 끼운다. 새 토큰을 주면 API 계층이 같은 요청을 한 번 다시 보낸다.
 * 이미 다른 요청이 갈아 끼웠으면 재발급 없이 지금 토큰을, 재발급하지 못하면 null 을 준다
 */
export type AccessTokenRefresher = (rejected: string) => Promise<string | null>

let provider: AccessTokenProvider | null = null
let refresher: AccessTokenRefresher | null = null

/** 공급자를 끼운다. null 이면 뺀다(세션 저장소 해제 · 테스트 정리) */
export function setAccessTokenProvider(next: AccessTokenProvider | null): void {
  provider = next
}

/** 갈아 끼우기를 끼운다. null 이면 뺀다 — 없으면 401 을 다시 보내지 않고 그대로 던진다 */
export function setAccessTokenRefresher(next: AccessTokenRefresher | null): void {
  refresher = next
}

/** API 계층(`client.ts`)이 요청마다 부른다. 공급자가 던진 오류는 삼키지 않고 호출한 쪽으로 넘긴다 */
export async function resolveAccessToken(): Promise<string | null> {
  if (!provider) return null
  const token = await provider()
  return token ? token : null
}

/** API 계층이 401(`reissue`)을 받았을 때 한 번 부른다. 갈아 끼우기가 없으면 null 이다 */
export async function refreshRejectedAccessToken(rejected: string): Promise<string | null> {
  if (!refresher) return null
  const token = await refresher(rejected)
  return token ? token : null
}
