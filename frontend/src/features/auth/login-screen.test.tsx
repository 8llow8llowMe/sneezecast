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

import type * as authClient from './auth-client'
import { startKakaoLogin } from './auth-client'
import type { LoginNotice } from './login-notice'
import { LoginScreen } from './login-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/login',
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, startKakaoLogin: vi.fn(actual.startKakaoLogin) }
})

/** 그만둔 이메일 가입 초안 */
const ABANDONED: SignupDraft = {
  email: 'dong@example.com',
  codeSentAt: 1,
  verificationToken: 'mock-verified',
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

function renderLogin(notice: LoginNotice | null = null, initialSignup: SignupDraft = EMPTY_SIGNUP) {
  return render(
    <OnboardingProvider initialSignup={initialSignup}>
      <LoginScreen notice={notice} />
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
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/setup/region'))
  })

  it('카카오를 기다리는 동안 · 이동하는 동안 버튼이 꺼진 채다', async () => {
    let resolve: (value: { redirectTo: string }) => void = () => {}
    vi.mocked(startKakaoLogin).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    renderLogin()
    const kakao = screen.getByRole<HTMLButtonElement>('button', { name: '카카오로 계속하기' })

    await userEvent.setup().click(kakao)
    expect(kakao.disabled).toBe(true)

    resolve({ redirectTo: '/setup/region' })
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/setup/region'))
    expect(kakao.disabled).toBe(true)
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

  it('들어오면 그만둔 가입의 비밀번호 · 인증 값을 지우고 이메일만 남긴다', () => {
    renderLogin(null, ABANDONED)
    expect(draft()).toEqual({ ...EMPTY_SIGNUP, email: 'dong@example.com' })
  })

  it('카카오로 시작하면 가입 초안을 모두 비운다', async () => {
    renderLogin(null, ABANDONED)
    await userEvent.setup().click(screen.getByRole('button', { name: '카카오로 계속하기' }))
    await waitFor(() => expect(router.push).toHaveBeenCalled())
    expect(draft()).toEqual(EMPTY_SIGNUP)
  })

  it('카카오를 기다리는 동안 화면을 떠나면 늦은 응답으로 이동하지 않는다', async () => {
    let resolve: (value: { redirectTo: string }) => void = () => {}
    vi.mocked(startKakaoLogin).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { unmount } = renderLogin()
    await userEvent.setup().click(screen.getByRole('button', { name: '카카오로 계속하기' }))
    unmount()
    await act(async () => {
      resolve({ redirectTo: '/setup/region' })
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

  it('kakao-exists 면 이메일로 로그인과 다른 카카오 계정으로 계속하기만 보인다', async () => {
    renderLogin('kakao-exists')
    const status = screen
      .getAllByRole('status')
      .find((region) => region.textContent?.includes('이미 이메일 회원으로'))
    expect(status).toBeDefined()

    expect(screen.queryByRole('button', { name: '이메일로 가입하기' })).toBeNull()
    expect(screen.getByRole('button', { name: '다른 카카오 계정으로 계속하기' })).toBeDefined()

    await userEvent.setup().click(screen.getByRole('button', { name: '이메일로 로그인' }))
    expect(router.push).toHaveBeenCalledWith('/login/email')
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
})
