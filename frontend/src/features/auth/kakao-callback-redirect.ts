import { type NextRequest, NextResponse } from 'next/server'

import { KAKAO_CALLBACK_PATH } from '@/features/onboarding/paths'

/* ── 카카오 콜백의 쿼리를 fragment 로 옮긴다 (#167 · #176) ─────────────────────────────────────
 *
 * 카카오 인가 화면은 백엔드 `KAKAO_REDIRECT_URI` 대로 `/login/kakao/callback?code=…&state=…`(취소면 `?error=…`)로 문서를 연다.
 * 모든 화면이 동적 렌더링이 되면서(CSP nonce, #176) Next 는 요청 주소를 응답 HTML 의 RSC 페이로드(첫 주소 `"c"` · `"q"` ·
 * 페이지 세그먼트 키)에 싣는다 — 그대로 그리면 인가 코드가 문서 본문에 남는다. #167 은 정적 페이지라 본문에 없었다.
 *
 * proxy 의 rewrite 로는 막을 수 없다. app-render 는 첫 주소를 rewrite 와 무관하게 **원래 요청 주소**(`req.url`)로 만든다
 * (`next/dist/server/app-render/app-render.js` 의 `prepareInitialCanonicalUrl`). 그래서 쿼리를 fragment 로 옮겨 같은 경로로
 * **303** 리다이렉트한다. fragment 는 서버로 가지 않아 다시 받은 문서는 쿼리 없이 그려지고, 303 이라 `?code=` 주소는 방문 기록에
 * 남지 않는다. 화면은 hash 에서 값을 읽고 바로 지운다(`kakao-callback-screen.tsx`).
 *
 * 응답: `Cache-Control: no-store`(인가 코드가 든 Location 을 캐시하지 않음) · `Referrer-Policy: no-referrer`(next.config.ts 의 콜백 규칙과
 * 같은 값). CSP · 보안 헤더는 proxy · next.config.ts 가 그대로 붙인다.
 *
 * 회귀 확인: 프로덕션 빌드 뒤 `curl -s -i '<웹>/login/kakao/callback?code=SECRETCODE123&state=S'` 가 303 이고 본문에 값이 없는지
 * (docs/conventions.md "보안 헤더 · CSP" 의 "카카오 콜백").
 */

/** 콜백에 쿼리가 붙은 GET 이면 fragment 로 옮겨 보내는 응답, 아니면 null */
export function kakaoCallbackRedirect(request: NextRequest): NextResponse | null {
  if (request.method !== 'GET') return null
  const { pathname, search } = request.nextUrl
  if (pathname !== KAKAO_CALLBACK_PATH || search === '') return null

  const target = request.nextUrl.clone()
  target.search = ''
  // 쿼리를 fragment 로 옮긴다. Next 가 Location 을 다시 인코딩할 수 있어 바이트는 달라질 수 있지만,
  // 화면이 URLSearchParams 로 읽는 키 · 값은 같다
  target.hash = search.slice(1)
  const response = NextResponse.redirect(target, 303)
  response.headers.set('Cache-Control', 'no-store')
  response.headers.set('Referrer-Policy', 'no-referrer')
  return response
}
