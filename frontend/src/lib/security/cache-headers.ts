/* ── 화면 응답의 캐시 정책 — 뒤로 가기 캐시(bfcache) (#186) ─────────────────────────────────────────
 *
 * `next.config.ts` 의 `headers()` 가 쓴다. 근거와 실측은 docs/conventions.md "뒤로 가기 캐시(bfcache)".
 *
 * - **문서는 Next 기본값(`no-store`)을 그대로 둔다.** Chrome 은 `no-store` 문서도 bfcache 에 넣지만, 떠난 뒤 그 오리진의 쿠키가
 *   바뀌면(로그아웃 · 만료가 세션 힌트 쿠키 `sc_session` 을 지운다) 되살리지 않는다. HTTP 캐시에도 문서가 남지 않는다
 * - **앱 안 이동 · 미리 받기(RSC, 요청 헤더 `rsc: 1`) 응답만 `private, no-cache` 로 바꾼다.** 스크립트 요청이 `no-store` 응답을 받으면
 *   Chrome 이 그 화면을 bfcache 에 넣지 않는다(`JsNetworkRequestReceivedCacheControlNoStoreResource`). RSC 응답은 회원별이 아니다 —
 *   세션 · 내 정보 · 이번 주 보고는 서버가 모르고 클라이언트 메모리에만 있다. `max-age` 는 주지 않아 쓸 때마다 서버에 다시 확인한다
 * - 카카오 콜백(`/login/kakao/callback`)은 RSC 요청도 `no-store` 다(문서와 같은 값)
 * - proxy 의 리다이렉트(첫 진입 307 · 콜백 303)는 proxy 가 붙인 `no-store` 가 이긴다 — 여기서 덮지 않는다
 *
 * `next.config.ts` 가 불러 빌드 설정을 읽을 때도 돌므로 이 파일은 다른 모듈(`@/` 별칭 포함)을 불러오지 않는다.
 */

/** Next 클라이언트가 RSC 요청(앱 안 이동 · 미리 받기)에 싣는 요청 헤더와 값 */
export const RSC_HEADER = { key: 'rsc', value: '1' } as const

/** RSC 응답. 개인 브라우저에만 저장하고 쓸 때마다 다시 확인한다 */
export const RSC_CACHE_CONTROL = 'private, no-cache'

/** 저장하지 않는다. Next 가 동적 문서에 붙이는 값과 같다(`next/dist/server/lib/cache-control.js`) */
export const NO_STORE_CACHE_CONTROL = 'private, no-cache, no-store, max-age=0, must-revalidate'
