// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { restoreSession } from '@/lib/session/session-store'
import { clearSessionExpiring, notifySessionExpired } from '@/lib/session-expiry'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import { holdReissue, resetApiSession, selectApiSource } from '@/test/api-session'

import { loginWithEmail, resetMockSession, signup } from './auth-client'
import { GuestOnlyGate, isGuestOnly, memberTarget } from './guest-only-gate'
import { SessionExpiryWatcher } from './session-expiry-watcher'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
let pathname = '/login'
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => pathname,
  useRouter: () => router,
}))

const GUARDED = [
  '/start',
  '/login',
  '/login/email',
  '/signup/email',
  '/signup/code',
  '/signup/account',
]

const login = () => loginWithEmail('dong@example.com', 'dongne2026', 'mock')

beforeEach(() => {
  search = ''
  pathname = '/login'
  resetMockSession()
  clearSessionExpiring()
  router.replace.mockClear()
})

describe('isGuestOnly', () => {
  it.each(GUARDED)('%s 는 대상이다', (path) => {
    expect(isGuestOnly(path, new URLSearchParams())).toBe(true)
  })

  it.each([
    '/password/reset',
    '/password/reset/code',
    '/password/reset/new',
    '/setup/region',
    '/setup/adult',
    '/setup/terms',
    '/setup/health-consent',
    '/terms/reconsent',
    '/browse/region',
  ])('%s 는 대상이 아니다 (회원도 거치는 화면)', (path) => {
    expect(isGuestOnly(path, new URLSearchParams())).toBe(false)
  })

  it('재설정을 마친 이메일 로그인(?reason=reset-done)은 대상이 아니다', () => {
    expect(isGuestOnly('/login/email', new URLSearchParams('reason=reset-done'))).toBe(false)
  })
})

describe('memberTarget', () => {
  it('next 가 없으면 홈이다', () => {
    expect(memberTarget(new URLSearchParams())).toBe('/')
  })

  it('허용 목록 안의 next 와 둘러보기 동네를 따른다', () => {
    expect(memberTarget(new URLSearchParams('next=%2Fme&region=11440660'))).toBe(
      '/me?region=11440660',
    )
  })

  it.each(['//evil.example', 'https://evil.example', '/me?x=1', '/login'])(
    '허용 목록 밖 next(%s)는 홈이다',
    (next) => {
      expect(memberTarget(new URLSearchParams({ next }))).toBe('/')
    },
  )

  // 회원이 보고하려던 로그인에 닿는 것은 사실상 로그인 성공 뒤 브라우저 뒤로다 — 보고 시트를 다시 열어 붙잡지 않는다
  it('보고하려던 로그인(?intent=report)이어도 보고 진입을 붙이지 않고 동네 · QA 덮어쓰기만 남긴다', () => {
    expect(memberTarget(new URLSearchParams('region=11680640&intent=report'))).toBe(
      '/?region=11680640',
    )
    expect(memberTarget(new URLSearchParams('intent=report&mock-auth=member'))).toBe(
      '/?mock-auth=member',
    )
    expect(memberTarget(new URLSearchParams('next=%2Fme&intent=report'))).toBe('/me')
  })

  it('QA 덮어쓰기는 남기고 화면 상태 쿼리(error · reason)는 버린다', () => {
    expect(memberTarget(new URLSearchParams('mock-auth=member&error=kakao-fail'))).toBe(
      '/?mock-auth=member',
    )
  })
})

describe('GuestOnlyGate', () => {
  it.each(GUARDED)('비회원이 %s 를 열면 보내지 않는다', (path) => {
    pathname = path
    const { container } = render(<GuestOnlyGate />)
    expect(router.replace).not.toHaveBeenCalled()
    expect(container.childElementCount).toBe(0)
  })

  it.each(GUARDED)('회원이 %s 를 열면 홈으로 기록을 바꿔 가고 화면을 덮는다', async (path) => {
    await login()
    pathname = path
    const { container } = render(<GuestOnlyGate />)
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/')
    expect(container.firstElementChild?.getAttribute('aria-hidden')).toBe('true')
  })

  it('동의한 회원 · 덮어쓰기 회원도 보낸다', () => {
    search = 'mock-auth=member'
    render(<GuestOnlyGate />)
    expect(router.replace).toHaveBeenCalledWith('/?mock-auth=member')
  })

  it('덮어쓰기로 비회원을 보면(?mock-auth=guest) 회원 세션이어도 보내지 않는다', async () => {
    await login()
    search = 'mock-auth=guest'
    render(<GuestOnlyGate />)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('내 정보 가드가 붙인 next 가 있으면 그곳으로 간다', async () => {
    await login()
    pathname = '/login/email'
    search = 'next=%2Fme&region=11440660'
    render(<GuestOnlyGate />)
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660')
  })

  it('회원이 보고하려던 로그인 화면을 열면 같은 동네 홈으로만 간다 (보고 진입 없음)', async () => {
    await login()
    pathname = '/login'
    search = 'region=11680640&intent=report'
    render(<GuestOnlyGate />)
    expect(router.replace).toHaveBeenCalledWith('/?region=11680640')
  })

  it.each(['/password/reset', '/setup/terms', '/setup/region', '/terms/reconsent'])(
    '회원이 %s 를 열어도 보내지 않는다',
    async (path) => {
      await login()
      pathname = path
      search = path === '/setup/region' ? 'reselect=1' : ''
      render(<GuestOnlyGate />)
      expect(router.replace).not.toHaveBeenCalled()
    },
  )

  it('회원이 재설정을 마친 이메일 로그인에 닿으면 보내지 않는다(안내를 잃지 않게)', async () => {
    await login()
    pathname = '/login/email'
    search = 'reason=reset-done'
    render(<GuestOnlyGate />)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('화면에 있는 동안 회원이 되면(이메일 로그인 성공) 끼어들지 않는다 — 이동은 화면이 한다', async () => {
    pathname = '/login/email'
    const { container } = render(<GuestOnlyGate />)
    await act(login)
    expect(router.replace).not.toHaveBeenCalled()
    expect(container.childElementCount).toBe(0)
  })

  it('가입 흐름: 비회원으로 가입 화면을 지나 회원이 된 뒤 뒤로 가기로 가입 화면에 다시 닿으면 보낸다', async () => {
    pathname = '/signup/account'
    const view = render(<GuestOnlyGate />)
    // 가입 마무리(S02-3)로 넘어가 가입한다 — 레이아웃은 첫 진입 화면 사이에서 그대로다
    pathname = '/setup/terms'
    view.rerender(<GuestOnlyGate />)
    await act(() => signup({ kind: 'kakao', consents: [] }, 'mock'))
    expect(router.replace).not.toHaveBeenCalled()

    pathname = '/signup/account'
    view.rerender(<GuestOnlyGate />)
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('로그인 만료로 로그인 화면에 오면 비회원으로 닿아 그대로 보인다', async () => {
    await login()
    pathname = '/me'
    const view = render(
      <NavTrailProvider>
        <SessionExpiryWatcher />
      </NavTrailProvider>,
    )
    act(() => notifySessionExpired())
    expect(router.replace).toHaveBeenCalledWith('/login?reason=expired')
    router.replace.mockClear()

    pathname = '/login'
    search = 'reason=expired'
    view.rerender(
      <NavTrailProvider>
        <SessionExpiryWatcher />
        <GuestOnlyGate />
      </NavTrailProvider>,
    )
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('하이드레이션 첫 그림(비회원)으로는 판단하지 않고, 하이드레이션 뒤 회원 상태로 보낸다', async () => {
    await login()
    const container = document.createElement('div')
    document.body.append(container)
    // 서버 그림은 목 세션을 모른다(비회원) — 덮개를 그리지 않는다
    container.innerHTML = renderToString(<GuestOnlyGate />)
    expect(container.childElementCount).toBe(0)
    expect(router.replace).not.toHaveBeenCalled()

    render(<GuestOnlyGate />, { container, hydrate: true })
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('앱 안 이동 기록을 거쳐 보낸다 — 로그인 → 홈 → 뒤로로 닿은 로그인은 홈으로 바꿔 간다(점검 F3)', async () => {
    await login()
    pathname = '/login'
    render(
      <NavTrailProvider>
        <GuestOnlyGate />
      </NavTrailProvider>,
    )
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/')
  })
})

describe('GuestOnlyGate 실데이터 모드', () => {
  afterEach(() => {
    resetApiSession()
  })

  it('회원이 로그인 화면을 새로고침하면 복원이 끝난 뒤 홈으로 보낸다 — 복원 중의 비회원으로 닿음을 정하지 않는다', async () => {
    selectApiSource()
    const server = holdReissue()
    render(<GuestOnlyGate />)
    let restoring: Promise<void> = Promise.resolve()
    act(() => {
      restoring = restoreSession()
    })
    expect(router.replace).not.toHaveBeenCalled()

    await act(async () => {
      server.succeed()
      await restoring
    })
    expect(router.replace.mock.calls).toEqual([['/']])
  })

  it('?mock-auth=member 덮어쓰기는 듣지 않는다 (비회원은 그대로 로그인 화면)', async () => {
    selectApiSource()
    search = 'mock-auth=member'
    render(<GuestOnlyGate />)
    await act(() => restoreSession())
    expect(router.replace).not.toHaveBeenCalled()
  })
})
