// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  EMPTY_SIGNUP,
  OnboardingProvider,
  type SignupDraft,
  useOnboarding,
} from '@/features/onboarding/onboarding-context'
import { NavTrailProvider } from '@/lib/use-nav-trail'

import type * as authClient from './auth-client'
import { sendEmailCode, verifyEmailCode } from './auth-client'
import { SignupCodeScreen } from './signup-code-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
const location = vi.hoisted(() => ({ pathname: '/signup/code' }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => location.pathname,
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return {
    ...actual,
    sendEmailCode: vi.fn(actual.sendEmailCode),
    verifyEmailCode: vi.fn(actual.verifyEmailCode),
  }
})

const EMAIL = 'dong@example.com'

/** Provider 에 남은 인증 시각을 읽는다 */
function Probe() {
  const { signup } = useOnboarding()
  return (
    <span hidden data-testid="verified-at">
      {signup.verifiedAt ?? ''}
    </span>
  )
}

function tree(initial: SignupDraft) {
  return (
    <NavTrailProvider>
      <OnboardingProvider initialSignup={initial}>
        <SignupCodeScreen />
        <Probe />
      </OnboardingProvider>
    </NavTrailProvider>
  )
}

function setup(draft: Partial<SignupDraft> = {}) {
  const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })
  const initial = { ...EMPTY_SIGNUP, email: EMAIL, codeSentAt: Date.now(), ...draft }
  const utils = render(tree(initial))
  return { user, initial, ...utils }
}

const timer = () => screen.getByRole('timer', { name: '남은 시간' })
const verifiedAt = () => screen.getByTestId('verified-at').textContent

const codeInput = () => screen.getByRole('textbox', { name: '인증 코드 6자리' })
const confirmButton = () => screen.getByRole('button', { name: '확인' })
const resendButton = () => screen.getByRole('button', { name: /^다시 받기/ })
const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

describe('SignupCodeScreen', () => {
  beforeEach(async () => {
    // Testing Library 의 비동기 대기(setTimeout 0)가 멈추지 않게 실제 시간도 흐르게 둔다.
    // 흐르는 실제 시간은 수십 ms 라 초 단위 표시에는 영향이 없다
    vi.useFakeTimers({ shouldAdvanceTime: true })
    router.push.mockClear()
    router.replace.mockClear()
    router.back.mockClear()
    location.pathname = '/signup/code'
    vi.mocked(verifyEmailCode).mockReset()
    vi.mocked(sendEmailCode).mockReset()
    // 목 서버에 이 이메일로 보낸 코드를 만든다
    await sendEmailCode(EMAIL, 'mock')
    vi.mocked(sendEmailCode).mockClear()
  })

  afterEach(() => vi.useRealTimers())

  it('보낸 이메일 · 남은 시간 5:00 · 다시 받기 대기 1:00 을 보인다', () => {
    setup()
    // 가입 여부를 드러내지 않는 중립 문구 (backend/docs/modules.md "화면 계약")
    expect(screen.getByText('이미 가입한 이메일이면 코드 대신 안내 메일이 가요.')).toBeDefined()
    expect(screen.getByText(EMAIL).closest('span')?.textContent).toBe(`${EMAIL}으로 보냈어요`)
    expect(timer().textContent).toBe('5:00')
    expect(resendButton().textContent).toBe('다시 받기 1:00')
    expect(isOff(resendButton())).toBe(true)
    expect(codeInput().getAttribute('inputmode')).toBe('numeric')
    expect(codeInput().getAttribute('autocomplete')).toBe('one-time-code')
  })

  it('1초마다 줄고 60초가 지나면 다시 받기가 켜진다', () => {
    setup()
    act(() => {
      vi.advanceTimersByTime(18_000)
    })
    expect(timer().textContent).toBe('4:42')
    expect(resendButton().textContent).toBe('다시 받기 0:42')

    act(() => {
      vi.advanceTimersByTime(42_000)
    })
    expect(resendButton().textContent).toBe('다시 받기')
    expect(isOff(resendButton())).toBe(false)
  })

  it('숫자 6자리를 넣어야 확인이 켜지고 숫자가 아닌 글자는 받지 않는다', async () => {
    const { user } = setup()
    await user.type(codeInput(), '48a29')
    expect((codeInput() as HTMLInputElement).value).toBe('4829')
    expect(isOff(confirmButton())).toBe(true)

    await user.type(codeInput(), '15')
    expect(isOff(confirmButton())).toBe(false)
  })

  it('붙여넣은 "482 915" 도 숫자 6자리로 받는다', async () => {
    const { user } = setup()
    await user.click(codeInput())
    await user.paste('482 915')
    expect((codeInput() as HTMLInputElement).value).toBe('482915')
    expect(isOff(confirmButton())).toBe(false)
  })

  it('맞으면 인증한 시각을 두고 비밀번호 · 닉네임으로 간다', async () => {
    const { user } = setup()
    expect(verifiedAt()).toBe('')
    const before = Date.now()
    await user.type(codeInput(), '482915')
    await user.click(confirmButton())
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/signup/account'))
    expect(Number(verifiedAt())).toBeGreaterThanOrEqual(before)
  })

  it('확인을 기다리는 동안 두 번 눌러도 한 번만 묻는다', async () => {
    let resolve: (value: { status: 'ok' }) => void = () => {}
    vi.mocked(verifyEmailCode).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { user } = setup()
    await user.type(codeInput(), '482915')
    await user.click(confirmButton())
    await user.click(confirmButton())
    expect(verifyEmailCode).toHaveBeenCalledTimes(1)
    expect(codeInput().hasAttribute('readonly')).toBe(true)
    resolve({ status: 'ok' })
    await waitFor(() => expect(router.push).toHaveBeenCalledTimes(1))
  })

  it('확인을 기다리는 동안 화면을 떠나면 늦은 응답으로 이동하지 않는다', async () => {
    let resolve: (value: { status: 'ok' }) => void = () => {}
    vi.mocked(verifyEmailCode).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { user, unmount } = setup()
    await user.type(codeInput(), '482915')
    await user.click(confirmButton())
    unmount()
    await act(async () => {
      resolve({ status: 'ok' })
      await Promise.resolve()
    })
    expect(router.push).not.toHaveBeenCalled()
  })

  it('확인 응답을 받지 못하면 다시 시도하라고 알린다', async () => {
    vi.mocked(verifyEmailCode).mockRejectedValueOnce(new Error('network'))
    const { user } = setup()
    await user.type(codeInput(), '482915')
    await user.click(confirmButton())
    expect((await screen.findByRole('alert')).textContent).toBe(
      '코드를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
  })

  it('인증을 마치고 돌아왔으면 다시 묻지 않고 넘어간다', async () => {
    const { user } = setup({ verifiedAt: Date.now() })
    expect(screen.getByRole('status').textContent).toContain('인증을 마쳤어요')
    expect(screen.queryByRole('timer')).toBeNull()
    expect((codeInput() as HTMLInputElement).disabled).toBe(true)

    await user.click(confirmButton())
    expect(verifyEmailCode).not.toHaveBeenCalled()
    expect(router.push).toHaveBeenCalledWith('/signup/account')
  })

  it('다시 받으면 앞선 인증이 무효가 된다', async () => {
    const { user } = setup({ verifiedAt: Date.now(), codeSentAt: Date.now() - 61_000 })
    await user.click(resendButton())
    await waitFor(() => expect(verifiedAt()).toBe(''))
    expect(timer().textContent).toBe('5:00')
  })

  it('다시 받기가 막히거나 실패하면 알린다', async () => {
    const { user } = setup({ codeSentAt: Date.now() - 61_000 })
    vi.mocked(sendEmailCode).mockResolvedValueOnce({ status: 'limit' })
    await user.click(resendButton())
    expect((await screen.findByRole('alert')).textContent).toBe(
      '코드 요청이 많아 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.',
    )

    vi.mocked(sendEmailCode).mockRejectedValueOnce(new Error('network'))
    await user.click(resendButton())
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toBe(
        '코드를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.',
      ),
    )
  })

  it('틀리면 남은 시도를 칸 아래 오류로 알리고 고치면 지운다', async () => {
    const { user } = setup()
    await user.type(codeInput(), '000000')
    await user.click(confirmButton())

    expect((await screen.findByRole('alert')).textContent).toBe(
      '코드가 맞지 않아요. 남은 시도는 4번이에요.',
    )
    await user.type(codeInput(), '{Backspace}1')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('5번 틀리면 남은 시도가 4번부터 1번까지 줄고 잠긴다', async () => {
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
    expect(verifyEmailCode).toHaveBeenCalledTimes(5)
  })

  it('5:00 이 지나면 0:00 으로 멈추고 확인을 끄고 다시 받게 한다', async () => {
    const { user } = setup()
    await user.type(codeInput(), '482915')
    act(() => {
      vi.advanceTimersByTime(300_000)
    })
    expect(timer().textContent).toBe('0:00')
    expect(timer().classList).toContain('text-danger')
    expect(screen.getByRole('alert').textContent).toBe(
      '입력 시간이 지났어요. 코드를 다시 받아 주세요.',
    )
    expect(isOff(confirmButton())).toBe(true)

    // 다시 받으면 코드 · 시간이 처음으로 돌아간다
    await user.click(resendButton())
    await waitFor(() => expect(timer().textContent).toBe('5:00'))
    expect((codeInput() as HTMLInputElement).value).toBe('')
    expect(sendEmailCode).toHaveBeenCalledWith(EMAIL, 'mock')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('잠기면 칸 · 두 버튼을 끄고 이메일 다시 입력하기를 보인다', async () => {
    const { user } = setup()
    await user.type(codeInput(), '999999')
    await user.click(confirmButton())

    expect((await screen.findByRole('alert')).textContent).toContain('시도 횟수를 넘겼어요')
    expect((codeInput() as HTMLInputElement).disabled).toBe(true)
    expect(isOff(confirmButton())).toBe(true)
    expect(isOff(resendButton())).toBe(true)

    await user.click(screen.getByRole('button', { name: '이메일 다시 입력하기' }))
    expect(router.replace).toHaveBeenCalledWith('/signup/email')
  })

  it('잠긴 뒤 화면을 다시 열어 확인하면 서버처럼 코드가 지워져 만료로 보이고 다시 받게 한다', async () => {
    const { user, initial, unmount } = setup()
    await user.type(codeInput(), '999999')
    await user.click(confirmButton())
    await screen.findByText(/시도 횟수를 넘겼어요/)
    unmount()

    // 앞으로 가기 등으로 같은 초안의 코드 화면이 다시 그려진 경우
    render(tree(initial))
    await user.type(codeInput(), '482915')
    await user.click(confirmButton())
    expect((await screen.findByRole('alert')).textContent).toBe(
      '입력 시간이 지났어요. 코드를 다시 받아 주세요.',
    )
    expect(isOff(confirmButton())).toBe(true)
    act(() => {
      vi.advanceTimersByTime(60_000)
    })
    expect(isOff(resendButton())).toBe(false)
  })

  it('틀린 뒤 시간이 지나면 칸 아래 오류를 비운다', async () => {
    const { user } = setup()
    await user.type(codeInput(), '000000')
    await user.click(confirmButton())
    await screen.findByText('코드가 맞지 않아요. 남은 시도는 4번이에요.')
    act(() => {
      vi.advanceTimersByTime(300_000)
    })
    expect(screen.queryByText(/코드가 맞지 않아요/)).toBeNull()
    expect(codeInput().getAttribute('aria-invalid')).toBeNull()
  })

  it('이메일에서 앱 안 이동으로 왔으면 이메일 다시 입력하기는 기록을 한 번만 되돌린다', async () => {
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })
    const initial = { ...EMPTY_SIGNUP, email: EMAIL, codeSentAt: Date.now() }
    location.pathname = '/signup/email'
    const { rerender } = render(tree(initial))
    location.pathname = '/signup/code'
    rerender(tree(initial))

    await user.type(codeInput(), '999999')
    await user.click(confirmButton())
    await user.click(await screen.findByRole('button', { name: '이메일 다시 입력하기' }))
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('보낸 이메일이 없으면 그리지 않고 이메일 입력으로 돌려보낸다', () => {
    const { container } = setup({ email: '', codeSentAt: null })
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/signup/email')
  })

  it('화면을 떠나면 타이머를 정리한다', () => {
    const { unmount } = setup()
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})

/** 뒤로 가기 캐시에 들어가기 직전 (#186) */
const freezePage = (persisted = true) =>
  act(() => {
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted }))
  })

describe('SignupCodeScreen 뒤로 가기 캐시 (#186)', () => {
  it('얼기 직전에 입력한 코드를 비우고, 이메일 · 보낸 시각이 지워져 이메일 단계로 보낸다', async () => {
    router.replace.mockClear()
    const user = userEvent.setup()
    render(tree({ ...EMPTY_SIGNUP, email: EMAIL, codeSentAt: Date.now() }))
    const input = codeInput() as HTMLInputElement
    await user.type(input, '482915')

    freezePage()
    expect(input.value).toBe('')

    // 얼기 직전에 보낸 이동은 브라우저가 버린다 — 되살아날 때 다시 보낸다
    router.replace.mockClear()
    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    })
    expect(router.replace).toHaveBeenCalledWith('/signup/email')
  })
})
