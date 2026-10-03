// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { OnboardingProvider, useOnboarding } from '@/features/onboarding/onboarding-context'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import { resetApiSession, selectApiSource } from '@/test/api-session'

import type * as authClient from './auth-client'
import { loginWithEmail } from './auth-client'
import { LoginEmailScreen } from './login-email-screen'
import type { LoginReturn } from './login-return'
import { clearLoginReturn, peekLoginReturn, saveLoginReturn } from './login-return-store'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
const pathname = vi.hoisted(() => ({ value: '/login/email' }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => pathname.value,
}))

// 실제 목을 쓰되 응답 지연 · 실패를 흉내 낼 수 있게 감싼다
vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, loginWithEmail: vi.fn(actual.loginWithEmail) }
})

function setup(resetDone = false, loginReturn?: LoginReturn) {
  const user = userEvent.setup()
  const utils = render(
    <OnboardingProvider>
      <LoginEmailScreen resetDone={resetDone} {...(loginReturn ? { loginReturn } : {})} />
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
    clearLoginReturn()
  })

  afterEach(() => resetApiSession())

  it('실데이터 모드면 출처 api 로 로그인한다', async () => {
    selectApiSource()
    vi.mocked(loginWithEmail).mockResolvedValueOnce({ status: 'ok' })
    const { user, email, password, submit } = setup()
    await fill(user, email, password, 'dong@example.com', 'dongne2026')
    await user.click(submit)

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
    expect(loginWithEmail).toHaveBeenCalledWith('dong@example.com', 'dongne2026', 'api')
  })

  it('limited(IP 상한)면 잠시 뒤 다시 하라고 alert 로 알리되 로그인을 끄지 않고, 칸을 고치면 지운다', async () => {
    const { user, email, password, submit } = setup()
    await fill(user, email, password, 'limit@example.com', 'dongne2026')
    await user.click(submit)

    expect((await screen.findByRole('alert')).textContent).toBe(
      '로그인 시도가 많아 잠시 막혔어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(isOff(submit)).toBe(false)

    await user.type(password, '1')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('suspended(정지)면 정지된 계정이라고 알리고 칸을 고치면 지운다', async () => {
    const { user, email, password, submit } = setup()
    await fill(user, email, password, 'suspended@example.com', 'dongne2026')
    await user.click(submit)

    expect((await screen.findByRole('alert')).textContent).toBe('이용이 정지된 계정이에요.')
    expect(router.replace).not.toHaveBeenCalled()

    await user.type(password, '1')
    expect(screen.queryByRole('alert')).toBeNull()
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
    expect(loginWithEmail).toHaveBeenCalledWith('dong@example.com', 'dongne2026', 'mock')
  })

  it('돌아갈 곳(?next=/me)이 있으면 성공한 뒤 동네를 남긴 내 정보로 기록을 바꿔 간다', async () => {
    vi.mocked(loginWithEmail).mockResolvedValueOnce({ status: 'ok' })
    const { user, email, password, submit } = setup(false, {
      next: '/me',
      region: '11440660',
      intent: null,
    })
    await fill(user, email, password, 'dong@example.com', 'dongne2026')
    await user.click(submit)

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/me?region=11440660'))
    expect(router.replace).toHaveBeenCalledTimes(1)
  })

  it('보고하려던 로그인(?intent=report)이면 성공한 뒤 같은 동네 홈의 보고 진입으로 기록을 바꿔 간다', async () => {
    const { user, email, password, submit } = setup(false, {
      next: '/',
      region: '11680640',
      intent: 'report',
    })
    await fill(user, email, password, 'dong@example.com', 'dongne2026')
    await user.click(submit)

    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/?region=11680640&report=start'),
    )
    expect(router.replace).toHaveBeenCalledTimes(1)
  })

  it('보고하려던 로그인을 주소로 바로 열었으면 뒤로는 그 돌아갈 곳을 붙인 로그인 방법 고르기로 바꿔 간다', async () => {
    const { user } = setup(false, { next: '/', region: '11680640', intent: 'report' })
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/login?region=11680640&intent=report')
  })

  it('주소로 바로 들어왔으면 뒤로는 돌아갈 곳을 붙인 로그인 방법 고르기로 바꿔 간다', async () => {
    const { user } = setup(false, { next: '/me', region: '11440660', intent: null })
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fme&region=11440660')
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

  it('비밀번호를 바꾸고 왔으면 재설정에 쓴 이메일로 칸을 채운다 — reset-done 이 아니면 비운다', () => {
    const reset = { email: 'dong@example.com', codeSentAt: null, resetToken: null }
    const { unmount } = render(
      <OnboardingProvider initialPasswordReset={reset}>
        <LoginEmailScreen resetDone />
      </OnboardingProvider>,
    )
    expect(screen.getByRole<HTMLInputElement>('textbox', { name: '이메일' }).value).toBe(
      'dong@example.com',
    )
    unmount()

    render(
      <OnboardingProvider initialPasswordReset={reset}>
        <LoginEmailScreen />
      </OnboardingProvider>,
    )
    expect(screen.getByRole<HTMLInputElement>('textbox', { name: '이메일' }).value).toBe('')
  })

  it('들어오면 그만둔 재설정의 보낸 시각 · 토큰을 지우고 이메일은 남겨 칸을 채운다', () => {
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
        <LoginEmailScreen resetDone />
        <ResetProbe />
      </OnboardingProvider>,
    )
    expect(JSON.parse(screen.getByTestId('reset').textContent ?? '{}')).toEqual({
      email: 'dong@example.com',
      codeSentAt: null,
      resetToken: null,
    })
    // 칸의 처음 값은 지우기 전(첫 렌더)에 읽는다
    expect(screen.getByRole<HTMLInputElement>('textbox', { name: '이메일' }).value).toBe(
      'dong@example.com',
    )
  })

  describe('가입 · 비밀번호 재설정으로 떠날 때 돌아갈 곳을 둔다 (#140)', () => {
    const REPORT = { next: '/', region: '11680640', intent: 'report' as const }

    it.each(['비밀번호를 잊었어요', '이메일로 가입하기'])('%s 를 누르면 둔다', async (name) => {
      const { user } = setup(false, REPORT)
      await user.click(screen.getByRole('link', { name }))
      expect(peekLoginReturn()).toEqual(REPORT)
    })

    it('돌아갈 곳이 없으면 앞서 둔 값을 지운다', async () => {
      saveLoginReturn(REPORT)
      const { user } = setup()
      await user.click(screen.getByRole('link', { name: '비밀번호를 잊었어요' }))
      expect(peekLoginReturn()).toEqual({ next: '/', region: null, intent: null })
    })

    it('로그인에 성공하면 둔 값을 지운다 (이 화면은 주소로 받았다)', async () => {
      saveLoginReturn({ next: '/me', region: null, intent: null })
      const { user, email, password, submit } = setup(true, REPORT)
      await fill(user, email, password, 'dong@example.com', 'dongne2026')
      await user.click(submit)
      await waitFor(() =>
        expect(router.replace).toHaveBeenCalledWith('/?region=11680640&report=start'),
      )
      expect(peekLoginReturn()).toEqual({ next: '/', region: null, intent: null })
    })

    it('로그인하지 못하면 둔 값을 남긴다', async () => {
      saveLoginReturn(REPORT)
      vi.mocked(loginWithEmail).mockResolvedValueOnce({ status: 'wrong' })
      const { user, email, password, submit } = setup(false, REPORT)
      await fill(user, email, password, 'dong@example.com', 'wrong2026')
      await user.click(submit)
      await screen.findByText('이메일 또는 비밀번호가 맞지 않아요.')
      expect(peekLoginReturn()).toEqual(REPORT)
    })
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

describe('LoginEmailScreen 뒤로 — 앱 안 이동 기록', () => {
  beforeEach(() => {
    router.replace.mockClear()
    router.back.mockClear()
  })

  /** 앱 안에서 앞 화면(`from`)을 지나 이메일 로그인에 온 것처럼 그린다 */
  function visitLoginEmail(from: string, loginReturn?: LoginReturn) {
    pathname.value = from
    const tree = () => (
      <NavTrailProvider>
        <OnboardingProvider>
          {pathname.value === '/login/email' ? (
            <LoginEmailScreen {...(loginReturn ? { loginReturn } : {})} />
          ) : (
            <div />
          )}
        </OnboardingProvider>
      </NavTrailProvider>
    )
    const { rerender } = render(tree())
    pathname.value = '/login/email'
    rerender(tree())
  }

  it('보고하려던 로그인을 홈(로그인 안내 시트)에서 열었으면 기록을 되돌려 시트로 돌아간다', async () => {
    visitLoginEmail('/', { next: '/', region: null, intent: 'report' })
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('그 밖의 로그인은 홈에서 왔어도 로그인 방법 고르기로 바꿔 간다 (지금과 같다)', async () => {
    visitLoginEmail('/')
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/login')
  })

  it('로그인 방법 고르기에서 왔으면 기록을 되돌린다', async () => {
    visitLoginEmail('/login', { next: '/', region: '11680640', intent: 'report' })
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledOnce()
  })
})

/** 뒤로 가기 캐시에 들어가기 직전 (#186) */
const freezePage = (persisted = true) =>
  act(() => {
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted }))
  })

describe('LoginEmailScreen 뒤로 가기 캐시 (#186)', () => {
  it('얼기 직전에 입력만 한 이메일 · 비밀번호를 비운다', async () => {
    const { user, email, password } = setup()
    await fill(user, email, password, 'me@example.com', 'Secret-PW-123')

    freezePage()

    expect((email as HTMLInputElement).value).toBe('')
    expect((password as HTMLInputElement).value).toBe('')
  })

  it('비밀번호 보기를 켰어도 끈다 — 다음 사람이 쓴 글자가 평문으로 보이지 않는다', async () => {
    const { user, password } = setup()
    await user.click(screen.getByRole('button', { name: /비밀번호 보기/ }))
    expect(password.getAttribute('type')).toBe('text')

    freezePage()

    expect(password.getAttribute('type')).toBe('password')
  })

  it('캐시에 들지 않는 떠나기(persisted false)는 그대로 둔다', async () => {
    const { user, email, password } = setup()
    await fill(user, email, password, 'me@example.com', 'Secret-PW-123')

    freezePage(false)

    expect((password as HTMLInputElement).value).toBe('Secret-PW-123')
  })
})
