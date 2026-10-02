// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { loginWithEmail, resetMockSession } from '@/features/auth/auth-client'
import { SessionExpiryWatcher } from '@/features/auth/session-expiry-watcher'
import { restoreSession } from '@/lib/session/session-store'
import { clearSessionExpiring, notifySessionExpired } from '@/lib/session-expiry'
import { NavTrailProvider, useNavTrail } from '@/lib/use-nav-trail'
import { holdReissue, memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

import { MeRequiredStepsGate, useMemberGate, useRequiredStepsGate } from './member-gate'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
let pathname = '/me'
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => pathname,
  useRouter: () => router,
}))

function HomeGate() {
  useRequiredStepsGate('/')
  return <p>홈</p>
}

beforeEach(() => {
  search = ''
  pathname = '/me'
  resetMockSession()
  clearSessionExpiring()
  router.replace.mockClear()
})

describe('useRequiredStepsGate', () => {
  it('비회원은 조건 덮어쓰기가 있어도 보내지 않는다', () => {
    search = 'mock-required=terms,region'
    render(<HomeGate />)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('조건이 없는 회원은 보내지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    render(<HomeGate />)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('약관이 개정된 회원은 재동의로 보낸다 (홈이면 next 없음)', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026', 'mock')
    render(<HomeGate />)
    expect(router.replace).toHaveBeenCalledWith('/terms/reconsent')
  })

  it('동네가 폐지된 회원은 동네 다시 고르기로 보낸다', async () => {
    await loginWithEmail('reselect@example.com', 'dongne2026', 'mock')
    render(<HomeGate />)
    expect(router.replace).toHaveBeenCalledWith('/setup/region?reselect=1')
  })

  it('조건이 둘이면 재동의가 먼저다', () => {
    search = 'mock-auth=member&mock-required=region,terms'
    render(<HomeGate />)
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith(
      '/terms/reconsent?mock-auth=member&mock-required=terms%2Cregion',
    )
  })

  it('동네 · 목 덮어쓰기만 남기고 열린 시트 쿼리는 버린다 — 홈의 보고 진입은 보고하려던 표시로 잇는다(#140)', () => {
    search = 'region=11440660&mock-auth=member-no-consent&mock-required=region&report=start'
    render(<HomeGate />)
    expect(router.replace).toHaveBeenCalledWith(
      '/setup/region?reselect=1&region=11440660&mock-auth=member-no-consent&mock-required=region&intent=report',
    )
  })

  it('하이드레이션 첫 그림(비회원)으로는 판단하지 않고, 하이드레이션 뒤 회원 상태로 보낸다', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026', 'mock')
    const container = document.createElement('div')
    document.body.append(container)
    // 서버 그림은 목 세션을 모른다(비회원)
    container.innerHTML = renderToString(<HomeGate />)
    expect(router.replace).not.toHaveBeenCalled()

    render(<HomeGate />, { container, hydrate: true })
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/terms/reconsent')
  })
})

describe('MeRequiredStepsGate', () => {
  it.each(['/me', '/me/devices', '/me/password'])(
    '%s 에서 보내면 그 경로로 돌아오게 next 를 붙인다',
    (path) => {
      pathname = path
      search = 'mock-auth=member&mock-required=terms'
      render(<MeRequiredStepsGate />)
      expect(router.replace).toHaveBeenCalledWith(
        `/terms/reconsent?next=${encodeURIComponent(path)}&mock-auth=member&mock-required=terms`,
      )
    },
  )

  it('내 정보로 돌아가면 보고 진입이 있어도 보고하려던 표시를 붙이지 않는다 (보고 진입은 홈의 시트다)', () => {
    pathname = '/me'
    search = 'mock-auth=member&mock-required=terms&report=start'
    render(<MeRequiredStepsGate />)
    expect(router.replace).toHaveBeenCalledWith(
      '/terms/reconsent?next=%2Fme&mock-auth=member&mock-required=terms',
    )
  })

  it('허용 목록 밖 경로에서는 홈으로 돌아오게 한다', () => {
    pathname = '/me/unknown'
    search = 'mock-auth=member&mock-required=region'
    render(<MeRequiredStepsGate />)
    expect(router.replace).toHaveBeenCalledWith(
      '/setup/region?reselect=1&mock-auth=member&mock-required=region',
    )
  })
})

/** 회원만 쓰는 계정 화면(로그인한 기기 · 비밀번호)처럼 가드를 쓰는 화면 */
function DevicesGate() {
  return <p>{useMemberGate({ next: '/me/devices' }) ?? '가드 대기'}</p>
}

describe('useMemberGate', () => {
  beforeEach(() => {
    pathname = '/me/devices'
  })

  it('비회원은 지금 화면을 next 로 싣고 로그인으로 보낸다 (#140)', () => {
    render(<DevicesGate />)
    expect(router.replace).toHaveBeenLastCalledWith('/login?next=%2Fme%2Fdevices')
  })

  it('회원 화면에서 로그인이 만료되면 마지막 이동이 만료 토스트가 있는 로그인 화면이다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    render(
      <>
        <DevicesGate />
        <SessionExpiryWatcher />
      </>,
    )
    expect(router.replace).not.toHaveBeenCalled()

    // 세션이 비회원이 되어 가드도 다시 그려져 로그인으로 보내려 한다 — 만료 이동보다 나중이어도 만료 주소여야 한다
    act(() => notifySessionExpired())

    expect(router.replace).toHaveBeenLastCalledWith('/login?reason=expired&next=%2Fme%2Fdevices')
    expect(router.replace).not.toHaveBeenCalledWith('/login?next=%2Fme%2Fdevices')
  })

  it('계정 화면에서 로그인이 만료되면 만료 주소에도 지금 화면(next)과 둘러보기 동네를 싣는다 — 만료 감시와 같은 주소다 (#140)', async () => {
    function AccountGate() {
      return <p>{useMemberGate({ next: '/me/devices' }) ?? '가드 대기'}</p>
    }
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    search = 'region=11440660&mock-auth=member'
    render(
      <>
        <AccountGate />
        <SessionExpiryWatcher />
      </>,
    )
    act(() => notifySessionExpired())

    const expired = '/login?reason=expired&next=%2Fme%2Fdevices&region=11440660'
    expect(router.replace).toHaveBeenLastCalledWith(expired)
    // 만료 감시와 가드가 모두 보내도 같은 곳이다
    expect(new Set(router.replace.mock.calls.map(([href]) => String(href)))).toEqual(
      new Set([expired]),
    )
  })

  it('계정 화면의 비회원은 지금 화면을 next 로 싣고 로그인으로 간다 (#140)', () => {
    function AccountGate() {
      return <p>{useMemberGate({ next: '/me/password' }) ?? '가드 대기'}</p>
    }
    render(<AccountGate />)
    expect(router.replace.mock.calls).toEqual([['/login?next=%2Fme%2Fpassword']])
  })

  it('돌아올 곳(next)을 주면 로그인 주소에 next 와 둘러보기 동네만 붙인다 (QA 덮어쓰기는 뺀다)', () => {
    pathname = '/me'
    search = 'region=11440660&mock-auth=guest&mock-required=terms&confirm=logout'
    render(<MeGate />)
    expect(router.replace.mock.calls).toEqual([['/login?next=%2Fme&region=11440660']])
  })

  it('허용 목록 밖의 next 는 홈으로 본다 (next 를 붙이지 않는다)', () => {
    function OddGate() {
      return <p>{useMemberGate({ next: '//evil.example' }) ?? '가드 대기'}</p>
    }
    render(<OddGate />)
    expect(router.replace.mock.calls).toEqual([['/login']])
  })

  it('멈춰 두면(paused) 세션이 비회원이 되어도 로그인으로 보내지 않는다 — 로그아웃 뒤 홈으로 가는 중', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    const { rerender } = render(<MeGate paused />)
    act(() => resetMockSession())
    expect(router.replace).not.toHaveBeenCalled()

    // 멈춤을 풀면(보내기 실패 등) 다시 판단한다
    rerender(<MeGate paused={false} />)
    expect(router.replace.mock.calls).toEqual([['/login?next=%2Fme']])
  })

  it('하이드레이션 첫 그림(서버 그림)에서는 판단하지 않는다', () => {
    const html = renderToString(<MeGate />)
    expect(html).toContain('가드 대기')
    expect(router.replace).not.toHaveBeenCalled()
  })
})

/** 내 정보처럼 로그인 뒤 돌아올 곳을 주는 가드 */
function MeGate({ paused = false }: { paused?: boolean }) {
  return <p>{useMemberGate({ next: '/me', paused }) ?? '가드 대기'}</p>
}

describe('실데이터 모드의 가드 (새로고침 뒤 세션 복원)', () => {
  beforeEach(() => {
    pathname = '/me'
    selectApiSource()
  })

  afterEach(() => {
    resetApiSession()
  })

  it('복원 중에는 로그인으로 보내지 않고, 비회원으로 정해진 뒤 보낸다', async () => {
    const server = holdReissue()
    render(<MeGate />)
    // 복원 전(idle)에도 판단하지 않는다
    expect(router.replace).not.toHaveBeenCalled()

    let restoring: Promise<void> = Promise.resolve()
    act(() => {
      restoring = restoreSession()
    })
    expect(router.replace).not.toHaveBeenCalled()
    expect(screen.getByText('가드 대기')).toBeTruthy()

    await act(async () => {
      server.fail('AUTH_014', 401)
      await restoring
    })
    expect(router.replace.mock.calls).toEqual([['/login?next=%2Fme']])
  })

  it('복원이 회원으로 끝나면 보내지 않고 회원 상태를 돌려준다', async () => {
    const server = holdReissue()
    render(<MeGate />)
    let restoring: Promise<void> = Promise.resolve()
    act(() => {
      restoring = restoreSession()
    })

    await act(async () => {
      server.succeed(memberToken({ reportWritable: false }))
      await restoring
    })
    expect(router.replace).not.toHaveBeenCalled()
    expect(screen.getByText('member-no-consent')).toBeTruthy()
  })

  it('?mock-auth=member 덮어쓰기로 비회원 가드를 건너뛰지 않는다', async () => {
    search = 'mock-auth=member'
    render(<MeGate />)
    await act(() => restoreSession())
    expect(router.replace.mock.calls).toEqual([['/login?next=%2Fme']])
  })
})

describe('가드의 replace 와 앱 안 이동 기록', () => {
  let navTrail: ReturnType<typeof useNavTrail> | null = null
  function Probe() {
    navTrail = useNavTrail()
    return null
  }

  it('바로 연 공식 정보 → 홈(가드가 재동의로 replace) → 휴대폰 뒤로 → 공식 정보의 뒤로가 사이트 밖으로 나가지 않는다', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026', 'mock')
    // 같은 요소 객체를 다시 넘기면 React 가 다시 그리지 않아 매번 새로 만든다
    const tree = () => (
      <NavTrailProvider>
        <Probe />
        {pathname === '/' && <HomeGate />}
      </NavTrailProvider>
    )
    pathname = '/official'
    const { rerender } = render(tree())
    for (const next of ['/', '/terms/reconsent', '/official']) {
      pathname = next
      rerender(tree())
    }
    expect(router.replace).toHaveBeenCalledWith('/terms/reconsent')

    router.replace.mockClear()
    navTrail?.goBack('/')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })
})
