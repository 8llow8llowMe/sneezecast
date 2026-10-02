// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { OnboardingProvider } from '@/features/onboarding/onboarding-context'
import { restoreSession } from '@/lib/session/session-store'
import { clearSessionExpiring, notifySessionExpired } from '@/lib/session-expiry'
import { holdReissue, memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

import type * as authClient from './auth-client'
import {
  agreeTermsReconsent,
  getMockProfile,
  getMockSession,
  loginWithEmail,
  logout,
  resetMockSession,
} from './auth-client'
import { LEGAL_VERSIONS } from './legal'
import { SessionExpiryWatcher } from './session-expiry-watcher'
import { TermsReconsentScreen } from './terms-reconsent-screen'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/terms/reconsent',
  useRouter: () => router,
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return {
    ...actual,
    agreeTermsReconsent: vi.fn(actual.agreeTermsReconsent),
    logout: vi.fn(actual.logout),
  }
})

const ui = (
  <OnboardingProvider>
    <TermsReconsentScreen />
  </OnboardingProvider>
)

const agreeButton = () => screen.getByRole('button', { name: '동의하고 계속하기' })
const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

beforeEach(async () => {
  search = ''
  resetMockSession()
  router.replace.mockClear()
  vi.mocked(agreeTermsReconsent).mockReset()
  vi.mocked(logout).mockReset()
  await loginWithEmail('reconsent@example.com', 'dongne2026')
})

describe('TermsReconsentScreen 그림', () => {
  it('시행일 · 바뀐 내용 · 동의 체크를 보이고 단계 표시 · 뒤로가 없다', () => {
    render(ui)
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('바뀐 약관을 확인해 주세요')
    expect(screen.getByText('12월 1일부터 아래 내용이 바뀌어요.')).toBeDefined()
    const terms = screen.getByRole('region', { name: '[필수] 서비스 이용약관' })
    expect([...terms.querySelectorAll('li')].map((item) => item.textContent)).toEqual([
      '운영자 안내의 정정·철회 절차를 추가했어요.',
      '개별 보고 보관 기간을 52주로 정했어요.',
    ])
    expect(screen.getByRole('checkbox', { name: '바뀐 서비스 이용약관에 동의해요' })).toBeDefined()
    expect(screen.queryByRole('button', { name: '뒤로' })).toBeNull()
    expect(screen.queryByText(/\d \/ 4/)).toBeNull()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('전문 보기는 본문이 준비 중이라는 알림을 띄운다', async () => {
    render(ui)
    await userEvent.setup().click(screen.getByRole('button', { name: '서비스 이용약관 전문 보기' }))
    expect(screen.getByText('약관 본문을 준비하고 있어요')).toBeDefined()
  })

  it('체크하기 전에는 버튼이 꺼져 있고(포커스는 남는다) 눌러도 보내지 않는다', async () => {
    const user = userEvent.setup()
    render(ui)
    expect(isOff(agreeButton())).toBe(true)
    await user.click(agreeButton())
    expect(agreeTermsReconsent).not.toHaveBeenCalled()

    await user.click(screen.getByRole('checkbox', { name: '바뀐 서비스 이용약관에 동의해요' }))
    expect(isOff(agreeButton())).toBe(false)
  })
})

describe('TermsReconsentScreen 보내기', () => {
  it('지금 이용약관 버전으로 동의하고, 프로필을 바꾼 뒤 next 로 기록을 바꿔 간다', async () => {
    search = 'next=/me/devices&region=11440660'
    const user = userEvent.setup()
    render(ui)
    await user.click(screen.getByRole('checkbox', { name: '바뀐 서비스 이용약관에 동의해요' }))
    await user.click(agreeButton())

    expect(agreeTermsReconsent).toHaveBeenCalledWith({
      type: 'TERMS_OF_SERVICE',
      documentVersion: LEGAL_VERSIONS.TERMS_OF_SERVICE,
    })
    expect(getMockProfile()?.termsReconsentRequired).toBe(false)
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/me/devices?region=11440660')
  })

  it('동네 조건이 남았으면 동네 다시 고르기로 이어 간다 (덮어쓰기에서 마친 조건을 뺀다)', async () => {
    search = 'next=/me&mock-auth=member&mock-required=terms,region'
    const user = userEvent.setup()
    render(ui)
    await user.click(screen.getByRole('checkbox', { name: '바뀐 서비스 이용약관에 동의해요' }))
    await user.click(agreeButton())

    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith(
      '/setup/region?reselect=1&next=%2Fme&mock-auth=member&mock-required=region',
    )
  })

  it('보내는 동안 버튼이 꺼지고 다시 눌러도 한 번만 보낸다', async () => {
    let finish: () => void = () => {}
    vi.mocked(agreeTermsReconsent).mockImplementationOnce(
      () => new Promise<void>((resolve) => (finish = resolve)),
    )
    const user = userEvent.setup()
    render(ui)
    await user.click(screen.getByRole('checkbox', { name: '바뀐 서비스 이용약관에 동의해요' }))
    await user.click(agreeButton())
    expect(isOff(agreeButton())).toBe(true)
    await user.click(agreeButton())
    expect(agreeTermsReconsent).toHaveBeenCalledTimes(1)

    await act(async () => {
      finish()
      await Promise.resolve()
    })
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('보내지 못하면 빨강 상자로 알리고 다시 누를 수 있다 (재현 이메일)', async () => {
    resetMockSession()
    await loginWithEmail('reconsent-fail@example.com', 'dongne2026')
    const user = userEvent.setup()
    render(ui)
    await user.click(screen.getByRole('checkbox', { name: '바뀐 서비스 이용약관에 동의해요' }))
    await user.click(agreeButton())

    expect((await screen.findByRole('alert')).textContent).toContain(
      '약관 동의를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(router.replace).not.toHaveBeenCalled()
    expect(getMockProfile()?.termsReconsentRequired).toBe(true)
    expect(isOff(agreeButton())).toBe(false)
    await user.click(agreeButton())
    expect(agreeTermsReconsent).toHaveBeenCalledTimes(2)
  })

  it('응답 전에 화면을 떠나면 늦은 응답으로 이동하지 않는다 (동의는 남는다)', async () => {
    let finish: () => void = () => {}
    vi.mocked(agreeTermsReconsent).mockImplementationOnce(
      () => new Promise<void>((resolve) => (finish = resolve)),
    )
    const user = userEvent.setup()
    const { unmount } = render(ui)
    await user.click(screen.getByRole('checkbox', { name: '바뀐 서비스 이용약관에 동의해요' }))
    await user.click(agreeButton())
    unmount()
    await act(async () => {
      finish()
      await Promise.resolve()
    })
    expect(router.replace).not.toHaveBeenCalled()
  })
})

const logoutButton = () => screen.getByRole('button', { name: '동의하지 않고 로그아웃' })

describe('TermsReconsentScreen 동의하지 않고 로그아웃 (시안에 없는 기본안)', () => {
  it('로그아웃하면 비회원이 되고 동네만 남긴 홈으로 기록을 바꿔 간다', async () => {
    search = 'next=/me&region=11440660&mock-provider=email&mock-required=terms'
    render(ui)
    await userEvent.setup().click(logoutButton())

    expect(logout).toHaveBeenCalledTimes(1)
    expect(agreeTermsReconsent).not.toHaveBeenCalled()
    expect(getMockSession()).toBe('guest')
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/?region=11440660')
  })

  it('동네가 없으면 홈으로만 간다 (체크하지 않아도 누를 수 있다)', async () => {
    render(ui)
    expect(isOff(logoutButton())).toBe(false)
    await userEvent.setup().click(logoutButton())
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('보내는 동안 두 버튼이 모두 꺼지고 한 번만 보낸다', async () => {
    let finish: () => void = () => {}
    vi.mocked(logout).mockImplementationOnce(
      () => new Promise<void>((resolve) => (finish = resolve)),
    )
    const user = userEvent.setup()
    render(ui)
    await user.click(screen.getByRole('checkbox', { name: '바뀐 서비스 이용약관에 동의해요' }))
    await user.click(logoutButton())
    expect(isOff(logoutButton())).toBe(true)
    expect(isOff(agreeButton())).toBe(true)
    await user.click(logoutButton())
    await user.click(agreeButton())
    expect(logout).toHaveBeenCalledTimes(1)
    expect(agreeTermsReconsent).not.toHaveBeenCalled()

    await act(async () => {
      finish()
      await Promise.resolve()
    })
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('로그아웃하지 못하면 빨강 상자로 알리고 화면에 남는다 (재현 이메일)', async () => {
    resetMockSession()
    await loginWithEmail('logout-fail@example.com', 'dongne2026')
    search = 'mock-required=terms'
    const user = userEvent.setup()
    render(ui)
    await user.click(logoutButton())

    expect((await screen.findByRole('alert')).textContent).toContain(
      '로그아웃하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(getMockSession()).toBe('member-no-consent')
    expect(router.replace).not.toHaveBeenCalled()
    expect(isOff(logoutButton())).toBe(false)
  })

  it('응답 전에 화면을 떠나면 늦은 응답으로 이동하지 않는다 (로그아웃은 남는다)', async () => {
    let finish: () => void = () => {}
    vi.mocked(logout).mockImplementationOnce(
      () => new Promise<void>((resolve) => (finish = resolve)),
    )
    const { unmount } = render(ui)
    await userEvent.setup().click(logoutButton())
    unmount()
    await act(async () => {
      finish()
      await Promise.resolve()
    })
    expect(router.replace).not.toHaveBeenCalled()
  })
})

describe('TermsReconsentScreen 들어올 수 없을 때', () => {
  it('비회원이면 그리지 않고 next 로 보낸다', () => {
    resetMockSession()
    search = 'next=/me'
    const { container } = render(ui)
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/me')
  })

  it('재동의 조건이 없는 회원은 next 로, 목록 밖 next 는 홈으로 보낸다', async () => {
    resetMockSession()
    await loginWithEmail('dong@example.com', 'dongne2026')
    search = 'next=https://evil.example'
    render(ui)
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('동네 조건만 있으면 동네 다시 고르기로 보낸다', () => {
    resetMockSession()
    search = 'next=/me&mock-auth=member&mock-required=region'
    render(ui)
    expect(router.replace).toHaveBeenCalledWith(
      '/setup/region?reselect=1&next=%2Fme&mock-auth=member&mock-required=region',
    )
  })

  it('하이드레이션 첫 그림(비회원)으로 판단하지 않는다 — 회원이면 하이드레이션 뒤 화면을 그린다', () => {
    const container = document.createElement('div')
    document.body.append(container)
    container.innerHTML = renderToString(ui)
    expect(container.textContent).toBe('')

    render(ui, { container, hydrate: true })
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('바뀐 약관을 확인해 주세요')
    expect(router.replace).not.toHaveBeenCalled()
  })
})

describe('TermsReconsentScreen 로그인 만료', () => {
  it('재동의 화면에서 만료되면 홈으로 덮어쓰지 않고 만료 토스트가 있는 로그인 화면으로만 간다', () => {
    clearSessionExpiring()
    render(
      <OnboardingProvider>
        <TermsReconsentScreen />
        <SessionExpiryWatcher />
      </OnboardingProvider>,
    )
    expect(router.replace).not.toHaveBeenCalled()

    // 세션이 비회원이 되어 조건이 사라지면 화면은 다음 곳(홈)으로 보내려 한다 — 만료 중에는 보내지 않아야 한다
    act(() => notifySessionExpired())

    expect(router.replace.mock.calls).toEqual([['/login?reason=expired']])
    clearSessionExpiring()
  })
})

describe('TermsReconsentScreen 실데이터 모드 (새로고침 뒤 세션 복원)', () => {
  beforeEach(() => {
    selectApiSource()
  })

  afterEach(() => {
    resetApiSession()
  })

  it('복원 중에는 다른 곳으로 보내지 않고, 재동의할 항목이 있는 회원으로 정해지면 화면을 보인다', async () => {
    const server = holdReissue()
    render(ui)
    let restoring: Promise<void> = Promise.resolve()
    act(() => {
      restoring = restoreSession()
    })
    expect(router.replace).not.toHaveBeenCalled()
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull()

    await act(async () => {
      server.succeed(memberToken({ pendingConsents: ['TERMS_OF_SERVICE'] }))
      await restoring
    })
    expect(router.replace).not.toHaveBeenCalled()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('바뀐 약관을 확인해 주세요')
  })

  it('복원이 비회원으로 끝난 뒤에만 홈으로 보낸다', async () => {
    const server = holdReissue()
    render(ui)
    let restoring: Promise<void> = Promise.resolve()
    act(() => {
      restoring = restoreSession()
    })
    expect(router.replace).not.toHaveBeenCalled()

    await act(async () => {
      server.fail('AUTH_014', 401)
      await restoring
    })
    expect(router.replace.mock.calls).toEqual([['/']])
  })
})
