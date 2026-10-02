// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  EMPTY_SIGNUP,
  OnboardingProvider,
  type PasswordResetDraft,
  useOnboarding,
} from '@/features/onboarding/onboarding-context'
import {
  getSessionSnapshot,
  resetSessionForTests,
  setSession,
  startSession,
} from '@/lib/session/session-store'
import {
  holdRequests,
  memberToken,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import type * as authClient from './auth-client'
import {
  type PasswordResetResult,
  resetPassword,
  sendPasswordResetCode,
  verifyPasswordResetCode,
} from './auth-client'
import { PasswordResetNewScreen } from './password-reset-new-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/password/reset/new',
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, resetPassword: vi.fn(actual.resetPassword) }
})

const EMAIL = 'dong@example.com'
/** 코드를 맞히고 목 서버에서 받은 토큰을 든 초안. 테스트마다 새 토큰을 받는다 */
let VERIFIED: PasswordResetDraft = { email: EMAIL, codeSentAt: 1, resetToken: null }

function Probe() {
  const { passwordReset } = useOnboarding()
  return (
    <span hidden data-testid="draft">
      {JSON.stringify(passwordReset)}
    </span>
  )
}
const draft = () =>
  JSON.parse(screen.getByTestId('draft').textContent ?? '{}') as PasswordResetDraft

function setup(initial: PasswordResetDraft = VERIFIED) {
  const user = userEvent.setup()
  const utils = render(
    <OnboardingProvider initialPasswordReset={initial}>
      <PasswordResetNewScreen />
      <Probe />
    </OnboardingProvider>,
  )
  return { user, ...utils }
}

const passwordInput = () => screen.getByLabelText('새 비밀번호', { selector: 'input' })
const confirmInput = () => screen.getByLabelText('새 비밀번호 확인', { selector: 'input' })
const submitButton = () => screen.getByRole('button', { name: '비밀번호 바꾸기' })
const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

async function fill(user: ReturnType<typeof userEvent.setup>, password: string, confirm: string) {
  await user.type(passwordInput(), password)
  await user.type(confirmInput(), confirm)
}

describe('PasswordResetNewScreen', () => {
  beforeEach(async () => {
    router.push.mockClear()
    router.replace.mockClear()
    router.back.mockClear()
    vi.mocked(resetPassword).mockReset()
    // 목 서버에서 이 이메일의 재설정 토큰을 받는다
    await sendPasswordResetCode(EMAIL, 'mock')
    const verified = await verifyPasswordResetCode(EMAIL, '482915', 'mock')
    if (verified.status !== 'ok') throw new Error('목 토큰을 받지 못했다')
    VERIFIED = { email: EMAIL, codeSentAt: 1, resetToken: verified.resetToken }
  })

  it('default: 시안 제목 · 가입과 같은 도움말을 보이고 빈 칸이면 버튼이 꺼져 있다', () => {
    setup()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('새 비밀번호를 정해 주세요')
    expect(screen.getByText('이메일 인증을 마쳤어요.')).toBeDefined()
    expect(screen.getByText('8~20자 · 영문과 숫자 포함 · 띄어쓰기 없이')).toBeDefined()
    expect(passwordInput().getAttribute('autocomplete')).toBe('new-password')
    expect(confirmInput().getAttribute('autocomplete')).toBe('new-password')
    expect(isOff(submitButton())).toBe(true)
    // 단계 표시가 없다
    expect(screen.queryByText(/\/ 4/)).toBeNull()
  })

  it('rule: 규칙에 맞지 않으면 누를 때 알리고 버튼을 끈다 — 고치면 지운다', async () => {
    const { user } = setup()
    await fill(user, 'abcdefgh', 'abcdefgh')
    expect(passwordInput().getAttribute('aria-invalid')).toBeNull()

    await user.click(submitButton())
    expect(screen.getByText('영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요.')).toBeDefined()
    expect(passwordInput().getAttribute('aria-invalid')).toBe('true')
    expect(isOff(submitButton())).toBe(true)
    expect(resetPassword).not.toHaveBeenCalled()

    await user.type(passwordInput(), '1')
    await user.type(confirmInput(), '1')
    expect(screen.queryByText(/영문과 숫자를 함께/)).toBeNull()
    expect(isOff(submitButton())).toBe(false)
  })

  it.each([
    ['20자 초과', 'abcdefghij1234567890x'],
    ['공백', 'newpass 2026'],
  ])('rule: %s 도 규칙 오류다 (가입과 같은 규칙)', async (_, value) => {
    const { user } = setup()
    await fill(user, value, value)
    await user.click(submitButton())
    expect(passwordInput().getAttribute('aria-invalid')).toBe('true')
    expect(resetPassword).not.toHaveBeenCalled()
  })

  it('mismatch: 확인이 다르면 확인 칸 아래 알리고 버튼을 끈다', async () => {
    const { user } = setup()
    await fill(user, 'newpass2026', 'newpass2025')
    await user.click(submitButton())
    expect(screen.getByText('비밀번호가 서로 달라요.')).toBeDefined()
    expect(confirmInput().getAttribute('aria-invalid')).toBe('true')
    expect(isOff(submitButton())).toBe(true)
    expect(resetPassword).not.toHaveBeenCalled()
  })

  it('바꾸면 재설정 초안을 비우고(이메일만 남긴다) 이메일 로그인으로 기록을 바꿔 간다', async () => {
    const { user } = setup()
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())

    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/login/email?reason=reset-done'),
    )
    expect(resetPassword).toHaveBeenCalledWith(VERIFIED.resetToken, 'newpass2026', EMAIL, 'mock')
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.push).not.toHaveBeenCalled()
    expect(draft()).toEqual({ email: EMAIL, codeSentAt: null, resetToken: null })
    // 새 비밀번호는 Provider 에 올리지 않는다
    expect(screen.getByTestId('draft').textContent).not.toContain('newpass2026')
  })

  it('인증 만료면 보낸 시각 · 토큰을 지우고 이메일 단계(안내)로 기록을 바꿔 간다', async () => {
    vi.mocked(resetPassword).mockResolvedValueOnce({ status: 'verification-expired' })
    const { user } = setup()
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())

    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/password/reset?reason=verification-expired'),
    )
    // 값이 없다고 안내 없는 이메일 단계로 한 번 더 보내지 않는다
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(draft()).toEqual({ email: EMAIL, codeSentAt: null, resetToken: null })
  })

  it('보내는 중에는 칸을 읽기 전용으로 두고 두 번 보내지 않는다', async () => {
    let resolve: (value: PasswordResetResult) => void = () => {}
    vi.mocked(resetPassword).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { user } = setup()
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())
    await user.click(submitButton())

    expect(resetPassword).toHaveBeenCalledTimes(1)
    expect(passwordInput().hasAttribute('readonly')).toBe(true)
    expect(confirmInput().hasAttribute('readonly')).toBe(true)
    expect(isOff(submitButton())).toBe(true)
    resolve({ status: 'ok' })
    await waitFor(() => expect(router.replace).toHaveBeenCalledTimes(1))
  })

  it('응답을 받지 못하면 alert 로 알리고 다시 누를 수 있다', async () => {
    vi.mocked(resetPassword).mockRejectedValueOnce(new Error('network'))
    const { user } = setup()
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())

    expect((await screen.findByRole('alert')).textContent).toBe(
      '비밀번호를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(passwordInput().hasAttribute('readonly')).toBe(false)
    expect(isOff(submitButton())).toBe(false)
    expect(draft()).toEqual(VERIFIED)

    await user.click(submitButton())
    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/login/email?reason=reset-done'),
    )
  })

  it('기다리는 동안 화면을 떠나면 이동하지 않지만, 서버에서 바뀐 것은 Provider 에 남긴다', async () => {
    let resolve: (value: PasswordResetResult) => void = () => {}
    vi.mocked(resetPassword).mockImplementationOnce(() => new Promise((done) => (resolve = done)))

    // Provider(레이아웃)는 남고 화면만 떠나는 경우
    function Tree({ show }: { show: boolean }) {
      return (
        <OnboardingProvider initialPasswordReset={VERIFIED}>
          {show && <PasswordResetNewScreen />}
          <Probe />
        </OnboardingProvider>
      )
    }
    const user = userEvent.setup()
    const { rerender } = render(<Tree show />)
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())
    rerender(<Tree show={false} />)

    await act(async () => {
      resolve({ status: 'ok' })
      await Promise.resolve()
    })
    expect(router.replace).not.toHaveBeenCalled()
    expect(draft()).toEqual({ email: EMAIL, codeSentAt: null, resetToken: null })
  })

  it('재설정 토큰이 없으면 그리지 않고 이메일 단계로 돌려보낸다', () => {
    setup({ email: EMAIL, codeSentAt: 1, resetToken: null })
    expect(screen.queryByRole('heading')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
    expect(router.replace).toHaveBeenCalledWith('/password/reset')
  })

  it('주소로 바로 들어왔으면 뒤로는 코드 단계로 바꿔 간다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/password/reset/code')
  })
  it('같은 토큰으로 두 번 바꾸지 못한다 — 이미 쓴 토큰이면 인증 만료로 돌아간다', async () => {
    const used = VERIFIED
    await resetPassword(used.resetToken ?? '', 'newpass2025', EMAIL, 'mock')
    vi.mocked(resetPassword).mockClear()
    const { user } = setup(used)
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())
    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/password/reset?reason=verification-expired'),
    )
  })

  it('보내는 중에는 머리줄 뒤로를 꺼 두고 눌러도 가지 않는다', async () => {
    let resolve: (value: PasswordResetResult) => void = () => {}
    vi.mocked(resetPassword).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { user } = setup()
    const back = screen.getByRole('button', { name: '뒤로' })
    expect(back.getAttribute('aria-disabled')).toBeNull()
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())

    expect(back.getAttribute('aria-disabled')).toBe('true')
    await user.click(back)
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).not.toHaveBeenCalled()
    resolve({ status: 'ok' })
    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/login/email?reason=reset-done'),
    )
  })

  it('가입 초안에만 인증 값이 있고 재설정 토큰이 없으면 이메일 단계로 돌려보낸다', () => {
    render(
      <OnboardingProvider
        initialSignup={{
          ...EMPTY_SIGNUP,
          method: 'email',
          email: EMAIL,
          codeSentAt: 1,
          verifiedAt: 2,
        }}
      >
        <PasswordResetNewScreen />
      </OnboardingProvider>,
    )
    expect(screen.queryByRole('heading')).toBeNull()
    expect(router.replace).toHaveBeenCalledWith('/password/reset')
  })

  it('재현용 이메일(reset-fail)로 받은 토큰이면 응답을 받지 못해 다시 누를 수 있다', async () => {
    await sendPasswordResetCode('reset-fail@example.com', 'mock')
    const verified = await verifyPasswordResetCode('reset-fail@example.com', '482915', 'mock')
    if (verified.status !== 'ok') throw new Error('목 토큰을 받지 못했다')
    const { user } = setup({
      email: 'reset-fail@example.com',
      codeSentAt: 1,
      resetToken: verified.resetToken,
    })
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())
    expect((await screen.findByRole('alert')).textContent).toBe(
      '비밀번호를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('limited(이 기기의 시도가 많음)면 회색 상자로 알리고 토큰을 남긴 채 다시 누를 수 있다', async () => {
    vi.mocked(resetPassword).mockResolvedValueOnce({ status: 'limited' })
    const { user } = setup()
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())

    expect((await screen.findByRole('alert')).textContent).toBe(
      '요청이 많아 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.',
    )
    expect(draft()).toEqual(VERIFIED)
    expect(isOff(submitButton())).toBe(false)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('서버가 규칙 위반으로 거절하면(invalid-password) 새 비밀번호 칸 아래 규칙 오류를 보이고 고칠 때까지 버튼을 끈다', async () => {
    vi.mocked(resetPassword).mockResolvedValueOnce({ status: 'invalid-password' })
    const { user } = setup()
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())

    await waitFor(() => expect(passwordInput().getAttribute('aria-invalid')).toBe('true'))
    expect(screen.getByText('영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요.')).toBeDefined()
    expect(isOff(submitButton())).toBe(true)
    expect(draft()).toEqual(VERIFIED)

    await user.type(passwordInput(), '7')
    expect(passwordInput().getAttribute('aria-invalid')).toBeNull()
  })
})

describe('PasswordResetNewScreen 실데이터 (#166)', () => {
  let stop: () => void = () => {}
  beforeEach(() => {
    router.replace.mockClear()
    vi.mocked(resetPassword).mockReset()
    resetSessionForTests()
    // 세션 저장소가 API 계층에 토큰을 준다 — 인증이 필요 없는 요청에 실리지 않는지 본다
    stop = startSession()
    selectApiSource()
  })
  afterEach(() => {
    stop()
    resetApiSession()
  })

  it('출처 api 로 보내고, 이 탭 계정을 모르면 로그아웃까지 마친 뒤 이메일 로그인으로 간다(서버가 그 계정의 모든 기기를 로그아웃)', async () => {
    const actual = await vi.importActual<typeof authClient>('./auth-client')
    vi.mocked(resetPassword).mockImplementation(actual.resetPassword)
    const server = holdRequests()
    setSession(memberToken())
    const { user } = setup({ email: EMAIL, codeSentAt: 1, resetToken: 'server-token' })
    await fill(user, 'newpass2026', 'newpass2026')
    await user.click(submitButton())

    expect(resetPassword).toHaveBeenCalledWith('server-token', 'newpass2026', EMAIL, 'api')
    await waitFor(() => expect(server.requests()).toEqual(['POST /api/v1/auth/password/reset']))
    // 인증이 필요 없는 요청이라 access 를 싣지 않는다
    const init = server.fetchMock.mock.calls[0]?.[1] as RequestInit | undefined
    expect(new Headers(init?.headers).get('Authorization')).toBeNull()
    expect(JSON.parse(init?.body as string)).toEqual({
      resetToken: 'server-token',
      newPassword: 'newpass2026',
    })

    act(() => server.reply('POST /api/v1/auth/password/reset', okResponse(null)))
    // 회원 정보 저장소가 켜져 있지 않아 이 탭 계정의 이메일을 모른다 — 로그아웃으로 서버 세션까지 끊는다
    await waitFor(() =>
      expect(server.requests()).toEqual([
        'POST /api/v1/auth/password/reset',
        'POST /api/v1/auth/logout',
      ]),
    )
    expect(router.replace).not.toHaveBeenCalled()
    act(() => server.reply('POST /api/v1/auth/logout', okResponse(null)))
    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/login/email?reason=reset-done'),
    )
    expect(getSessionSnapshot().status).toBe('guest')
  })
})
