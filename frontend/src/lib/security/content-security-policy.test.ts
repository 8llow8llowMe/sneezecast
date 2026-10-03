import { NextRequest, NextResponse } from 'next/server'

import { describe, expect, it } from 'vitest'

import {
  buildContentSecurityPolicy,
  contentSecurityPolicy,
  createNonce,
  CSP_HEADER,
  type CspEnv,
  httpOrigin,
  withContentSecurityPolicy,
} from './content-security-policy'

/** 지시어 이름 → 값 목록. 순서 · 공백에 기대지 않고 지시어 단위로 맞춰 본다 */
function directives(policy: string): Map<string, string[]> {
  const map = new Map<string, string[]>()
  for (const part of policy.split(';')) {
    const [name, ...values] = part.trim().split(/\s+/)
    if (name) map.set(name, values)
  }
  return map
}

const NONCE = 'bm9uY2Utdmx1ZQ=='
const PROD_ENV: CspEnv = {
  dev: false,
  apiBaseUrl: 'https://api.sneezecast.com',
  siteUrl: 'https://www.sneezecast.com',
}

describe('httpOrigin — connect-src 에 실을 게이트웨이 오리진', () => {
  it('경로 · 끝의 / 를 떼고 오리진만 남긴다', () => {
    expect(httpOrigin('https://api-dev.sneezecast.com')).toBe('https://api-dev.sneezecast.com')
    expect(httpOrigin('https://api.sneezecast.com/base/')).toBe('https://api.sneezecast.com')
    expect(httpOrigin('http://localhost:8080/api')).toBe('http://localhost:8080')
  })

  it('http(s) 가 아니거나 주소가 아니면 null — connect-src 는 self 만 남는다', () => {
    expect(httpOrigin('')).toBeNull()
    expect(httpOrigin('api.sneezecast.com')).toBeNull()
    expect(httpOrigin('javascript:alert(1)')).toBeNull()
    expect(httpOrigin('ftp://files.sneezecast.com')).toBeNull()
  })

  it('CSP 구분자(;) · 공백이 든 호스트는 받지 않는다 — 지시어를 끼워 넣지 못하게', () => {
    expect(httpOrigin("https://a;script-src 'unsafe-inline'")).toBeNull()
    expect(httpOrigin('https://a;b.example.com')).toBeNull()
  })
})

describe('buildContentSecurityPolicy', () => {
  it('운영: nonce + strict-dynamic, unsafe-inline · unsafe-eval 이 스크립트에 없다', () => {
    const policy = directives(
      buildContentSecurityPolicy({
        nonce: NONCE,
        dev: false,
        apiOrigin: 'https://api.sneezecast.com',
        upgradeInsecureRequests: true,
      }),
    )
    expect(policy.get('default-src')).toEqual(["'self'"])
    expect(policy.get('script-src')).toEqual(["'self'", `'nonce-${NONCE}'`, "'strict-dynamic'"])
    expect(policy.get('style-src')).toEqual(["'self'", "'unsafe-inline'"])
    expect(policy.get('img-src')).toEqual(["'self'"])
    expect(policy.get('font-src')).toEqual(["'self'"])
    expect(policy.get('connect-src')).toEqual(["'self'", 'https://api.sneezecast.com'])
    expect(policy.get('manifest-src')).toEqual(["'self'"])
    expect(policy.get('worker-src')).toEqual(["'self'"])
    expect(policy.get('object-src')).toEqual(["'none'"])
    expect(policy.get('base-uri')).toEqual(["'self'"])
    expect(policy.get('form-action')).toEqual(["'self'"])
    expect(policy.get('frame-ancestors')).toEqual(["'none'"])
    expect(policy.get('upgrade-insecure-requests')).toEqual([])
  })

  it("개발 모드만 script-src 에 'unsafe-eval' 을 더한다", () => {
    const policy = directives(
      buildContentSecurityPolicy({
        nonce: NONCE,
        dev: true,
        apiOrigin: null,
        upgradeInsecureRequests: false,
      }),
    )
    expect(policy.get('script-src')).toEqual([
      "'self'",
      `'nonce-${NONCE}'`,
      "'strict-dynamic'",
      "'unsafe-eval'",
    ])
  })

  it('게이트웨이 오리진이 없으면 connect-src 는 self 만, upgrade-insecure-requests 는 끌 수 있다', () => {
    const policy = directives(
      buildContentSecurityPolicy({
        nonce: NONCE,
        dev: false,
        apiOrigin: null,
        upgradeInsecureRequests: false,
      }),
    )
    expect(policy.get('connect-src')).toEqual(["'self'"])
    expect(policy.has('upgrade-insecure-requests')).toBe(false)
  })

  it('Next 가 읽는 모양(script-src 의 첫 nonce)으로 nonce 를 싣는다', () => {
    const policy = buildContentSecurityPolicy({
      nonce: NONCE,
      dev: false,
      apiOrigin: null,
      upgradeInsecureRequests: false,
    })
    // next/dist/server/app-render/get-script-nonce-from-header 와 같은 규칙
    const scriptSrc = policy.split(';').find((d) => d.trim().startsWith('script-src'))
    expect(scriptSrc?.match(/'nonce-([A-Za-z0-9+/_-]+={0,2})'/)?.[1]).toBe(NONCE)
  })
})

describe('contentSecurityPolicy — 환경값에서 정책 만들기', () => {
  it('https 사이트 운영 빌드면 upgrade-insecure-requests 를 켜고 게이트웨이 오리진을 싣는다', () => {
    const policy = directives(contentSecurityPolicy(PROD_ENV, NONCE))
    expect(policy.has('upgrade-insecure-requests')).toBe(true)
    expect(policy.get('connect-src')).toEqual(["'self'", 'https://api.sneezecast.com'])
  })

  it('로컬 http 사이트(next start)면 upgrade-insecure-requests 를 넣지 않는다', () => {
    const policy = directives(
      contentSecurityPolicy({ ...PROD_ENV, siteUrl: 'http://localhost:3000' }, NONCE),
    )
    expect(policy.has('upgrade-insecure-requests')).toBe(false)
  })

  it('개발 모드는 사이트가 https 여도 upgrade-insecure-requests 를 넣지 않는다', () => {
    const policy = directives(contentSecurityPolicy({ ...PROD_ENV, dev: true }, NONCE))
    expect(policy.has('upgrade-insecure-requests')).toBe(false)
  })

  it('게이트웨이 주소가 주소가 아니면 connect-src 는 self 만 둔다(빌드 · 요청은 깨지지 않음)', () => {
    const policy = directives(
      contentSecurityPolicy({ ...PROD_ENV, apiBaseUrl: 'not a url' }, NONCE),
    )
    expect(policy.get('connect-src')).toEqual(["'self'"])
  })

  it('nonce 를 주지 않으면 요청마다 새로 만든다', () => {
    const a = directives(contentSecurityPolicy(PROD_ENV)).get('script-src')
    const b = directives(contentSecurityPolicy(PROD_ENV)).get('script-src')
    expect(a?.[1]).not.toBe(b?.[1])
  })
})

describe('createNonce', () => {
  it('128비트 무작위 값을 base64 로 — 요청마다 다르다', () => {
    const nonces = new Set(Array.from({ length: 50 }, () => createNonce()))
    expect(nonces.size).toBe(50)
    for (const nonce of nonces) {
      expect(nonce).toMatch(/^[A-Za-z0-9+/]{22}==$/)
      expect(Buffer.from(nonce, 'base64')).toHaveLength(16)
    }
  })
})

describe('withContentSecurityPolicy', () => {
  const POLICY = "default-src 'self'; script-src 'self' 'nonce-abc' 'strict-dynamic'"

  it('요청 헤더(Next 가 nonce 를 읽는 곳)와 응답 헤더에 같은 정책을 싣는다', () => {
    const request = new NextRequest('http://localhost:3000/me')
    let seen: Headers | undefined
    const response = withContentSecurityPolicy(request, POLICY, (init) => {
      seen = init.request?.headers
      return NextResponse.next(init)
    })
    expect(seen?.get(CSP_HEADER)).toBe(POLICY)
    expect(response.headers.get(CSP_HEADER)).toBe(POLICY)
    // NextResponse.next({ request }) 가 렌더링에 넘기는 덮어쓴 요청 헤더
    expect(response.headers.get('x-middleware-request-content-security-policy')).toBe(POLICY)
  })

  it('브라우저가 보낸 CSP 요청 헤더(알려진 nonce)는 덮어써 버린다', () => {
    const request = new NextRequest('http://localhost:3000/', {
      headers: { 'content-security-policy': "script-src 'nonce-attacker'" },
    })
    let seen: Headers | undefined
    withContentSecurityPolicy(request, POLICY, (init) => {
      seen = init.request?.headers
      return NextResponse.next(init)
    })
    expect(seen?.get(CSP_HEADER)).toBe(POLICY)
  })

  it('원래 요청 헤더는 그대로 넘긴다', () => {
    const request = new NextRequest('http://localhost:3000/', {
      headers: { cookie: 'sc_visited=1', 'sec-fetch-dest': 'document' },
    })
    let seen: Headers | undefined
    withContentSecurityPolicy(request, POLICY, (init) => {
      seen = init.request?.headers
      return NextResponse.next(init)
    })
    expect(seen?.get('cookie')).toBe('sc_visited=1')
    expect(seen?.get('sec-fetch-dest')).toBe('document')
  })

  it('리다이렉트 응답에도 정책을 싣는다', () => {
    const request = new NextRequest('http://localhost:3000/')
    const response = withContentSecurityPolicy(request, POLICY, () =>
      NextResponse.redirect(new URL('/start', request.url)),
    )
    expect(response.status).toBe(307)
    expect(response.headers.get(CSP_HEADER)).toBe(POLICY)
  })
})
