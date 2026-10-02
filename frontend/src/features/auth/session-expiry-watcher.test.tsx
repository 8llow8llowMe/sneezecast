// @vitest-environment jsdom
import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { clearSession, setSession } from '@/lib/session/session-store'
import { clearSessionExpiring, isSessionExpiring, notifySessionExpired } from '@/lib/session-expiry'
import { NavTrailProvider, useNavTrail } from '@/lib/use-nav-trail'
import { memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

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
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
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
    // 만료된 화면(내 정보)으로 돌아오게 next 를 싣는다(#140)
    expect(router.replace).toHaveBeenCalledWith('/login?reason=expired&next=%2Fme')
    expect(router.push).not.toHaveBeenCalled()
  })

  it.each([
    [
      '/me/devices',
      'region=11440660&mock-auth=member',
      '/login?reason=expired&next=%2Fme%2Fdevices&region=11440660',
    ],
    ['/me/password', '', '/login?reason=expired&next=%2Fme%2Fpassword'],
    ['/', 'region=11680640&report=start', '/login?reason=expired&region=11680640'],
    // 허용 목록 밖 화면은 next 를 싣지 않는다(다시 로그인하면 홈) — 둘러보기 동네는 남긴다
    ['/map', 'region=11680640', '/login?reason=expired&region=11680640'],
    ['/official', '', '/login?reason=expired'],
  ])('%s?%s 에서 만료되면 %s 로 간다', (path, query, expected) => {
    pathname.value = path
    search.value = query
    render(<SessionExpiryWatcher />)
    act(() => notifySessionExpired())
    expect(router.replace.mock.calls).toEqual([[expected]])
  })

  it('화면이 바뀌면 바뀐 화면을 싣는다', () => {
    const view = render(<SessionExpiryWatcher />)
    pathname.value = '/me/devices'
    view.rerender(<SessionExpiryWatcher />)
    act(() => notifySessionExpired())
    expect(router.replace.mock.calls).toEqual([['/login?reason=expired&next=%2Fme%2Fdevices']])
  })

  it('목 재현 입력 ?mock-session=expired 면 열자마자 만료 흐름을 탄다', () => {
    search.value = 'mock-auth=member&mock-session=expired'
    render(<SessionExpiryWatcher />)

    expect(getMockSession()).toBe('guest')
    expect(router.replace).toHaveBeenCalledTimes(1)
    // 목 덮어쓰기(`mock-auth` · `mock-session`)는 싣지 않는다 — 다시 만료되지 않는다
    expect(router.replace).toHaveBeenCalledWith('/login?reason=expired&next=%2Fme')
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

    await act(() => loginWithEmail('dong@example.com', 'dongne2026', 'mock'))
    expect(isSessionExpiring()).toBe(false)
  })
})

describe('SessionExpiryWatcher 실데이터 모드', () => {
  beforeEach(async () => {
    pathname.value = '/me'
    search.value = ''
    router.replace.mockClear()
    clearSessionExpiring()
    resetMockSession()
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    selectApiSource()
  })

  afterEach(() => {
    resetApiSession()
    resetMockSession()
  })

  it('?mock-session=expired 는 듣지 않는다', () => {
    search.value = 'mock-session=expired'
    render(<SessionExpiryWatcher />)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('세션 저장소의 만료를 받으면 로그인 만료로 보내고 목 세션은 건드리지 않는다', () => {
    setSession(memberToken())
    render(<SessionExpiryWatcher />)

    act(() => clearSession('expired'))

    expect(router.replace).toHaveBeenCalledWith('/login?reason=expired&next=%2Fme')
    expect(getMockSession()).toBe('member-no-consent')
  })

  it('만료 뒤 실데이터 세션으로 다시 회원이 되면 표시를 끈다', () => {
    setSession(memberToken())
    render(<SessionExpiryWatcher />)
    act(() => clearSession('expired'))
    expect(isSessionExpiring()).toBe(true)

    act(() => setSession(memberToken()))
    expect(isSessionExpiring()).toBe(false)
  })
})

describe('SessionExpiryWatcher 와 앱 안 이동 기록', () => {
  let navTrail: ReturnType<typeof useNavTrail> | null = null
  function Probe() {
    navTrail = useNavTrail()
    return null
  }

  beforeEach(async () => {
    router.replace.mockClear()
    router.back.mockClear()
    clearSessionExpiring()
    resetMockSession()
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
  })

  afterEach(() => {
    resetMockSession()
  })

  it('바로 연 공식 정보 → 홈 → 로그인 만료(/login 으로 replace) → 휴대폰 뒤로 → 공식 정보의 뒤로가 사이트 밖으로 나가지 않는다', () => {
    // 같은 요소 객체를 다시 넘기면 React 가 다시 그리지 않아 매번 새로 만든다
    const tree = () => (
      <NavTrailProvider>
        <Probe />
        <SessionExpiryWatcher />
      </NavTrailProvider>
    )
    pathname.value = '/official'
    const { rerender } = render(tree())
    pathname.value = '/'
    rerender(tree())
    act(() => notifySessionExpired())
    expect(router.replace).toHaveBeenCalledWith('/login?reason=expired')
    for (const next of ['/login', '/official']) {
      pathname.value = next
      rerender(tree())
    }

    router.replace.mockClear()
    navTrail?.goBack('/')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })
})
