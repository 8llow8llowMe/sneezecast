// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react'
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
import { resetApiSession, selectApiSource } from '@/test/api-session'

import type * as authClient from './auth-client'
import { sendEmailCode, sendPasswordResetCode } from './auth-client'
import { PasswordResetEmailScreen } from './password-reset-email-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/password/reset',
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return {
    ...actual,
    sendEmailCode: vi.fn(actual.sendEmailCode),
    sendPasswordResetCode: vi.fn(actual.sendPasswordResetCode),
  }
})

/** Provider 에 남은 재설정 · 가입 초안을 읽는다 */
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

function setup(
  initial: PasswordResetDraft = EMPTY_PASSWORD_RESET,
  { verificationExpired = false } = {},
) {
  const user = userEvent.setup()
  const utils = render(
    <OnboardingProvider initialPasswordReset={initial}>
      <PasswordResetEmailScreen verificationExpired={verificationExpired} />
      <Probe />
    </OnboardingProvider>,
  )
  const email = screen.getByRole('textbox', { name: '이메일' })
  const submit = screen.getByRole('button', { name: '인증 코드 받기' })
  return { user, email, submit, ...utils }
}

describe('PasswordResetEmailScreen', () => {
  beforeEach(() => {
    router.push.mockClear()
    router.replace.mockClear()
    router.back.mockClear()
    vi.mocked(sendPasswordResetCode).mockReset()
    vi.mocked(sendEmailCode).mockReset()
  })

  afterEach(() => resetApiSession())

  it('실데이터 모드면 출처 api 로 코드를 받는다', async () => {
    selectApiSource()
    vi.mocked(sendPasswordResetCode).mockResolvedValueOnce({ status: 'sent' })
    const { user, email, submit } = setup()
    await user.type(email, 'dong@example.com')
    await user.click(submit)
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/password/reset/code'))
    expect(sendPasswordResetCode).toHaveBeenCalledWith('dong@example.com', 'api')
  })

  it('가입 이메일 화면을 제목만 바꿔 쓴다', () => {
    setup()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
      '가입한 이메일을 알려 주세요',
    )
    expect(screen.getByText('인증 코드를 보내 드릴게요.')).toBeDefined()
    expect(screen.queryByText('이메일을 알려 주세요')).toBeNull()
  })

  it('보내면 재설정 초안에 이메일 · 보낸 시각을 쓰고 코드 단계로 간다 — 가입 초안은 건드리지 않는다', async () => {
    const { user, email, submit } = setup()
    const before = Date.now()
    await user.type(email, ' dong@example.com ')
    await user.click(submit)

    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/password/reset/code'))
    expect(sendPasswordResetCode).toHaveBeenCalledWith('dong@example.com', 'mock')
    expect(sendEmailCode).not.toHaveBeenCalled()
    const { passwordReset, signup } = drafts()
    expect(passwordReset.email).toBe('dong@example.com')
    expect(passwordReset.codeSentAt).toBeGreaterThanOrEqual(before)
    expect(passwordReset.resetToken).toBeNull()
    expect(signup).toEqual(EMPTY_SIGNUP)
  })

  it('다시 보내면 앞선 토큰을 지운다', async () => {
    const { user, submit } = setup({
      email: 'dong@example.com',
      codeSentAt: 1,
      resetToken: 'token-1',
    })
    await user.click(submit)
    await waitFor(() => expect(router.push).toHaveBeenCalledTimes(1))
    expect(drafts().passwordReset.resetToken).toBeNull()
    expect(drafts().passwordReset.codeSentAt).toBeGreaterThan(1)
  })

  it('요청이 많으면 잠시 막혔다고 alert 로 알린다', async () => {
    const { user, email, submit } = setup()
    await user.type(email, 'limit@example.com')
    await user.click(submit)
    expect((await screen.findByRole('alert')).textContent).toBe(
      '코드 요청이 많아 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.',
    )
    expect(router.push).not.toHaveBeenCalled()
  })

  it('보내는 중에는 칸을 고칠 수 없고 두 번 보내지 않는다', async () => {
    let resolve: (value: { status: 'sent' }) => void = () => {}
    vi.mocked(sendPasswordResetCode).mockImplementationOnce(
      () => new Promise((done) => (resolve = done)),
    )
    const { user, email, submit } = setup()
    await user.type(email, 'dong@example.com')
    await user.click(submit)
    await user.click(submit)
    expect(sendPasswordResetCode).toHaveBeenCalledTimes(1)
    expect(email.hasAttribute('readonly')).toBe(true)
    expect(submit.getAttribute('aria-disabled')).toBe('true')
    resolve({ status: 'sent' })
    await waitFor(() => expect(router.push).toHaveBeenCalledTimes(1))
  })

  it('인증 시간이 지나 돌아왔으면 안내를 alert 로 띄우고 앞서 쓴 이메일을 남긴다', () => {
    const { email } = setup(
      { email: 'dong@example.com', codeSentAt: null, resetToken: null },
      { verificationExpired: true },
    )
    expect(screen.getByRole('alert').textContent).toBe(
      '인증 시간이 지났어요. 이메일 인증부터 다시 해 주세요.',
    )
    expect((email as HTMLInputElement).value).toBe('dong@example.com')
  })

  it('코드를 새로 보낸 뒤 돌아오면 쿼리가 남아 있어도 안내를 띄우지 않는다', () => {
    setup(
      { email: 'dong@example.com', codeSentAt: Date.now(), resetToken: null },
      { verificationExpired: true },
    )
    expect(screen.queryByText(/인증 시간이 지났어요/)).toBeNull()
  })

  it('주소로 바로 들어왔으면 뒤로는 이메일 로그인으로 바꿔 간다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/login/email')
  })
})
