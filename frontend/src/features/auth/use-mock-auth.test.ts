// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  agreeHealthConsent,
  EXAMPLE_PROFILES,
  loginWithEmail,
  logout,
  resetMockSession,
  signup,
} from './auth-client'
import { consentFor } from './legal'
import { parseMockAuth, parseMockProvider, useMockAuth, useMockProfile } from './use-mock-auth'

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

    await act(() => loginWithEmail('dong@example.com', 'dongne2026', 'mock'))
    expect(result.current).toBe('member-no-consent')

    await act(() => agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO')))
    expect(result.current).toBe('member')
  })

  it('?mock-auth= 덮어쓰기가 목 세션보다 먼저다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    search = 'mock-auth=guest'
    const { result } = renderHook(() => useMockAuth())
    expect(result.current).toBe('guest')
  })

  it('모르는 덮어쓰기 값은 무시하고 목 세션을 쓴다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    search = 'mock-auth=admin'
    const { result } = renderHook(() => useMockAuth())
    expect(result.current).toBe('member-no-consent')
  })
})

describe('parseMockProvider', () => {
  it.each([
    ['email', 'email'],
    ['kakao', 'kakao'],
    ['naver', null],
    [null, null],
  ])('%s → %s', (value, expected) => {
    expect(parseMockProvider(value)).toBe(expected)
  })
})

describe('useMockProfile', () => {
  beforeEach(() => {
    search = ''
    resetMockSession()
  })

  it('비회원이면 null 이다 (?mock-provider= 만 있어도)', () => {
    search = 'mock-provider=kakao'
    const { result } = renderHook(() => useMockProfile())
    expect(result.current).toBeNull()
  })

  it('목 세션 프로필을 따르고, 로그아웃처럼 세션이 바뀌면 다시 그린다', async () => {
    const { result } = renderHook(() => useMockProfile())
    await act(() => loginWithEmail('me@example.com', 'dongne2026', 'mock'))
    expect(result.current?.email).toBe('me@example.com')

    await act(() => logout('mock'))
    expect(result.current).toBeNull()
  })

  it('?mock-auth= 덮어쓰기만 있고 세션이 없으면 이메일 예시 프로필이다', () => {
    search = 'mock-auth=member'
    const { result } = renderHook(() => useMockProfile())
    expect(result.current).toEqual(EXAMPLE_PROFILES.email)
  })

  it('?mock-provider= 가 세션과 다르면 그 방법의 예시 프로필, 같으면 세션 프로필이다', async () => {
    await signup({ kind: 'kakao', consents: [consentFor('TERMS_OF_SERVICE')] }, 'mock')
    search = 'mock-provider=email'
    const { result, rerender } = renderHook(() => useMockProfile())
    expect(result.current).toEqual(EXAMPLE_PROFILES.email)

    search = 'mock-provider=kakao'
    rerender()
    expect(result.current).toEqual(EXAMPLE_PROFILES.kakao)
  })
})
