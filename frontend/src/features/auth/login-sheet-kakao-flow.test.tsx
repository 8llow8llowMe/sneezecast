// @vitest-environment jsdom
import type { ReactNode } from 'react'

import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { AdultScreen } from '@/features/onboarding/adult-screen'
import { OnboardingProvider } from '@/features/onboarding/onboarding-context'
import { FROM_KAKAO, FROM_PARAM } from '@/features/onboarding/paths'
import { RegionScreen } from '@/features/onboarding/region-screen'

import type * as authClient from './auth-client'
import { getMockSession, loginWithEmail, resetMockSession, signup } from './auth-client'
import { HealthConsentScreen } from './health-consent-screen'
import { clearLoginReturn, LOGIN_RETURN_STORAGE_KEY } from './login-return-store'
import { LoginSheet } from './login-sheet'
import { TermsScreen } from './terms-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
const location = vi.hoisted(() => ({ pathname: '/' }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => location.pathname,
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, signup: vi.fn(actual.signup), loginWithEmail: vi.fn(actual.loginWithEmail) }
})

/**
 * 홈의 로그인 안내 시트는 첫 진입 Provider 밖에 있어 가입 종류를 둘 수 없다. 카카오에서 돌아온 주소(`?from=kakao`)를
 * S02-1 이 받아 가입 종류를 카카오로 두므로, 동네 · 성인 확인을 거쳐 S02-3 이 카카오 가입을 보낸다.
 * 시트가 떠나기 전에 둔 돌아갈 곳(보고하려던 로그인 · 둘러보던 동네)은 카카오 왕복(문서를 새로 엶) 뒤 저장소에서 살아나
 * 가입 마무리(S02-4)가 같은 동네 홈의 보고 진입으로 보낸다(#140).
 */
describe('홈 로그인 안내 시트 → 카카오 → 가입 마무리', () => {
  it('시트 → 카카오 → S02-1(from=kakao) → S02-2 → S02-3 제출이 카카오 가입을 보낸다', async () => {
    resetMockSession()
    const user = userEvent.setup()

    // 1) 홈 위 시트(Provider 밖)에서 카카오로 시작
    clearLoginReturn()
    const sheet = render(<LoginSheet open onClose={() => {}} regionCode="11680640" />)
    await user.click(screen.getByRole('button', { name: '카카오로 계속하기' }))
    await waitFor(() => expect(router.push).toHaveBeenCalledTimes(1))
    const redirect = new URL(String(router.push.mock.calls[0]?.[0]), 'http://localhost')
    expect(redirect.pathname).toBe('/setup/region')
    sheet.unmount()

    // 카카오 왕복을 흉내 낸다: 문서를 새로 열면 모듈 변수는 비고 저장소만 남는다
    const saved = window.sessionStorage.getItem(LOGIN_RETURN_STORAGE_KEY)
    expect(saved).not.toBeNull()
    clearLoginReturn()
    window.sessionStorage.setItem(LOGIN_RETURN_STORAGE_KEY, saved ?? '')

    // 2) 돌아온 주소로 첫 진입 화면을 새 Provider 로 그린다 (홈 → 첫 진입은 레이아웃이 새로 그려진다)
    const fromKakao = redirect.searchParams.get(FROM_PARAM) === FROM_KAKAO
    const tree = (child: ReactNode) => <OnboardingProvider>{child}</OnboardingProvider>
    location.pathname = '/setup/region'
    const flow = render(tree(<RegionScreen fromKakao={fromKakao} />))

    await user.type(screen.getByRole('searchbox', { name: '행정동 이름' }), '역삼')
    await user.click((await screen.findAllByRole('radio'))[0]!)
    await user.click(screen.getByRole('button', { name: '다음' }))
    expect(router.push).toHaveBeenLastCalledWith('/setup/adult')

    location.pathname = '/setup/adult'
    flow.rerender(tree(<AdultScreen />))
    await user.click(screen.getByRole('checkbox', { name: '성인 본인의 건강 상태만 보고할게요' }))
    await user.click(screen.getByRole('button', { name: '다음' }))
    expect(router.push).toHaveBeenLastCalledWith('/setup/terms')

    // 3) 가입 동의: 가입 종류가 카카오라 /login 으로 돌려보내지 않고 카카오 가입을 보낸다
    location.pathname = '/setup/terms'
    flow.rerender(tree(<TermsScreen />))
    await user.click(screen.getByRole('checkbox', { name: '전체 동의' }))
    await user.click(screen.getByRole('button', { name: '동의하고 가입하기' }))

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
    expect(router.replace).not.toHaveBeenCalledWith('/login')
    expect(signup).toHaveBeenCalledTimes(1)
    expect(vi.mocked(signup).mock.calls[0]?.[0].kind).toBe('kakao')
    expect(loginWithEmail).not.toHaveBeenCalled()
    expect(getMockSession()).toBe('member-no-consent')

    // 4) 증상 보고 동의를 마치면 시트에서 보고하려던 같은 동네 홈의 보고 진입으로 간다
    location.pathname = '/setup/health-consent'
    flow.rerender(tree(<HealthConsentScreen />))
    await user.click(
      screen.getByRole('checkbox', { name: /건강·증상 정보\(민감정보\) 처리에 동의해요/ }),
    )
    await user.click(screen.getByRole('button', { name: '동의하고 시작하기' }))
    await waitFor(() =>
      expect(router.replace).toHaveBeenLastCalledWith('/?region=11680640&report=start'),
    )
    expect(window.sessionStorage.getItem(LOGIN_RETURN_STORAGE_KEY)).toBeNull()
  })
})
