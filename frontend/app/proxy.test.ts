import { NextRequest } from 'next/server'

import { describe, expect, it } from 'vitest'

import { VISITED_COOKIE } from '@/features/onboarding/first-visit'
import { KAKAO_CALLBACK_PATH } from '@/features/onboarding/paths'

import { proxy } from '../proxy'

const ORIGIN = 'http://localhost:3000'

function nonceOf(policy: string | null): string | undefined {
  return policy?.match(/script-src [^;]*'nonce-([^']+)'/)?.[1]
}

describe('proxy — CSP + 첫 진입', () => {
  it('화면 요청의 응답과 렌더링에 넘기는 요청 헤더에 같은 CSP 를 싣는다', () => {
    const response = proxy(
      new NextRequest(new URL('/me', ORIGIN), { headers: { cookie: `${VISITED_COOKIE}=1` } }),
    )
    const policy = response.headers.get('content-security-policy')
    expect(nonceOf(policy)).toBeTruthy()
    expect(response.headers.get('x-middleware-request-content-security-policy')).toBe(policy)
  })

  it('요청마다 nonce 가 다르다', () => {
    const make = () =>
      proxy(new NextRequest(new URL('/', ORIGIN), { headers: { cookie: `${VISITED_COOKIE}=1` } }))
    const a = nonceOf(make().headers.get('content-security-policy'))
    const b = nonceOf(make().headers.get('content-security-policy'))
    expect(a).toBeTruthy()
    expect(a).not.toBe(b)
  })

  it('테스트 환경(개발 모드 아님)에서는 unsafe-eval 이 없고 게이트웨이 오리진이 connect-src 에 있다', () => {
    const policy = proxy(new NextRequest(new URL('/login', ORIGIN))).headers.get(
      'content-security-policy',
    )
    expect(policy).not.toContain("'unsafe-eval'")
    expect(policy).not.toMatch(/script-src[^;]*'unsafe-inline'/)
    expect(policy).toMatch(/connect-src 'self' https?:\/\/[^\s;]+/)
  })

  it('처음 온 사람을 시작 화면으로 보내는 응답에도 CSP 와 방문 표시가 있다', () => {
    const response = proxy(
      new NextRequest(new URL('/', ORIGIN), { headers: { 'sec-fetch-dest': 'document' } }),
    )
    expect(response.status).toBe(307)
    expect(response.headers.get('location')).toBe(`${ORIGIN}/start`)
    expect(nonceOf(response.headers.get('content-security-policy'))).toBeTruthy()
    expect(response.cookies.get(VISITED_COOKIE)?.value).toBe('1')
  })

  describe('카카오 콜백 — 쿼리를 fragment 로 옮겨 303', () => {
    it.each([
      ['다시 온 사람', { cookie: `${VISITED_COOKIE}=1`, 'sec-fetch-dest': 'document' }],
      ['처음 온 사람', { 'sec-fetch-dest': 'document' }],
    ])('%s 도 시작 화면이 아니라 같은 경로의 fragment 로 보내고 CSP 를 싣는다', (_, headers) => {
      const response = proxy(
        new NextRequest(new URL(`${KAKAO_CALLBACK_PATH}?code=SECRET&state=S`, ORIGIN), { headers }),
      )
      expect(response.status).toBe(303)
      expect(response.headers.get('location')).toBe(
        `${ORIGIN}${KAKAO_CALLBACK_PATH}#code=SECRET&state=S`,
      )
      expect(response.headers.get('cache-control')).toBe('no-store')
      expect(response.headers.get('referrer-policy')).toBe('no-referrer')
      expect(nonceOf(response.headers.get('content-security-policy'))).toBeTruthy()
    })

    it('쿼리가 없는 콜백은 화면으로 넘기고 처음 온 사람이면 방문 표시를 심는다', () => {
      const response = proxy(
        new NextRequest(new URL(KAKAO_CALLBACK_PATH, ORIGIN), {
          headers: { 'sec-fetch-dest': 'document' },
        }),
      )
      expect(response.headers.get('location')).toBeNull()
      expect(response.headers.get('x-middleware-next')).toBe('1')
      expect(response.cookies.get(VISITED_COOKIE)?.value).toBe('1')
    })

    it('다른 화면의 쿼리는 건드리지 않는다', () => {
      const response = proxy(
        new NextRequest(new URL('/login?code=x', ORIGIN), {
          headers: { cookie: `${VISITED_COOKIE}=1` },
        }),
      )
      expect(response.headers.get('location')).toBeNull()
      expect(response.headers.get('x-middleware-next')).toBe('1')
    })
  })
})
