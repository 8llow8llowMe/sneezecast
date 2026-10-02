import { type NextRequest, NextResponse } from 'next/server'

import { HOME_PATH, START_PATH } from './paths'

/* ── 처음 온 사람은 시작 화면으로 (#127) ─────────────────────────────────────────────────────────
 *
 * 루트 `proxy.ts`(Next 16 의 미들웨어)가 부른다. 화면을 그리기 전에 판단해 첫 그림이 홈으로 깜빡이지 않는다.
 *
 * **처음 온 사람 = 이 브라우저에서 서비스 화면을 한 번도 연 적이 없는 사람**이다. 판정은 1st-party 쿠키 `sc_visited=1` 로 한다.
 * - 브라우저 저장소(`localStorage` 등)는 린트로 막혀 있고, 서버(proxy)가 읽지 못해 깜빡임 없이 보낼 수 없다.
 * - 값은 고정값 `1` 이다. 개인을 알아보는 값 · 방문 시각 · 경로를 담지 않고 다른 곳으로 보내지 않는다(개인정보가 아니다).
 * - 서버만 읽으므로 `HttpOnly` 다. `Path=/` · `SameSite=Lax` · 1년(`Max-Age`) · HTTPS 면 `Secure`.
 *
 * **표시하는 때: 쿠키 없이 화면을 처음 열 때(어느 화면이든)** 응답에 쿠키를 심는다. 시작 화면으로 보내는 응답에도 심어 시작 화면은 한 번만 본다.
 * 시작 화면에서 버튼을 고를 때 표시하는 방식은 고르지 않았다 — 버튼을 고르지 않고 나가면 `/` 를 열 때마다 시작 화면으로 되돌아가고,
 * 쿠키를 쓰는 클라이언트 코드가 따로 필요하다. 공유 링크로 하위 화면을 먼저 연 사람은 그때 이미 서비스를 봤으므로 처음 온 사람이 아니다.
 * 회원은 로그인 · 가입 화면을 열면서 이미 표시된다(목 세션은 서버가 모르지만 이 순서로 "회원이면 쿠키가 있다" 가 된다).
 *
 * **보내는 때: 쿠키가 없고, 홈(`/`)을 쿼리 없이 문서로 열 때만**이다.
 * - 공유 링크로 바로 연 하위 화면(`/notice/…` · `/official` · `/map` …)은 보내지 않는다.
 * - 쿼리가 있으면 보내지 않는다 — 둘러보기 동네 `?region=` · 시트 `?report=` · `?explain=` 같은 바로가기와 QA 덮어쓰기
 *   (`?mock-auth=` · `?mock=` …)다. 시작 화면으로 보내면 그 값을 잃는다.
 * - 문서 요청(`Sec-Fetch-Dest: document`)만 보낸다. 앱 안 이동(RSC 요청 · 미리 받기)은 보내지 않는다 — 쿠키를 막은 브라우저도
 *   시작 화면의 둘러보기 · 로그인 뒤 홈에 들어갈 수 있다. 이 헤더가 없는 클라이언트(오래된 브라우저 · curl)는 문서 요청으로 본다.
 *   (proxy 는 Next 내부 헤더 `rsc` 를 지운 요청을 받아 그 헤더로는 가릴 수 없다)
 * - GET 만 본다.
 *
 * **연동 요구사항**: 실제 세션 쿠키가 생기면 세션이 있는 요청도 보내지 않는다(쿠키를 지운 뒤 다시 로그인한 경우의 안전장치).
 */

export const VISITED_COOKIE = 'sc_visited'
export const VISITED_VALUE = '1'
/** 1년. 브라우저 상한(400일) 안이다. 쿠키가 없을 때만 심으므로 1년 뒤에는 시작 화면을 다시 한 번 본다 */
export const VISITED_MAX_AGE_SECONDS = 60 * 60 * 24 * 365

/** 쿠키 없이 홈을 쿼리 없이 문서로 열었는지 — 시작 화면으로 보낼 요청인지 */
export function shouldSendToStart(request: NextRequest): boolean {
  if (request.method !== 'GET' || request.cookies.has(VISITED_COOKIE)) return false
  const { pathname, search } = request.nextUrl
  if (pathname !== HOME_PATH || search !== '') return false
  const dest = request.headers.get('sec-fetch-dest')
  return dest === null || dest === 'document'
}

function isHttps(request: NextRequest): boolean {
  return (
    request.nextUrl.protocol === 'https:' || request.headers.get('x-forwarded-proto') === 'https'
  )
}

/**
 * 처음 온 사람의 홈 요청은 시작 화면으로 보내고, 쿠키가 없는 응답에는 방문 표시를 심는다.
 * 쿠키가 이미 있으면 아무것도 바꾸지 않는다.
 */
export function firstVisit(request: NextRequest): NextResponse {
  if (request.cookies.has(VISITED_COOKIE)) return NextResponse.next()

  const toStart = shouldSendToStart(request)
  const response = toStart
    ? NextResponse.redirect(new URL(START_PATH, request.url))
    : NextResponse.next()
  if (toStart) {
    // 쿠키에 따라 갈리는 응답이라 중간 캐시가 다른 사람에게 다시 쓰지 않게 한다
    response.headers.set('Cache-Control', 'private, no-store')
  }
  response.cookies.set({
    name: VISITED_COOKIE,
    value: VISITED_VALUE,
    path: '/',
    maxAge: VISITED_MAX_AGE_SECONDS,
    sameSite: 'lax',
    httpOnly: true,
    secure: isHttps(request),
  })
  return response
}
