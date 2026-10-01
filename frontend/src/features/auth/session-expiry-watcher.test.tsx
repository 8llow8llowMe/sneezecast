// @vitest-environment jsdom
import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { clearSessionExpiring, isSessionExpiring, notifySessionExpired } from '@/lib/session-expiry'

import { getMockSession, loginWithEmail, resetMockSession } from './auth-client'
import { SessionExpiryWatcher } from './session-expiry-watcher'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
const search = vi.hoisted(() => ({ value: '' }))
const pathname = vi.hoisted(() => ({ value: '/me' }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  useSearchParams: () => new URLSearchParams(search.value),
  usePathname: () => pathname.value,
}))

describe('SessionExpiryWatcher', () => {
  beforeEach(async () => {
    search.value = ''
    pathname.value = '/me'
    router.replace.mockClear()
    clearSessionExpiring()
    resetMockSession()
    await loginWithEmail('dong@example.com', 'dongne2026')
  })

  afterEach(() => {
    resetMockSession()
  })

  it('로그인 만료를 받으면 세션을 비우고 로그인 화면(만료 토스트)으로 기록을 바꿔 간다', () => {
    render(<SessionExpiryWatcher />)
    expect(getMockSession()).toBe('member-no-consent')
    expect(router.replace).not.toHaveBeenCalled()

    act(() => notifySessionExpired())

    expect(getMockSession()).toBe('guest')
    expect(router.replace).toHaveBeenCalledWith('/login?reason=expired')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('목 재현 입력 ?mock-session=expired 면 열자마자 만료 흐름을 탄다', () => {
    search.value = 'mock-auth=member&mock-session=expired'
    render(<SessionExpiryWatcher />)

    expect(getMockSession()).toBe('guest')
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/login?reason=expired')
  })

  it('모르는 ?mock-session= 값은 무시한다', () => {
    search.value = 'mock-session=later'
    render(<SessionExpiryWatcher />)

    expect(getMockSession()).toBe('member-no-consent')
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('화면을 떠나면 더 받지 않는다', () => {
    const { unmount } = render(<SessionExpiryWatcher />)
    unmount()

    notifySessionExpired()
    expect(router.replace).not.toHaveBeenCalled()
    expect(getMockSession()).toBe('member-no-consent')
  })

  it('만료 진행 표시는 로그인 화면에 닿을 때까지 켜져 있다', () => {
    const { rerender } = render(<SessionExpiryWatcher />)
    act(() => notifySessionExpired())
    expect(isSessionExpiring()).toBe(true)

    pathname.value = '/login'
    rerender(<SessionExpiryWatcher />)
    expect(isSessionExpiring()).toBe(false)
  })

  it('이미 로그인 화면에서 만료돼도 표시를 끈다', () => {
    pathname.value = '/login'
    render(<SessionExpiryWatcher />)

    act(() => notifySessionExpired())
    expect(router.replace).toHaveBeenCalledWith('/login?reason=expired')
    expect(isSessionExpiring()).toBe(false)
  })

  it('만료 뒤 로그인 화면을 거치지 않고 다시 회원이 돼도 표시를 끈다', async () => {
    render(<SessionExpiryWatcher />)
    act(() => notifySessionExpired())
    expect(isSessionExpiring()).toBe(true)

    await act(() => loginWithEmail('dong@example.com', 'dongne2026'))
    expect(isSessionExpiring()).toBe(false)
  })
})
