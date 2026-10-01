// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { agreeHealthConsent, loginWithEmail, resetMockSession } from './auth-client'
import { consentFor } from './legal'
import { parseMockAuth, useMockAuth } from './use-mock-auth'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
}))

describe('parseMockAuth', () => {
  it.each([
    ['guest', 'guest'],
    ['member', 'member'],
    ['member-no-consent', 'member-no-consent'],
    ['admin', null],
    ['', null],
    [null, null],
  ])('%s → %s', (value, expected) => {
    expect(parseMockAuth(value)).toBe(expected)
  })
})

describe('useMockAuth', () => {
  beforeEach(() => {
    search = ''
    resetMockSession()
  })

  it('쿼리도 목 세션도 없으면 비회원이다', () => {
    const { result } = renderHook(() => useMockAuth())
    expect(result.current).toBe('guest')
  })

  it('목 세션이 바뀌면 다시 그린다 (로그인 → 동의)', async () => {
    const { result } = renderHook(() => useMockAuth())

    await act(() => loginWithEmail('dong@example.com', 'dongne2026'))
    expect(result.current).toBe('member-no-consent')

    await act(() => agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO')))
    expect(result.current).toBe('member')
  })

  it('?mock-auth= 덮어쓰기가 목 세션보다 먼저다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026')
    search = 'mock-auth=guest'
    const { result } = renderHook(() => useMockAuth())
    expect(result.current).toBe('guest')
  })

  it('모르는 덮어쓰기 값은 무시하고 목 세션을 쓴다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026')
    search = 'mock-auth=admin'
    const { result } = renderHook(() => useMockAuth())
    expect(result.current).toBe('member-no-consent')
  })
})
