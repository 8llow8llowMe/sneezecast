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
import { sendEmailCode } from './auth-client'
import { SignupEmailScreen } from './signup-email-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/signup/email',
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, sendEmailCode: vi.fn(actual.sendEmailCode) }
})

/** Provider 에 남은 가입 초안을 읽는다 */
function Probe() {
  const { signup } = useOnboarding()
  return (
    <span hidden data-testid="draft">
      {JSON.stringify(signup)}
    </span>
  )
}
const draft = () => JSON.parse(screen.getByTestId('draft').textContent ?? '{}') as SignupDraft

function setup(initial: SignupDraft = EMPTY_SIGNUP) {
  const user = userEvent.setup()
  const utils = render(
    <OnboardingProvider initialSignup={initial}>
      <SignupEmailScreen />
      <Probe />
    </OnboardingProvider>,
  )
  const email = screen.getByRole('textbox', { name: '이메일' })
  const submit = screen.getByRole('button', { name: '인증 코드 받기' })
  return { user, email, submit, ...utils }
}

const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

describe('SignupEmailScreen', () => {
  beforeEach(() => {
    router.push.mockClear()
    router.replace.mockClear()
    vi.mocked(sendEmailCode).mockReset()
  })

  it('이메일 자동 완성 값을 쓰고 빈 칸이면 버튼이 꺼져 있다', () => {
    const { email, submit } = setup()
    expect(email.getAttribute('type')).toBe('email')
    expect(email.getAttribute('autocomplete')).toBe('email')
    expect(isOff(submit)).toBe(true)
  })

  it('입력 중에는 형식 오류를 띄우지 않고 보낼 때 알린다', async () => {
    const { user, email, submit } = setup()
    await user.type(email, 'dong@example')
    expect(email.getAttribute('aria-invalid')).toBeNull()

    await user.click(submit)
    expect(screen.getByRole('alert').textContent).toBe('이메일 형식을 확인해 주세요.')
    expect(email.getAttribute('aria-invalid')).toBe('true')
    expect(isOff(submit)).toBe(true)
    expect(sendEmailCode).not.toHaveBeenCalled()

    await user.type(email, '.com')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(isOff(submit)).toBe(false)
  })

  it('보내면 인증 코드로 간다', async () => {
    const { user, email, submit } = setup()
    await user.type(email, ' dong@example.com ')
    await user.click(submit)

    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/signup/code'))
    expect(sendEmailCode).toHaveBeenCalledWith('dong@example.com')
  })

  it('이미 가입된 이메일이면 이메일로 로그인 링크를 보인다', async () => {
    const { user, email, submit } = setup()
    await user.type(email, 'exists@example.com')
    await user.click(submit)

    expect((await screen.findByRole('alert')).textContent).toBe('이미 가입된 이메일이에요.')
    expect(screen.getByRole('link', { name: '이메일로 로그인' }).getAttribute('href')).toBe(
      '/login/email',
    )
    expect(isOff(submit)).toBe(true)
  })

  it('요청이 많으면 잠시 막혔다고 alert 로 알린다', async () => {
    const { user, email, submit } = setup()
    await user.type(email, 'limit@example.com')
    await user.click(submit)

    expect((await screen.findByRole('alert')).textContent).toContain('10분 뒤')
    expect(isOff(submit)).toBe(true)
  })

  it('보내는 중에는 칸을 고칠 수 없고 두 번 보내지 않는다', async () => {
    let resolve: (value: { status: 'sent' }) => void = () => {}
    vi.mocked(sendEmailCode).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { user, email, submit } = setup()
    await user.type(email, 'dong@example.com')
    await user.click(submit)
    await user.click(submit)

    expect(email.hasAttribute('readonly')).toBe(true)
    expect(sendEmailCode).toHaveBeenCalledTimes(1)
    resolve({ status: 'sent' })
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/signup/code'))
  })

  it('응답을 받지 못하면 다시 시도하라고 알린다', async () => {
    vi.mocked(sendEmailCode).mockRejectedValueOnce(new Error('network'))
    const { user, email, submit } = setup()
    await user.type(email, 'dong@example.com')
    await user.click(submit)
    expect((await screen.findByRole('alert')).textContent).toContain('보내지 못했어요')
  })

  it('다른 이메일로 보내면 이메일 · 보낸 시각을 새로 두고 앞선 인증을 지운다', async () => {
    const { user, email, submit } = setup({
      ...EMPTY_SIGNUP,
      email: 'old@example.com',
      codeSentAt: 1,
      verificationToken: 'mock-verified',
    })
    await user.clear(email)
    await user.type(email, 'new@example.com')
    await user.click(submit)

    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/signup/code'))
    expect(draft().email).toBe('new@example.com')
    expect(draft().verificationToken).toBeNull()
    expect(draft().codeSentAt).toBeGreaterThan(1)
  })

  it('보내는 동안 화면을 떠나면 늦은 응답을 버린다', async () => {
    let resolve: (value: { status: 'sent' }) => void = () => {}
    vi.mocked(sendEmailCode).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { user, email, submit, unmount } = setup()
    await user.type(email, 'dong@example.com')
    await user.click(submit)
    unmount()
    await act(async () => {
      resolve({ status: 'sent' })
      await Promise.resolve()
    })
    expect(router.push).not.toHaveBeenCalled()
  })

  it('이메일 바꾸기로 돌아오면 앞서 쓴 이메일이 남아 있다', () => {
    const { email } = setup({ ...EMPTY_SIGNUP, email: 'dong@example.com' })
    expect((email as HTMLInputElement).value).toBe('dong@example.com')
  })

  it('주소로 바로 들어왔으면 뒤로는 로그인으로 바꿔 간다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/login')
  })
})
