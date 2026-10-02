/**
 * access token 공급자를 끼우는 자리.
 *
 * access token 은 **메모리에만** 둔다 — 브라우저 저장소(`localStorage` · `sessionStorage`)에 두지 않는다. refresh 토큰은
 * HttpOnly 쿠키라 화면이 다루지 않는다(backend/docs/modules.md "화면 계약").
 *
 * 이 모듈은 토큰을 들지 않고 "토큰을 줄 함수" 하나만 기억한다. 세션 저장소(로그인 응답의 토큰 · 만료 시각을 들고 만료 전에
 * 재발급하는 쪽)는 다음 연동 이슈에서 만들고, 그쪽이 `setAccessTokenProvider` 로 자신을 끼운다. 공급자가 없거나 null 을
 * 주면 `Authorization` 없이 보낸다(비로그인 둘러보기).
 */

/** 지금 쓸 access token. 없으면 null. 만료 전 재발급을 기다려야 하면 Promise 를 돌려준다 */
export type AccessTokenProvider = () => string | null | Promise<string | null>

let provider: AccessTokenProvider | null = null

/** 공급자를 끼운다. null 이면 뺀다(로그아웃 · 테스트 정리) */
export function setAccessTokenProvider(next: AccessTokenProvider | null): void {
  provider = next
}

/** API 계층(`client.ts`)이 요청마다 부른다. 공급자가 던진 오류는 삼키지 않고 호출한 쪽으로 넘긴다 */
export async function resolveAccessToken(): Promise<string | null> {
  if (!provider) return null
  const token = await provider()
  return token ? token : null
}
