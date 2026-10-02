import { unstable_doesMiddlewareMatch } from 'next/experimental/testing/server'
import { NextRequest } from 'next/server'

import { describe, expect, it } from 'vitest'

import { config } from '../../../proxy'
import { firstVisit, shouldSendToStart, VISITED_COOKIE } from './first-visit'

const ORIGIN = 'http://localhost:3000'

function request(
  path: string,
  {
    visited = false,
    dest,
    method = 'GET',
    headers = {},
  }: {
    visited?: boolean
    dest?: string
    method?: string
    headers?: Record<string, string>
  } = {},
) {
  const all: Record<string, string> = { ...headers }
  if (visited) all.cookie = `${VISITED_COOKIE}=1`
  if (dest) all['sec-fetch-dest'] = dest
  return new NextRequest(new URL(path, ORIGIN), { method, headers: all })
}

/** proxy 가 넘기는 응답이면(리다이렉트가 아니면) null */
const redirectOf = (response: Response) => response.headers.get('location')

describe('firstVisit — 처음 온 사람', () => {
  it('쿠키 없이 홈을 문서로 열면 시작 화면으로 보내고 방문 표시를 심는다', () => {
    const response = firstVisit(request('/', { dest: 'document' }))
    expect(response.status).toBe(307)
    expect(redirectOf(response)).toBe(`${ORIGIN}/start`)
    expect(response.headers.get('cache-control')).toBe('private, no-store')
    const cookie = response.cookies.get(VISITED_COOKIE)
    expect(cookie).toMatchObject({
      value: '1',
      path: '/',
      maxAge: 60 * 60 * 24 * 365,
      sameSite: 'lax',
      httpOnly: true,
      secure: false,
    })
  })

  it('Sec-Fetch-Dest 가 없는 요청(curl · 오래된 브라우저)도 문서로 본다', () => {
    expect(redirectOf(firstVisit(request('/')))).toBe(`${ORIGIN}/start`)
  })

  it('HTTPS 면 Secure 로 심는다 (TLS 를 앞단에서 끝내도)', () => {
    const response = firstVisit(request('/', { headers: { 'x-forwarded-proto': 'https' } }))
    expect(response.cookies.get(VISITED_COOKIE)?.secure).toBe(true)
  })

  it('다시 온 사람(쿠키 있음)은 홈을 그대로 보이고 쿠키를 다시 심지 않는다', () => {
    const response = firstVisit(request('/', { visited: true, dest: 'document' }))
    expect(redirectOf(response)).toBeNull()
    expect(response.headers.get('x-middleware-next')).toBe('1')
    expect(response.cookies.get(VISITED_COOKIE)).toBeUndefined()
  })

  it.each(['/official', '/notice/11440660', '/map', '/me', '/install', '/start', '/login'])(
    '공유 링크로 바로 연 %s 는 보내지 않고 방문 표시만 심는다',
    (path) => {
      const response = firstVisit(request(path, { dest: 'document' }))
      expect(redirectOf(response)).toBeNull()
      expect(response.cookies.get(VISITED_COOKIE)?.value).toBe('1')
    },
  )

  it.each([
    '/?region=11440660',
    '/?report=login',
    '/?explain=1',
    '/?mock-auth=member',
    '/?mock=high',
  ])('쿼리가 있는 홈 %s (바로가기 · QA 덮어쓰기)는 보내지 않는다', (path) => {
    expect(shouldSendToStart(request(path, { dest: 'document' }))).toBe(false)
  })

  it('앱 안 이동(RSC 요청 · 미리 받기, Sec-Fetch-Dest: empty)은 보내지 않는다 — 쿠키를 막은 브라우저도 홈에 들어간다', () => {
    const response = firstVisit(request('/', { dest: 'empty' }))
    expect(redirectOf(response)).toBeNull()
    expect(response.cookies.get(VISITED_COOKIE)?.value).toBe('1')
  })

  it('GET 이 아니면 보내지 않는다', () => {
    expect(shouldSendToStart(request('/', { method: 'POST' }))).toBe(false)
  })
})

describe('proxy matcher', () => {
  it.each([
    '/',
    '/start',
    '/login',
    '/official',
    '/notice/11440660/2026-W39',
    '/me/devices',
    '/install',
  ])('화면 경로 %s 에서 돈다', (url) => {
    expect(unstable_doesMiddlewareMatch({ config, url })).toBe(true)
  })

  it.each([
    '/_next/static/chunks/main.js',
    '/_next/image?url=%2Fonboarding%2Fneighborhood.svg',
    '/icon',
    '/apple-icon',
    '/app-icons/icon-192.png',
    '/manifest.webmanifest',
    '/onboarding/neighborhood.svg',
    '/favicon.ico',
  ])('정적 파일 · 아이콘 %s 에서는 돌지 않는다', (url) => {
    expect(unstable_doesMiddlewareMatch({ config, url })).toBe(false)
  })
})
