// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  EMPTY_PASSWORD_RESET,
  EMPTY_SIGNUP,
  OnboardingProvider,
  type PasswordResetDraft,
  type SignupDraft,
  useOnboarding,
} from '@/features/onboarding/onboarding-context'

import type * as authClient from './auth-client'
import {
  type PasswordResetVerifyResult,
  sendEmailCode,
  sendPasswordResetCode,
  verifyEmailCode,
  verifyPasswordResetCode,
} from './auth-client'
import { PasswordResetCodeScreen } from './password-reset-code-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/password/reset/code',
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return {
    ...actual,
    sendEmailCode: vi.fn(actual.sendEmailCode),
    verifyEmailCode: vi.fn(actual.verifyEmailCode),
    sendPasswordResetCode: vi.fn(actual.sendPasswordResetCode),
    verifyPasswordResetCode: vi.fn(actual.verifyPasswordResetCode),
  }
})

const EMAIL = 'dong@example.com'

function Probe() {
  const { passwordReset, signup } = useOnboarding()
  return (
    <span hidden data-testid="drafts">
      {JSON.stringify({ passwordReset, signup })}
    </span>
  )
}
const drafts = () =>
  JSON.parse(screen.getByTestId('drafts').textContent ?? '{}') as {
    passwordReset: PasswordResetDraft
    signup: SignupDraft
  }

function setup(draft: Partial<PasswordResetDraft> = {}) {
  const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })
  const initial = { ...EMPTY_PASSWORD_RESET, email: EMAIL, codeSentAt: Date.now(), ...draft }
  const utils = render(
    <OnboardingProvider initialPasswordReset={initial}>
      <PasswordResetCodeScreen />
      <Probe />
    </OnboardingProvider>,
  )
  return { user, ...utils }
}

const codeInput = () => screen.getByRole('textbox', { name: '인증 코드 6자리' })
const confirmButton = () => screen.getByRole('button', { name: '확인' })
const resendButton = () => screen.getByRole('button', { name: /^다시 받기/ })
const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

describe('PasswordResetCodeScreen', () => {
  beforeEach(async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    router.push.mockClear()
    router.replace.mockClear()
    router.back.mockClear()
    vi.mocked(sendEmailCode).mockReset()
    vi.mocked(verifyEmailCode).mockReset()
    vi.mocked(sendPasswordResetCode).mockReset()
    vi.mocked(verifyPasswordResetCode).mockReset()
    // 목 서버에 이 이메일로 보낸 재설정 코드를 만든다
    await sendPasswordResetCode(EMAIL)
    vi.mocked(sendPasswordResetCode).mockClear()
  })

  afterEach(() => vi.useRealTimers())

  it('가입 여부를 드러내지 않는 재설정용 중립 문구를 늘 보인다', () => {
    setup()
    expect(screen.getByText('가입한 이메일이면 코드를 보내 드려요.')).toBeDefined()
    // 가입 화면의 문구는 쓰지 않는다
    expect(screen.queryByText(/코드 대신 안내 메일/)).toBeNull()
    expect(screen.getByRole('timer', { name: '남은 시간' }).textContent).toBe('5:00')
    expect(resendButton().textContent).toBe('다시 받기 1:00')
  })

  it('맞으면 재설정 초안에 일회용 토큰을 두고 새 비밀번호로 간다 — 가입 초안은 건드리지 않는다', async () => {
    const { user } = setup()
    await user.type(codeInput(), '482915')
    await user.click(confirmButton())
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/password/reset/new'))
    expect(verifyPasswordResetCode).toHaveBeenCalledWith(EMAIL, '482915')
    expect(verifyEmailCode).not.toHaveBeenCalled()
    expect(drafts().passwordReset.resetToken).toMatch(/^mock-reset-/)
    expect(drafts().signup).toEqual(EMPTY_SIGNUP)
  })

  it('틀리면 남은 시도를 알리고 5번째에 잠긴다', async () => {
    const { user } = setup()
    for (const remaining of [4, 3, 2, 1]) {
      await user.clear(codeInput())
      await user.type(codeInput(), '000000')
      await user.click(confirmButton())
      await screen.findByText(`코드가 맞지 않아요. 남은 시도는 ${remaining}번이에요.`)
    }
    await user.clear(codeInput())
    await user.type(codeInput(), '000000')
    await user.click(confirmButton())
    expect((await screen.findByRole('alert')).textContent).toContain('시도 횟수를 넘겼어요')
  })

  it('잠기면 두 버튼을 끄고 이메일 다시 입력하기는 재설정 이메일 단계로 간다', async () => {
    const { user } = setup()
    await user.type(codeInput(), '999999')
    await user.click(confirmButton())
    expect((await screen.findByRole('alert')).textContent).toContain('시도 횟수를 넘겼어요')
    expect(isOff(confirmButton())).toBe(true)
    expect(isOff(resendButton())).toBe(true)

    await user.click(screen.getByRole('button', { name: '이메일 다시 입력하기' }))
    expect(router.replace).toHaveBeenCalledWith('/password/reset')
  })

  it('5:00 이 지나면 만료로 확인을 끄고, 다시 받으면 재설정 코드를 새로 보낸다', async () => {
    const { user } = setup()
    act(() => {
      vi.advanceTimersByTime(300_000)
    })
    expect(screen.getByRole('alert').textContent).toBe(
      '입력 시간이 지났어요. 코드를 다시 받아 주세요.',
    )
    expect(isOff(confirmButton())).toBe(true)

    await user.click(resendButton())
    await waitFor(() =>
      expect(screen.getByRole('timer', { name: '남은 시간' }).textContent).toBe('5:00'),
    )
    expect(sendPasswordResetCode).toHaveBeenCalledWith(EMAIL)
    expect(sendEmailCode).not.toHaveBeenCalled()
  })

  it('다시 받기가 막히면 조금 뒤 다시 하라고 알린다', async () => {
    const { user } = setup({ codeSentAt: Date.now() - 61_000 })
    vi.mocked(sendPasswordResetCode).mockResolvedValueOnce({ status: 'limit' })
    await user.click(resendButton())
    expect((await screen.findByRole('alert')).textContent).toBe(
      '코드 요청이 많아 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.',
    )
  })

  it('다시 받으면 앞선 토큰을 지운다', async () => {
    const { user } = setup({ resetToken: 'token-1', codeSentAt: Date.now() - 61_000 })
    await user.click(resendButton())
    await waitFor(() => expect(drafts().passwordReset.resetToken).toBeNull())
    expect(screen.getByRole('timer', { name: '남은 시간' }).textContent).toBe('5:00')
  })

  it('인증을 마치고 돌아왔으면 다시 묻지 않고 새 비밀번호로 간다', async () => {
    const { user } = setup({ resetToken: 'token-1' })
    expect(screen.getByRole('status').textContent).toContain('인증을 마쳤어요')
    await user.click(confirmButton())
    expect(verifyPasswordResetCode).not.toHaveBeenCalled()
    expect(router.push).toHaveBeenCalledWith('/password/reset/new')
  })

  it('보낸 이메일이 없으면 그리지 않고 재설정 이메일 단계로 돌려보낸다', () => {
    setup({ email: '', codeSentAt: null })
    expect(screen.queryByRole('heading')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
    expect(router.replace).toHaveBeenCalledWith('/password/reset')
  })

  it('가입 초안에만 이메일 · 보낸 시각이 있어도 재설정 코드 단계는 열리지 않는다', () => {
    const { container } = render(
      <OnboardingProvider initialSignup={{ ...EMPTY_SIGNUP, email: EMAIL, codeSentAt: Date.now() }}>
        <PasswordResetCodeScreen />
      </OnboardingProvider>,
    )
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/password/reset')
  })

  it('확인을 기다리는 동안 화면을 떠나면 이동하지 않지만, 받은 토큰은 Provider 에 남긴다', async () => {
    let resolve: (value: PasswordResetVerifyResult) => void = () => {}
    vi.mocked(verifyPasswordResetCode).mockImplementationOnce(
      () => new Promise((done) => (resolve = done)),
    )
    // Provider(레이아웃)는 남고 화면만 떠나는 경우
    function Tree({ show }: { show: boolean }) {
      return (
        <OnboardingProvider
          initialPasswordReset={{ ...EMPTY_PASSWORD_RESET, email: EMAIL, codeSentAt: Date.now() }}
        >
          {show && <PasswordResetCodeScreen />}
          <Probe />
        </OnboardingProvider>
      )
    }
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })
    const { rerender } = render(<Tree show />)
    await user.type(codeInput(), '482915')
    await user.click(confirmButton())
    rerender(<Tree show={false} />)
    await act(async () => {
      resolve({ status: 'ok', resetToken: 'token-late' })
      await Promise.resolve()
    })
    expect(router.push).not.toHaveBeenCalled()
    // 서버는 코드를 이미 썼다. 토큰을 버리면 코드를 다시 받아야 한다
    expect(drafts().passwordReset.resetToken).toBe('token-late')
  })
})
