import type { NextRequest, NextResponse } from 'next/server'

/* ── Content-Security-Policy (#176) ──────────────────────────────────────────────────────────────
 *
 * 루트 `proxy.ts` 가 화면 요청마다 nonce 를 새로 만들어 이 정책을 **요청 헤더**(Next 가 렌더링할 때 nonce 를 읽어 자기 스크립트에
 * 붙인다)와 **응답 헤더**(브라우저가 지킨다)에 싣는다. 방식 · 근거 · 새 출처를 쓸 때 고칠 곳은 docs/conventions.md "보안 헤더 · CSP".
 *
 * - nonce 방식이라 화면은 모두 동적 렌더링이다(루트 레이아웃의 `connection()`). 해시(SRI, `experimental.sri`)는 Next 가 문서에 넣는
 *   인라인 스크립트(`self.__next_f.push(…)` RSC 페이로드)를 덮지 못해 하이드레이션이 깨진다(프로덕션 빌드 + 헤드리스 Chrome 으로 확인).
 * - 스크립트에는 `'unsafe-inline'` 이 없다. 개발 모드만 `'unsafe-eval'`(React 가 서버 오류 스택을 eval 로 되살린다)을 더한다.
 */

export const CSP_HEADER = 'Content-Security-Policy'

/** 정책을 고르는 환경값. proxy 가 `process.env.NODE_ENV` · `clientEnv` 로 채운다 */
export type CspEnv = {
  /** `next dev` 인지 */
  dev: boolean
  /** 게이트웨이 주소(`clientEnv.apiBaseUrl`). 오리진만 connect-src 에 싣는다 */
  apiBaseUrl: string
  /** 사이트 주소(`clientEnv.siteUrl`). https 일 때만 upgrade-insecure-requests 를 켠다 */
  siteUrl: string
}

export type CspOptions = {
  nonce: string
  dev: boolean
  /** connect-src 에 더할 게이트웨이 오리진. null 이면 `'self'` 만 */
  apiOrigin: string | null
  upgradeInsecureRequests: boolean
}

/** 오리진에 올 수 있는 모양. 호스트에 CSP 구분자(`;`) 같은 글자가 들면 정책에 지시어를 끼워 넣을 수 있어 받지 않는다 */
const SAFE_ORIGIN = /^https?:\/\/(?:[a-z0-9.-]+|\[[0-9a-f:.]+\])(?::\d+)?$/i

/**
 * http(s) 주소의 오리진(`https://api.sneezecast.com`). 주소가 아니거나 http(s) 가 아니면 null.
 *
 * null 이면 connect-src 는 `'self'` 만 남는다 — 빌드 · 화면은 깨지지 않고 게이트웨이 요청만 CSP 에 막힌다. 그런 값이면 API 계층
 * (`src/lib/api/client.ts`)도 주소를 만들지 못하므로 막히는 요청이 원래 성공할 수 없었다. 값은 빌드 때 정해져
 * (`NEXT_PUBLIC_API_BASE_URL`) 배포 전에 `.env.example` 대로 넣는다.
 */
export function httpOrigin(value: string): string | null {
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null
  return SAFE_ORIGIN.test(url.origin) ? url.origin : null
}

/**
 * 정책 문자열. 지시어마다 근거:
 * - `script-src`: `'self'` 는 `'strict-dynamic'` 을 모르는 브라우저(CSP 2)가 같은 오리진 청크를 받게 둔다. 아는 브라우저는 `'self'` 를
 *   무시하고 nonce 가 붙은 스크립트와 그 스크립트가 넣은 스크립트(Next 의 청크 로더)만 돌린다.
 * - `style-src 'unsafe-inline'`: 서버가 그린 `style` 속성(next/image `fill` · 진행 막대 너비 · 증상 추이 높이 · 카카오 버튼 색)이 있다.
 *   nonce · 해시는 `style` 속성을 덮지 못하고, `style-src` 에 nonce 가 있으면 `'unsafe-inline'` 이 무시된다. CSS 로 값을 빼 가는 길
 *   (외부 이미지 · 글꼴 · 요청)은 img-src · font-src · connect-src 가 막는다.
 * - `img-src` · `font-src 'self'`: 이미지는 같은 오리진 파일 · 아이콘 라우트, 글꼴은 번들된 Pretendard 뿐이다(`data:` · `blob:` 없음).
 * - `worker-src 'self'`: 서비스 워커(2단계 푸시)를 같은 오리진에서 등록하게 미리 둔다. 없으면 script-src 로 떨어져
 *   `'strict-dynamic'` 이 `'self'` 를 무시하고 nonce 는 워커에 붙지 않아 등록이 막힌다.
 * - `form-action 'self'`: 폼은 화면 안에서 `fetch` 로 보낸다. 카카오 인가 이동은 `location.assign`(문서 이동)이라 CSP 대상이 아니다.
 * - `upgrade-insecure-requests`: 사이트 주소가 https 인 빌드(dev · 운영 웹)에서만. 로컬 http(`next start`)에서 켜면 브라우저에 따라
 *   (Safari 등) 같은 오리진 http 요청까지 https 로 올려 청크를 받지 못할 수 있다. https 배포에서는 바꿀 http 요청이 없어 영향이 없다.
 */
export function buildContentSecurityPolicy({
  nonce,
  dev,
  apiOrigin,
  upgradeInsecureRequests,
}: CspOptions): string {
  const directives: string[][] = [
    ['default-src', "'self'"],
    [
      'script-src',
      "'self'",
      `'nonce-${nonce}'`,
      "'strict-dynamic'",
      ...(dev ? ["'unsafe-eval'"] : []),
    ],
    ['style-src', "'self'", "'unsafe-inline'"],
    ['img-src', "'self'"],
    ['font-src', "'self'"],
    ['connect-src', "'self'", ...(apiOrigin ? [apiOrigin] : [])],
    ['manifest-src', "'self'"],
    ['worker-src', "'self'"],
    ['object-src', "'none'"],
    ['base-uri', "'self'"],
    ['form-action', "'self'"],
    ['frame-ancestors', "'none'"],
    ...(upgradeInsecureRequests ? [['upgrade-insecure-requests']] : []),
  ]
  return directives.map((directive) => directive.join(' ')).join('; ')
}

/** 128비트 무작위 nonce(base64). 요청마다 새로 만든다 — 맞힐 수 있으면 공격 스크립트가 nonce 를 달고 들어온다 */
export function createNonce(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return btoa(String.fromCharCode(...bytes))
}

/** 환경값으로 이 요청의 정책을 만든다. nonce 를 주지 않으면 새로 만든다 */
export function contentSecurityPolicy(env: CspEnv, nonce: string = createNonce()): string {
  return buildContentSecurityPolicy({
    nonce,
    dev: env.dev,
    apiOrigin: httpOrigin(env.apiBaseUrl),
    upgradeInsecureRequests: !env.dev && httpOrigin(env.siteUrl)?.startsWith('https:') === true,
  })
}

/** `NextResponse.next(init)` 에 넘길 값 — 덮어쓴 요청 헤더 */
export type ProxyNextInit = { request: { headers: Headers } }

/**
 * 정책을 요청 헤더에 실어 `respond` 로 응답을 만들고, 그 응답 헤더에도 싣는다.
 *
 * 브라우저가 보낸 `Content-Security-Policy` 요청 헤더는 덮어쓴다 — 남기면 Next 가 그 안의(공격자가 아는) nonce 를 스크립트에 붙인다.
 * `respond` 가 리다이렉트를 돌려주면 `init` 은 쓰이지 않고 응답 헤더만 붙는다.
 */
export function withContentSecurityPolicy(
  request: NextRequest,
  policy: string,
  respond: (init: ProxyNextInit) => NextResponse,
): NextResponse {
  const headers = new Headers(request.headers)
  headers.set(CSP_HEADER, policy)
  const response = respond({ request: { headers } })
  response.headers.set(CSP_HEADER, policy)
  return response
}
