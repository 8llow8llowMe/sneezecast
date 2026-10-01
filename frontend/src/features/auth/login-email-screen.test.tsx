// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { OnboardingProvider } from '@/features/onboarding/onboarding-context'

import type * as authClient from './auth-client'
import { loginWithEmail } from './auth-client'
import { LoginEmailScreen } from './login-email-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/login/email',
}))

// 실제 목을 쓰되 응답 지연 · 실패를 흉내 낼 수 있게 감싼다
vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, loginWithEmail: vi.fn(actual.loginWithEmail) }
})

function setup(resetDone = false) {
  const user = userEvent.setup()
  const utils = render(
    <OnboardingProvider>
      <LoginEmailScreen resetDone={resetDone} />
    </OnboardingProvider>,
  )
  const email = screen.getByRole('textbox', { name: '이메일' })
  const password = screen.getByLabelText('비밀번호', { selector: 'input' })
  const submit = screen.getByRole<HTMLButtonElement>('button', { name: '로그인' })
  return { user, email, password, submit, ...utils }
}

/** 로그인 버튼은 포커스를 지키려고 disabled 대신 aria-disabled 로 끈다 */
function isOff(button: HTMLElement) {
  return button.getAttribute('aria-disabled') === 'true'
}

async function fill(
  user: ReturnType<typeof userEvent.setup>,
  email: HTMLElement,
  password: HTMLElement,
  emailValue: string,
  passwordValue: string,
) {
  await user.type(email, emailValue)
  await user.type(password, passwordValue)
}

describe('LoginEmailScreen', () => {
  beforeEach(() => {
    router.push.mockClear()
    router.replace.mockClear()
    vi.mocked(loginWithEmail).mockReset()
  })

  it('입력칸은 로그인 자동 완성 값을 쓴다', () => {
    const { email, password } = setup()
    expect(email.getAttribute('type')).toBe('email')
    expect(email.getAttribute('autocomplete')).toBe('username')
    expect(password.getAttribute('autocomplete')).toBe('current-password')
  })

  it('빈 칸이 있으면 로그인이 꺼져 있고 눌러도 보내지 않는다', async () => {
    const { user, email, password, submit } = setup()
    expect(isOff(submit)).toBe(true)

    await user.type(email, 'dong@example.com')
    expect(isOff(submit)).toBe(true)
    await user.click(submit)
    expect(loginWithEmail).not.toHaveBeenCalled()

    await user.type(password, 'dongne2026')
    expect(isOff(submit)).toBe(false)
  })

  it('성공하면 기록을 바꿔 홈으로 간다', async () => {
    const { user, email, password, submit } = setup()
    await fill(user, email, password, 'dong@example.com', 'dongne2026')
    await user.click(submit)

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
    expect(loginWithEmail).toHaveBeenCalledWith('dong@example.com', 'dongne2026')
  })

  it('wrong 이면 맞지 않다고 alert 로 알리고 칸을 고치면 지운다', async () => {
    const { user, email, password, submit } = setup()
    await fill(user, email, password, 'dong@example.com', 'wrong')
    await user.click(submit)

    expect((await screen.findByRole('alert')).textContent).toBe(
      '이메일 또는 비밀번호가 맞지 않아요.',
    )
    expect(router.replace).not.toHaveBeenCalled()

    await user.type(password, '1')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('locked 면 alert 로 알리고 로그인을 끄되 포커스는 버튼에 남는다', async () => {
    const { user, email, password, submit } = setup()
    await fill(user, email, password, 'locked@example.com', 'dongne2026')
    await user.click(submit)

    expect((await screen.findByRole('alert')).textContent).toContain('10분 뒤')
    expect(isOff(submit)).toBe(true)
    // disabled 가 아니라서 포커스를 잃지 않는다
    expect(submit.disabled).toBe(false)
    expect(document.activeElement).toBe(submit)

    await user.click(submit)
    expect(loginWithEmail).toHaveBeenCalledTimes(1)
  })

  it('locked 는 비밀번호를 고쳐도 풀리지 않고 이메일을 바꾸면 풀린다', async () => {
    const { user, email, password, submit } = setup()
    await fill(user, email, password, 'locked@example.com', 'dongne2026')
    await user.click(submit)
    await screen.findByRole('alert')

    await user.type(password, '1')
    expect(isOff(submit)).toBe(true)
    expect(screen.queryByRole('alert')).not.toBeNull()

    await user.clear(email)
    await user.type(email, 'dong@example.com')
    expect(isOff(submit)).toBe(false)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('보내는 중에는 로그인이 꺼지고 칸을 고칠 수 없다', async () => {
    let resolve: (value: { status: 'ok' }) => void = () => {}
    vi.mocked(loginWithEmail).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { user, email, password, submit } = setup()
    await fill(user, email, password, 'dong@example.com', 'dongne2026')
    await user.click(submit)

    expect(isOff(submit)).toBe(true)
    expect(email.hasAttribute('readonly')).toBe(true)
    expect(password.hasAttribute('readonly')).toBe(true)
    await user.type(password, 'x')
    expect((password as HTMLInputElement).value).toBe('dongne2026')

    resolve({ status: 'ok' })
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
  })

  it('기다리는 동안 화면을 떠나면 늦은 응답으로 이동하지 않는다', async () => {
    let resolve: (value: { status: 'ok' }) => void = () => {}
    vi.mocked(loginWithEmail).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { user, email, password, submit, unmount } = setup()
    await fill(user, email, password, 'dong@example.com', 'dongne2026')
    await user.click(submit)
    unmount()
    await act(async () => {
      resolve({ status: 'ok' })
      await Promise.resolve()
    })
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('응답을 받지 못하면 다시 시도하라고 알리고 칸을 고치면 지운다', async () => {
    vi.mocked(loginWithEmail).mockRejectedValueOnce(new Error('network'))
    const { user, email, password, submit } = setup()
    await fill(user, email, password, 'dong@example.com', 'dongne2026')
    await user.click(submit)

    expect((await screen.findByRole('alert')).textContent).toBe(
      '로그인하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(email.hasAttribute('readonly')).toBe(false)

    await user.type(password, '1')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('reset-done 이면 비밀번호를 바꿨다는 토스트를 띄운다', async () => {
    setup(true)
    await waitFor(() =>
      expect(
        screen
          .getAllByRole('status')
          .some(
            (region) =>
              region.textContent === '비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.',
          ),
      ).toBe(true),
    )
  })

  it('비밀번호 찾기 · 가입 링크가 있다', () => {
    setup()
    expect(screen.getByRole('link', { name: '비밀번호를 잊었어요' }).getAttribute('href')).toBe(
      '/password/reset',
    )
    expect(screen.getByRole('link', { name: '이메일로 가입하기' }).getAttribute('href')).toBe(
      '/signup/email',
    )
  })
})
