// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getSessionSnapshot, setSession, startSession } from '@/lib/session/session-store'
import type { SessionMessage } from '@/lib/session/session-sync'
import { clearSessionExpiring, isSessionExpiring, onSessionExpired } from '@/lib/session-expiry'
import { memberToken, resetApiSession } from '@/test/api-session'

import { logout } from './auth-client'

/* 실데이터 로그아웃이 세션 저장소 · API 계층과 함께 도는 흐름. 탭 사이 통로는 보낸 알림을 모으는 가짜다 */

const sync = vi.hoisted(() => ({ posted: [] as SessionMessage[] }))
vi.mock('@/lib/session/session-sync', () => ({
  openSessionChannel: () => ({
    post: (message: SessionMessage) => sync.posted.push(message),
    close: () => {},
  }),
  withSessionLock: <T>(task: () => Promise<T>): Promise<T> => task(),
}))

function envelope(error?: { code: string; status: number }): Response {
  const dataHeader = error
    ? { success: false, resultCode: error.code, resultMessage: '거절', fieldErrors: null }
    : { success: true, resultCode: null, resultMessage: null, fieldErrors: null }
  return new Response(JSON.stringify({ dataHeader, dataBody: null }), {
    status: error?.status ?? 200,
  })
}

let stop: () => void = () => {}

beforeEach(() => {
  sync.posted = []
  stop = startSession()
})

afterEach(() => {
  stop()
  clearSessionExpiring()
  resetApiSession()
})

describe('logout (실데이터 · 세션 저장소와 함께)', () => {
  it('만료 직전 access 의 재발급이 재로그인(AUTH_014)으로 끝나도 로그아웃으로 끝난다 — 만료 표시 · 두 번째 방송이 없다', async () => {
    // 만료 30초 전 안쪽이라 요청 전에 먼저 재발급한다
    setSession(memberToken({ accessTokenExpiresIn: 10 }))
    const expired = vi.fn()
    const unsubscribe = onSessionExpired(expired)
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      const path = new URL(url).pathname
      if (path === '/api/v1/auth/token/reissue') {
        return Promise.resolve(envelope({ code: 'AUTH_014', status: 401 }))
      }
      // 재발급이 세션을 비워 토큰 없이 나간다
      expect(new Headers(init?.headers).get('Authorization')).toBeNull()
      return Promise.resolve(envelope({ code: 'SECURITY_001', status: 401 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(logout('api')).resolves.toBeUndefined()

    expect(fetchMock.mock.calls.map(([url]) => new URL(url).pathname)).toEqual([
      '/api/v1/auth/token/reissue',
      '/api/v1/auth/logout',
    ])
    expect(getSessionSnapshot().status).toBe('guest')
    // 재발급이 만료를 알렸지만 사용자가 고른 로그아웃이 이긴다
    expect(expired).toHaveBeenCalledTimes(1)
    expect(isSessionExpiring()).toBe(false)
    // 다른 탭에는 재발급이 비운 한 번만 알린다
    expect(sync.posted.filter((message) => message.type === 'signed-out')).toHaveLength(1)
    unsubscribe()
  })

  it('성공하면 세션을 비우고 로그아웃을 한 번 알린다', async () => {
    setSession(memberToken())
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(envelope())),
    )

    await logout('api')

    expect(getSessionSnapshot().status).toBe('guest')
    expect(sync.posted.filter((message) => message.type === 'signed-out')).toEqual([
      { type: 'signed-out', reason: 'logout' },
    ])
  })
})
