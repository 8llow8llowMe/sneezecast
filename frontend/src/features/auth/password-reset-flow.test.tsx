// @vitest-environment jsdom
import { useSyncExternalStore } from 'react'

import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { OnboardingProvider } from '@/features/onboarding/onboarding-context'

import { LoginEmailScreen } from './login-email-screen'
import { PasswordResetCodeScreen } from './password-reset-code-screen'
import { PasswordResetEmailScreen } from './password-reset-email-screen'
import { PasswordResetNewScreen } from './password-reset-new-screen'

/**
 * 주소 하나를 들고 push · replace 로 바꾸는 가짜 라우터. 화면 사이를 실제로 넘어가며 같은 Provider(레이아웃)를 쓰는지 본다.
 * back 은 이 흐름에서 쓰지 않는다
 */
const nav = vi.hoisted(() => {
  let current = '/password/reset'
  const listeners = new Set<() => void>()
  const go = (path: string) => {
    current = path
    listeners.forEach((listener) => listener())
  }
  return {
    get: () => current,
    reset: (path: string) => {
      current = path
    },
    subscribe: (listener: () => void) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    router: { push: vi.fn(go), replace: vi.fn(go), back: vi.fn() },
  }
})

function useLocation() {
  return useSyncExternalStore(nav.subscribe, nav.get)
}

vi.mock('next/navigation', () => ({
  useRouter: () => nav.router,
  usePathname: () => useLocation().split('?')[0],
}))

function Routes() {
  const [path, query = ''] = useLocation().split('?')
  const reason = new URLSearchParams(query).get('reason')
  switch (path) {
    case '/password/reset':
      return <PasswordResetEmailScreen verificationExpired={reason === 'verification-expired'} />
    case '/password/reset/code':
      return <PasswordResetCodeScreen />
    case '/password/reset/new':
      return <PasswordResetNewScreen />
    case '/login/email':
      return <LoginEmailScreen resetDone={reason === 'reset-done'} />
    default:
      return null
  }
}

function renderFlow() {
  const user = userEvent.setup()
  render(
    <OnboardingProvider>
      <Routes />
    </OnboardingProvider>,
  )
  return user
}

async function throughCode(user: ReturnType<typeof userEvent.setup>, email: string) {
  await user.type(screen.getByRole('textbox', { name: '이메일' }), email)
  await user.click(screen.getByRole('button', { name: '인증 코드 받기' }))
  await user.type(await screen.findByRole('textbox', { name: '인증 코드 6자리' }), '482915')
  await user.click(screen.getByRole('button', { name: '확인' }))
  await screen.findByRole('heading', { name: '새 비밀번호를 정해 주세요' })
}

async function changePassword(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('새 비밀번호', { selector: 'input' }), 'newpass2026')
  await user.type(screen.getByLabelText('새 비밀번호 확인', { selector: 'input' }), 'newpass2026')
  await user.click(screen.getByRole('button', { name: '비밀번호 바꾸기' }))
}

describe('비밀번호 재설정 흐름', () => {
  beforeEach(() => {
    nav.reset('/password/reset')
    nav.router.push.mockClear()
    nav.router.replace.mockClear()
  })

  it('이메일 → 코드 → 새 비밀번호 → 이메일 로그인(토스트 · 이메일 채움)', async () => {
    const user = renderFlow()
    await throughCode(user, 'flow@example.com')
    await changePassword(user)

    await screen.findByRole('heading', { name: '이메일로 로그인해 주세요' })
    expect(nav.get()).toBe('/login/email?reason=reset-done')
    expect(nav.router.push.mock.calls.map(([path]) => path)).toEqual([
      '/password/reset/code',
      '/password/reset/new',
    ])
    await waitFor(() =>
      expect(
        screen
          .getAllByRole('status')
          .some((region) => region.textContent?.includes('비밀번호를 바꿨어요')),
      ).toBe(true),
    )
    expect(screen.getByRole<HTMLInputElement>('textbox', { name: '이메일' }).value).toBe(
      'flow@example.com',
    )
    // 비밀번호 칸은 비어 있다 — 새 비밀번호를 어디에도 남기지 않는다
    expect(screen.getByLabelText<HTMLInputElement>('비밀번호', { selector: 'input' }).value).toBe(
      '',
    )
  })

  it('인증이 만료되면 이메일 단계로 돌아가 안내를 띄우고, 다시 인증해 마칠 수 있다', async () => {
    const user = renderFlow()
    // 재현용 이메일은 재설정 요청이 늘 인증 만료다
    await throughCode(user, 'verify-expired@example.com')
    await changePassword(user)

    expect((await screen.findByRole('alert')).textContent).toBe(
      '인증 시간이 지났어요. 이메일 인증부터 다시 해 주세요.',
    )
    expect(nav.get()).toBe('/password/reset?reason=verification-expired')
    const email = screen.getByRole<HTMLInputElement>('textbox', { name: '이메일' })
    expect(email.value).toBe('verify-expired@example.com')

    // 새 비밀번호 화면에 바로 가도 인증이 지워져 이메일 단계로 돌아온다
    nav.router.push('/password/reset/new')
    await waitFor(() => expect(nav.get()).toBe('/password/reset'))

    await user.clear(screen.getByRole('textbox', { name: '이메일' }))
    await throughCode(user, 'again@example.com')
    await changePassword(user)
    await waitFor(() => expect(nav.get()).toBe('/login/email?reason=reset-done'))
  })
  it('새 비밀번호 단계까지 간 뒤 이메일 로그인으로 돌아오면 앞으로 가기로 다시 들어가도 열리지 않는다 (공용 기기)', async () => {
    const user = renderFlow()
    await throughCode(user, 'shared@example.com')

    // 휴대폰 뒤로 가기 등으로 이메일 로그인까지 돌아옴
    nav.router.push('/login/email')
    await screen.findByRole('heading', { name: '이메일로 로그인해 주세요' })

    // 앞으로 가기로 새 비밀번호 화면에 다시 들어옴 → 토큰이 지워져 이메일 단계로 돌려보낸다
    nav.router.push('/password/reset/new')
    await waitFor(() => expect(nav.get()).toBe('/password/reset'))
    expect(screen.queryByRole('heading', { name: '새 비밀번호를 정해 주세요' })).toBeNull()
    // 이메일은 남는다
    expect(screen.getByRole<HTMLInputElement>('textbox', { name: '이메일' }).value).toBe(
      'shared@example.com',
    )
  })
})
