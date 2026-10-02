// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetSessionForTests, restoreSession, setSession } from '@/lib/session/session-store'
import { holdReissue, memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

import { loginWithEmail, resetMockSession } from './auth-client'
import { authStateOf, useAuth, useAuthSettled } from './use-auth'
import { useMemberRequirements } from './use-member-requirements'
import { useMockProfile } from './use-mock-auth'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
}))

beforeEach(() => {
  search = ''
  resetMockSession()
  resetSessionForTests()
})

afterEach(() => {
  resetApiSession()
})

describe('authStateOf', () => {
  it('회원이 아니면(복원 전 · 복원 중 포함) 비회원이다', () => {
    expect(authStateOf({ status: 'idle' })).toBe('guest')
    expect(authStateOf({ status: 'restoring' })).toBe('guest')
    expect(authStateOf({ status: 'guest' })).toBe('guest')
  })

  it('보고를 쓸 수 있으면 member, 아니면 member-no-consent 다', () => {
    const summary = { memberId: '1', role: 'USER', pendingConsents: [] } as const
    expect(authStateOf({ status: 'member', summary: { ...summary, reportWritable: true } })).toBe(
      'member',
    )
    expect(authStateOf({ status: 'member', summary: { ...summary, reportWritable: false } })).toBe(
      'member-no-consent',
    )
  })
})

describe('useAuth', () => {
  it('목데이터 모드는 ?mock-auth= 덮어쓰기와 목 세션을 따른다', async () => {
    search = 'mock-auth=member'
    const { result, rerender } = renderHook(() => useAuth())
    expect(result.current).toBe('member')

    search = ''
    await act(() => loginWithEmail('dong@example.com', 'dongne2026'))
    rerender()
    expect(result.current).toBe('member-no-consent')
  })

  it('실데이터 모드는 ?mock-auth= 와 목 세션을 듣지 않고 세션 저장소를 따른다', async () => {
    selectApiSource()
    search = 'mock-auth=member'
    await loginWithEmail('dong@example.com', 'dongne2026')
    const { result } = renderHook(() => useAuth())
    expect(result.current).toBe('guest')

    act(() => setSession(memberToken({ reportWritable: false })))
    expect(result.current).toBe('member-no-consent')
  })
})

describe('useAuthSettled', () => {
  it('목데이터 모드는 하이드레이션을 마치면 정해진 것이다', () => {
    const { result } = renderHook(() => useAuthSettled())
    expect(result.current).toBe(true)
  })

  it('실데이터 모드는 복원 전(idle) · 복원 중(restoring)에는 정해지지 않았다', async () => {
    selectApiSource()
    const server = holdReissue()
    const { result } = renderHook(() => useAuthSettled())
    expect(result.current).toBe(false)

    let restoring: Promise<void> = Promise.resolve()
    act(() => {
      restoring = restoreSession()
    })
    expect(result.current).toBe(false)

    // 재로그인으로 끝나면 비회원으로 정해진다
    await act(async () => {
      server.fail('AUTH_014', 401)
      await restoring
    })
    expect(result.current).toBe(true)
  })
})

describe('실데이터 모드의 QA 덮어쓰기', () => {
  it('?mock-provider= 를 듣지 않는다', () => {
    selectApiSource()
    search = 'mock-provider=kakao'
    setSession(memberToken())
    const { result } = renderHook(() => useMockProfile())
    expect(result.current?.provider).toBe('email')
  })

  it('?mock-required= 를 듣지 않고 재동의할 항목(pendingConsents)으로 거칠 화면을 정한다', () => {
    selectApiSource()
    search = 'mock-required=region'
    setSession(memberToken())
    const { result } = renderHook(() => useMemberRequirements())
    expect(result.current.steps).toEqual([])

    act(() => setSession(memberToken({ pendingConsents: ['TERMS_OF_SERVICE'] })))
    expect(result.current).toEqual({ steps: ['terms'], abolishedRegion: null })
  })

  it('목데이터 모드는 ?mock-required= 를 그대로 듣는다', () => {
    search = 'mock-auth=member&mock-required=region'
    const { result } = renderHook(() => useMemberRequirements())
    expect(result.current.steps).toEqual(['region'])
  })
})
