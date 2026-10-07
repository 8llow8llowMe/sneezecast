// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  EMPTY_SIGNUP,
  OnboardingProvider,
  type SignupDraft,
  useOnboarding,
} from '@/features/onboarding/onboarding-context'
import { assignLocation } from '@/lib/location'
import { NavTrailProvider, useNavTrail } from '@/lib/use-nav-trail'

import type * as kakaoClient from './kakao-client'
import { type KakaoStartResult, startKakaoLogin } from './kakao-client'
import { LoginEmailScreen } from './login-email-screen'
import type { KakaoFailReason, LoginNotice } from './login-notice'
import { type LoginReturn, loginReturnFromSearch } from './login-return'
import {
  clearLoginReturn,
  LOGIN_RETURN_STORAGE_KEY,
  peekLoginReturn,
  saveLoginReturn,
} from './login-return-store'
import { LoginScreen } from './login-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
const pathname = vi.hoisted(() => ({ value: '/login' }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => pathname.value,
}))

vi.mock('./kakao-client', async (importOriginal) => {
  const actual = await importOriginal<typeof kakaoClient>()
  return { ...actual, startKakaoLogin: vi.fn(actual.startKakaoLogin) }
})
vi.mock('@/lib/location', () => ({ assignLocation: vi.fn() }))

const internal = (href: string): KakaoStartResult => ({ status: 'redirect', href, external: false })

/** 그만둔 이메일 가입 초안 */
const ABANDONED: SignupDraft = {
  method: 'email',
  email: 'dong@example.com',
  codeSentAt: 1,
  verifiedAt: 1,
  password: 'dongne2026',
  nickname: '동네지기',
}

function Probe() {
  const { signup } = useOnboarding()
  return (
    <span hidden data-testid="draft">
      {JSON.stringify(signup)}
    </span>
  )
}
const draft = () => JSON.parse(screen.getByTestId('draft').textContent ?? '{}') as SignupDraft

function renderLogin(
  notice: LoginNotice | null = null,
  initialSignup: SignupDraft = EMPTY_SIGNUP,
  loginReturn?: LoginReturn,
  kakaoReason: KakaoFailReason | null = null,
) {
  return render(
    <OnboardingProvider initialSignup={initialSignup}>
      <LoginScreen
        notice={notice}
        kakaoReason={kakaoReason}
        {...(loginReturn ? { loginReturn } : {})}
      />
      <Probe />
    </OnboardingProvider>,
  )
}

describe('LoginScreen', () => {
  beforeEach(() => {
    router.push.mockClear()
    router.replace.mockClear()
    router.back.mockClear()
    vi.mocked(startKakaoLogin).mockReset()
    vi.mocked(assignLocation).mockReset()
    clearLoginReturn()
  })

  it('기본은 카카오 · 이메일 가입 · 이메일 로그인을 보이고 알림이 없다', () => {
    renderLogin()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
      '계정을 만들거나로그인해 주세요',
    )
    expect(screen.getByRole('button', { name: '카카오로 계속하기' })).toBeDefined()
    expect(screen.getByRole('button', { name: '이메일로 가입하기' })).toBeDefined()
    expect(screen.getByRole('link', { name: '이메일로 로그인' }).getAttribute('href')).toBe(
      '/login/email',
    )
    expect(screen.queryByRole('alert')).toBeNull()
    // 단계 표시가 없다
    expect(screen.queryByText(/\/ 4/)).toBeNull()
  })

  it('카카오로 계속하기는 목에서 신규 회원으로 보고 동네 선택으로 간다', async () => {
    renderLogin()
    await userEvent.setup().click(screen.getByRole('button', { name: '카카오로 계속하기' }))
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/setup/region?from=kakao'))
  })

  it('카카오를 기다리는 동안 · 이동하는 동안 버튼이 꺼진 채다', async () => {
    let resolve: (value: KakaoStartResult) => void = () => {}
    vi.mocked(startKakaoLogin).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    renderLogin()
    const kakao = screen.getByRole<HTMLButtonElement>('button', { name: '카카오로 계속하기' })

    await userEvent.setup().click(kakao)
    expect(kakao.disabled).toBe(true)

    resolve(internal('/setup/region'))
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/setup/region'))
    expect(kakao.disabled).toBe(true)
  })

  it('실데이터 인가 주소면 앱 밖으로 문서를 옮기고(router 가 아님) 버튼을 꺼 둔다', async () => {
    vi.mocked(startKakaoLogin).mockResolvedValueOnce({
      status: 'redirect',
      href: 'https://kauth.kakao.com/oauth/authorize?state=s',
      external: true,
    })
    renderLogin()
    const kakao = screen.getByRole<HTMLButtonElement>('button', { name: '카카오로 계속하기' })
    await userEvent.setup().click(kakao)
    await waitFor(() =>
      expect(assignLocation).toHaveBeenCalledWith(
        'https://kauth.kakao.com/oauth/authorize?state=s',
      ),
    )
    expect(router.push).not.toHaveBeenCalled()
    expect(kakao.disabled).toBe(true)
    // 출처를 첫 인자로 넘긴다(테스트는 목 기본값)
    expect(startKakaoLogin).toHaveBeenCalledWith('mock', {})
  })

  it('카카오 화면에서 뒤로 와 bfcache 로 다시 보이면 버튼을 다시 켠다', async () => {
    vi.mocked(startKakaoLogin).mockResolvedValueOnce({
      status: 'redirect',
      href: 'https://kauth.kakao.com/oauth/authorize?state=s',
      external: true,
    })
    renderLogin()
    const kakao = screen.getByRole<HTMLButtonElement>('button', { name: '카카오로 계속하기' })
    await userEvent.setup().click(kakao)
    await waitFor(() => expect(assignLocation).toHaveBeenCalled())
    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    })
    expect(kakao.disabled).toBe(false)
  })

  it('요청이 많으면(AUTH_028) 잠시 막혔다고 알리고 버튼을 다시 켠다', async () => {
    vi.mocked(startKakaoLogin).mockResolvedValueOnce({ status: 'limited' })
    renderLogin()
    const kakao = screen.getByRole<HTMLButtonElement>('button', { name: '카카오로 계속하기' })
    await userEvent.setup().click(kakao)
    await waitFor(() => expect(kakao.disabled).toBe(false))
    expect(
      screen
        .getAllByRole('status')
        .some((region) => region.textContent?.includes('요청이 많아 잠시 막혔어요')),
    ).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
  })

  it('카카오 시작이 거부되면 토스트로 알리고 버튼을 다시 켠다', async () => {
    vi.mocked(startKakaoLogin).mockRejectedValueOnce(new Error('network'))
    renderLogin()
    const kakao = screen.getByRole<HTMLButtonElement>('button', { name: '카카오로 계속하기' })

    await userEvent.setup().click(kakao)
    await waitFor(() => expect(kakao.disabled).toBe(false))
    expect(
      screen
        .getAllByRole('status')
        .some((region) => region.textContent?.includes('시작하지 못했어요')),
    ).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
  })

  it('들어오면 그만둔 가입의 가입 종류 · 인증 · 비밀번호를 지우고 이메일만 남긴다', () => {
    renderLogin(null, ABANDONED)
    expect(draft()).toEqual({ ...EMPTY_SIGNUP, email: 'dong@example.com' })
  })

  it('들어오면 그만둔 비밀번호 재설정의 보낸 시각 · 토큰도 지우고 이메일만 남긴다', () => {
    function ResetProbe() {
      const { passwordReset } = useOnboarding()
      return (
        <span hidden data-testid="reset">
          {JSON.stringify(passwordReset)}
        </span>
      )
    }
    render(
      <OnboardingProvider
        initialPasswordReset={{ email: 'dong@example.com', codeSentAt: 1, resetToken: 'token-1' }}
      >
        <LoginScreen notice={null} />
        <ResetProbe />
      </OnboardingProvider>,
    )
    expect(JSON.parse(screen.getByTestId('reset').textContent ?? '{}')).toEqual({
      email: 'dong@example.com',
      codeSentAt: null,
      resetToken: null,
    })
  })

  it('카카오로 시작하면 가입 초안을 모두 비우고 가입 종류를 카카오로 둔다', async () => {
    renderLogin(null, ABANDONED)
    await userEvent.setup().click(screen.getByRole('button', { name: '카카오로 계속하기' }))
    await waitFor(() => expect(router.push).toHaveBeenCalled())
    expect(draft()).toEqual({ ...EMPTY_SIGNUP, method: 'kakao' })
  })

  it('카카오를 기다리는 동안 화면을 떠나면 늦은 응답으로 이동하지 않는다', async () => {
    let resolve: (value: KakaoStartResult) => void = () => {}
    vi.mocked(startKakaoLogin).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { unmount } = renderLogin()
    await userEvent.setup().click(screen.getByRole('button', { name: '카카오로 계속하기' }))
    unmount()
    await act(async () => {
      resolve(internal('/setup/region'))
      await Promise.resolve()
    })
    expect(router.push).not.toHaveBeenCalled()
  })

  it('이메일로 가입하기는 이메일 가입으로 간다', async () => {
    renderLogin()
    await userEvent.setup().click(screen.getByRole('button', { name: '이메일로 가입하기' }))
    expect(router.push).toHaveBeenCalledWith('/signup/email')
  })

  it('kakao-fail 이면 실패를 alert 로 알린다', () => {
    renderLogin('kakao-fail')
    expect(screen.getByRole('alert').textContent).toBe(
      '카카오 로그인을 마치지 못했어요. 다시 시도해 주세요.',
    )
    expect(screen.getByRole('button', { name: '카카오로 계속하기' })).toBeDefined()
  })

  it.each([
    [
      'email-required',
      '카카오 계정의 이메일을 받지 못했어요. 다시 시도할 때 이메일 제공에 동의해 주세요.',
    ],
    [
      'email-unverified',
      '카카오 계정의 이메일이 인증되지 않았어요. 카카오에서 이메일을 인증한 뒤 다시 시도해 주세요.',
    ],
    ['expired', '시간이 지나 카카오 로그인을 마치지 못했어요. 카카오 로그인부터 다시 해 주세요.'],
    ['suspended', '이용이 정지된 계정이에요.'],
  ] as const)('kakao-fail 사유 %s 면 사유별 문장을 알린다', (reason, text) => {
    renderLogin('kakao-fail', EMPTY_SIGNUP, undefined, reason)
    expect(screen.getByRole('alert').textContent).toBe(text)
    expect(screen.getByRole('button', { name: '카카오로 계속하기' })).toBeDefined()
  })

  it('사유는 kakao-fail 일 때만 보인다', () => {
    renderLogin(null, EMPTY_SIGNUP, undefined, 'expired')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('들어오면 그만둔 카카오 계정 연결 확인의 가린 이메일을 지운다', () => {
    function LinkProbe() {
      const { kakaoLinkEmail } = useOnboarding()
      return <span data-testid="link">{kakaoLinkEmail ?? 'none'}</span>
    }
    render(
      <OnboardingProvider initialKakaoLinkEmail="d***@example.com">
        <LoginScreen notice={null} />
        <LinkProbe />
      </OnboardingProvider>,
    )
    expect(screen.getByTestId('link').textContent).toBe('none')
  })

  it('expired 면 다시 로그인하라는 토스트를 띄운다', async () => {
    renderLogin('expired')
    await waitFor(() =>
      expect(
        screen
          .getAllByRole('status')
          .some((region) => region.textContent === '다시 로그인해 주세요'),
      ).toBe(true),
    )
  })

  it('주소로 바로 들어왔으면 뒤로는 시작 화면으로 바꿔 간다', async () => {
    renderLogin()
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/start')
  })

  it('돌아갈 곳(?next=/me)을 이메일 로그인 링크에 이어 넘긴다', () => {
    const ret = { next: '/me', region: '11440660', intent: null }
    renderLogin(null, EMPTY_SIGNUP, ret)
    expect(screen.getByRole('link', { name: '이메일로 로그인' }).getAttribute('href')).toBe(
      '/login/email?next=%2Fme&region=11440660',
    )
  })

  it('보고하려던 로그인(?intent=report)을 이메일 로그인 링크에 이어 넘긴다', () => {
    renderLogin(null, EMPTY_SIGNUP, { next: '/', region: '11680640', intent: 'report' })
    expect(screen.getByRole('link', { name: '이메일로 로그인' }).getAttribute('href')).toBe(
      '/login/email?region=11680640&intent=report',
    )
  })

  it('돌아갈 곳이 없으면 이메일 로그인 링크에 쿼리가 없다', () => {
    renderLogin()
    expect(screen.getByRole('link', { name: '이메일로 로그인' }).getAttribute('href')).toBe(
      '/login/email',
    )
  })

  describe('카카오 · 이메일 가입으로 떠날 때 돌아갈 곳을 둔다 (#140)', () => {
    const ME = { next: '/me', region: '11440660', intent: null }

    it('카카오로 계속하기(실데이터 인가 화면)는 문서를 옮기기 전에 저장소에 둔다', async () => {
      vi.mocked(startKakaoLogin).mockResolvedValueOnce({
        status: 'redirect',
        href: 'https://kauth.kakao.com/oauth/authorize?state=s',
        external: true,
      })
      // 문서를 옮기는 때에는 이미 저장소에 있어야 한다(콜백은 새 문서다)
      let storedAtLeave: string | null = null
      vi.mocked(assignLocation).mockImplementationOnce(() => {
        storedAtLeave = window.sessionStorage.getItem(LOGIN_RETURN_STORAGE_KEY)
      })
      renderLogin(null, EMPTY_SIGNUP, ME)
      await userEvent.setup().click(screen.getByRole('button', { name: '카카오로 계속하기' }))
      await waitFor(() => expect(assignLocation).toHaveBeenCalled())
      expect(JSON.parse(storedAtLeave ?? 'null')).toMatchObject({
        v: 1,
        next: '/me',
        region: '11440660',
        intent: null,
      })
    })

    it('카카오로 계속하기(목)도 같은 문서 안에서 들고 간다', async () => {
      renderLogin(null, EMPTY_SIGNUP, { next: '/', region: '11680640', intent: 'report' })
      await userEvent.setup().click(screen.getByRole('button', { name: '카카오로 계속하기' }))
      // 둘러보던 동네는 가입 동네 고르기의 처음 선택으로도 싣는다(#227)
      await waitFor(() =>
        expect(router.push).toHaveBeenCalledWith('/setup/region?from=kakao&region=11680640'),
      )
      expect(peekLoginReturn()).toEqual({ next: '/', region: '11680640', intent: 'report' })
    })

    it('카카오를 시작하지 못하면 두지 않는다', async () => {
      vi.mocked(startKakaoLogin).mockResolvedValueOnce({ status: 'limited' })
      renderLogin(null, EMPTY_SIGNUP, ME)
      await userEvent.setup().click(screen.getByRole('button', { name: '카카오로 계속하기' }))
      await screen.findByText('요청이 많아 잠시 막혔어요. 잠시 뒤 다시 시도해 주세요.')
      expect(peekLoginReturn()).toEqual({ next: '/', region: null, intent: null })
    })

    it('이메일로 가입하기는 둔 뒤 이메일 가입으로 간다', async () => {
      renderLogin(null, EMPTY_SIGNUP, ME)
      await userEvent.setup().click(screen.getByRole('button', { name: '이메일로 가입하기' }))
      expect(router.push).toHaveBeenCalledWith('/signup/email')
      expect(peekLoginReturn()).toEqual(ME)
    })

    it('돌아갈 곳이 없는 로그인에서 떠나면 앞서 그만둔 흐름의 값을 지운다', async () => {
      saveLoginReturn(ME)
      renderLogin()
      await userEvent.setup().click(screen.getByRole('button', { name: '이메일로 가입하기' }))
      expect(peekLoginReturn()).toEqual({ next: '/', region: null, intent: null })
      expect(window.sessionStorage.getItem(LOGIN_RETURN_STORAGE_KEY)).toBeNull()
    })
  })
})

describe('LoginScreen 뒤로 — 앱 안 이동 기록', () => {
  beforeEach(() => {
    router.replace.mockClear()
    router.back.mockClear()
  })

  /** 앱 안에서 앞 화면(`from`)을 지나 로그인에 온 것처럼 그린다 */
  function visitLogin(from: string, loginReturn?: LoginReturn) {
    pathname.value = from
    const tree = () => (
      <NavTrailProvider>
        {pathname.value === '/login' ? (
          <OnboardingProvider>
            <LoginScreen notice={null} {...(loginReturn ? { loginReturn } : {})} />
          </OnboardingProvider>
        ) : (
          <div />
        )}
      </NavTrailProvider>
    )
    const { rerender } = render(tree())
    pathname.value = '/login'
    rerender(tree())
  }

  it('내 정보 가드가 보낸 로그인(?next=/me)이면 앞 화면이 무엇이든 기록을 되돌린다', async () => {
    visitLogin('/', { next: '/me', region: null, intent: null })
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('보고하려던 로그인(?intent=report)이면 보고 버튼을 누른 화면(지도 등)으로 기록을 되돌린다', async () => {
    visitLogin('/map', { next: '/', region: '11680640', intent: 'report' })
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('돌아갈 곳이 없으면 지금처럼 시작 화면에서 왔을 때만 되돌린다', async () => {
    visitLogin('/')
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/start')
  })
})

/**
 * 흐름 중간에 로그인 화면으로 돌려보낸 뒤의 뒤로 (#140 리뷰). 돌려보낼 때 돌아갈 곳을 쿼리로 다시 실어 "돌아갈 곳이 있는 로그인" 이 되지만,
 * 바로 앞 기록은 그만둔 가입 · 재설정 단계다 — 그 단계로 되돌리지 않고 시작 화면으로 기록을 바꿔 간다.
 */
describe('LoginScreen 뒤로 — 흐름 중간에 돌려보낸 로그인', () => {
  let search = ''
  let navTrail: ReturnType<typeof useNavTrail> | null = null
  function Probe() {
    navTrail = useNavTrail()
    return null
  }
  function Page() {
    const loginReturn = loginReturnFromSearch(new URLSearchParams(search))
    if (pathname.value === '/login') return <LoginScreen notice={null} loginReturn={loginReturn} />
    if (pathname.value === '/login/email') return <LoginEmailScreen loginReturn={loginReturn} />
    return <div />
  }
  const tree = () => (
    <NavTrailProvider>
      <Probe />
      <OnboardingProvider>
        <Page />
      </OnboardingProvider>
    </NavTrailProvider>
  )

  beforeEach(() => {
    router.replace.mockClear()
    router.back.mockClear()
    router.push.mockClear()
    clearLoginReturn()
  })

  /** 앱 안 이동(쌓음 · `replace`)을 차례로 흉내 낸다. 주소는 이 값으로 바뀐 것으로 본다 */
  function walk(steps: readonly (string | { replace: string })[]) {
    let view: ReturnType<typeof render> | null = null
    for (const step of steps) {
      const href = typeof step === 'string' ? step : step.replace
      if (typeof step !== 'string') act(() => navTrail?.replace(href))
      const url = new URL(href, 'http://localhost')
      pathname.value = url.pathname
      search = url.search.slice(1)
      if (view) view.rerender(tree())
      else view = render(tree())
    }
    router.replace.mockClear()
    return view
  }

  /** 지금 화면의 뒤로를 누른 뒤 `replace` 로 간 주소에 닿은 것처럼 다시 그린다 */
  async function backAndArrive(view: ReturnType<typeof render> | null) {
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    const href = String(router.replace.mock.calls.at(-1)?.[0])
    const url = new URL(href, 'http://localhost')
    pathname.value = url.pathname
    search = url.search.slice(1)
    view?.rerender(tree())
    return href
  }

  const GUARD_TO_SIGNUP = [
    '/me',
    { replace: '/login?next=%2Fme' },
    '/setup/region',
    '/setup/adult',
    '/setup/terms',
  ]

  it('카카오 가입표 만료(S02-3 → /login?error=kakao-fail&next=…)의 뒤로는 가입 단계가 아니라 시작 화면이다', async () => {
    walk([...GUARD_TO_SIGNUP, { replace: '/login?error=kakao-fail&kakao=expired&next=%2Fme' }])
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenLastCalledWith('/start')
  })

  it('가입된 이메일(S02-3 → 이메일 로그인)의 뒤로 · 그다음 뒤로가 가입 단계로 돌아가지 않는다', async () => {
    const view = walk([...GUARD_TO_SIGNUP, { replace: '/login/email?next=%2Fme' }])
    expect(await backAndArrive(view)).toBe('/login?next=%2Fme')
    router.replace.mockClear()
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenLastCalledWith('/start')
  })

  it('비밀번호 재설정 완료(→ /login/email?reason=reset-done&next=…)의 뒤로 · 그다음 뒤로가 재설정 단계로 돌아가지 않는다', async () => {
    const view = walk([
      '/me',
      { replace: '/login?next=%2Fme' },
      '/login/email?next=%2Fme',
      '/password/reset',
      '/password/reset/code',
      '/password/reset/new',
      { replace: '/login/email?reason=reset-done&next=%2Fme' },
    ])
    expect(await backAndArrive(view)).toBe('/login?next=%2Fme')
    router.replace.mockClear()
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenLastCalledWith('/start')
  })

  it('가드가 보낸 원래 화면이 바로 앞이면 지금처럼 기록을 되돌린다', async () => {
    // 홈 → 내 정보(가드가 /login?next=/me 로 맨 끝을 바꿈) — 바로 앞은 내 정보를 누른 홈이다
    walk(['/', '/me', { replace: '/login?next=%2Fme' }])
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
  })
})
