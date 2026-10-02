import { vi } from 'vitest'

import { DATA_SOURCE_COOKIE } from '@/lib/data-source'
import { writeSessionHint } from '@/lib/session/session-hint'
import { type AuthToken, resetSessionForTests } from '@/lib/session/session-store'

/* 실데이터 모드(세션 저장소)를 흉내 내는 테스트 도구. jsdom 환경 파일에서만 쓴다(쿠키) */

/** 실데이터 모드로 바꾼다(출처 쿠키). 정리는 `resetApiSession` */
export function selectApiSource(): void {
  document.cookie = `${DATA_SOURCE_COOKIE}=api; Path=/`
}

/** 출처 쿠키 · 힌트 쿠키 · 메모리 세션 · 가짜 fetch 를 지운다 */
export function resetApiSession(): void {
  document.cookie = `${DATA_SOURCE_COOKIE}=; Path=/; Max-Age=0`
  writeSessionHint(false)
  resetSessionForTests()
  vi.unstubAllGlobals()
}

export function memberToken(overrides: Partial<AuthToken> = {}): AuthToken {
  return {
    memberId: '1843956734582784',
    role: 'USER',
    accessToken: 'access-1',
    accessTokenExpiresIn: 900,
    pendingConsents: [],
    reportWritable: true,
    ...overrides,
  }
}

/** `GET /api/v1/members/me` 응답 본문(backend `MemberMyInfoResponse`). `memberToken()` 과 같은 회원이다 */
export function myInfoBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    memberId: '1843956734582784',
    email: 'me@example.com',
    nickname: '재채기탐정',
    provider: 'EMAIL',
    hasPassword: true,
    role: 'USER',
    pendingConsents: [],
    reportWritable: true,
    ...overrides,
  }
}

/** 성공 봉투 응답 */
export function okResponse(dataBody: unknown): Response {
  return envelope(dataBody)
}

/** 실패 봉투 응답 */
export function errorResponse(code: string, status: number): Response {
  return envelope(null, { code, status })
}

/**
 * 요청마다 응답을 테스트가 정할 때까지 붙잡아 두는 서버(가짜 fetch). `reply('GET /api/v1/members/me', response)` 처럼
 * 메서드 · 경로로 가장 먼저 붙잡힌 요청에 답한다. 답하지 않은 요청은 끝나지 않는다
 */
export function holdRequests(): {
  fetchMock: ReturnType<typeof vi.fn>
  requests: () => string[]
  reply: (key: string, response: Response) => void
} {
  const waiting: { key: string; resolve: (response: Response) => void }[] = []
  const fetchMock = vi.fn(
    (url: string, init?: RequestInit) =>
      new Promise<Response>((resolve) => {
        waiting.push({ key: `${init?.method ?? 'GET'} ${new URL(url).pathname}`, resolve })
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return {
    fetchMock,
    requests: () =>
      fetchMock.mock.calls.map(
        ([url, init]) => `${init?.method ?? 'GET'} ${new URL(url).pathname}`,
      ),
    reply: (key, response) => {
      const index = waiting.findIndex((request) => request.key === key)
      const [request] = index >= 0 ? waiting.splice(index, 1) : []
      if (!request) throw new Error(`붙잡힌 요청이 없다: ${key}`)
      request.resolve(response)
    },
  }
}

function envelope(dataBody: unknown, error?: { code: string; status: number }): Response {
  const dataHeader = error
    ? { success: false, resultCode: error.code, resultMessage: '거절', fieldErrors: null }
    : { success: true, resultCode: null, resultMessage: null, fieldErrors: null }
  return new Response(JSON.stringify({ dataHeader, dataBody: error ? null : dataBody }), {
    status: error?.status ?? 200,
  })
}

/**
 * 재발급 응답을 테스트가 정할 때까지 붙잡아 두는 서버(가짜 fetch). 힌트 쿠키도 남긴다 —
 * 그다음 `restoreSession()` 을 부르면 `restoring` 에 머문다
 */
export function holdReissue(): {
  succeed: (token?: AuthToken) => void
  fail: (code: string, status: number) => void
} {
  writeSessionHint(true)
  let reply: (response: Response) => void = () => {}
  vi.stubGlobal(
    'fetch',
    vi.fn(() => new Promise<Response>((resolve) => (reply = resolve))),
  )
  return {
    succeed: (token = memberToken()) => reply(envelope(token)),
    fail: (code, status) => reply(envelope(null, { code, status })),
  }
}
