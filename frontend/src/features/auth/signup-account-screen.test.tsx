// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  EMPTY_SIGNUP,
  OnboardingProvider,
  type SignupDraft,
  useOnboarding,
} from '@/features/onboarding/onboarding-context'

import { SignupAccountScreen } from './signup-account-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/signup/account',
}))

const VERIFIED: SignupDraft = {
  ...EMPTY_SIGNUP,
  email: 'dong@example.com',
  method: 'email',
  codeSentAt: 0,
  verifiedAt: 1,
}

/** Provider 에 남은 가입 초안을 읽는다 */
function Probe() {
  const { signup } = useOnboarding()
  return (
    <span hidden data-testid="draft">
      {JSON.stringify(signup)}
    </span>
  )
}

function setup(draft: SignupDraft = VERIFIED) {
  const user = userEvent.setup()
  const utils = render(
    <OnboardingProvider initialSignup={draft}>
      <SignupAccountScreen />
      <Probe />
    </OnboardingProvider>,
  )
  const password = screen.getByLabelText('비밀번호', { selector: 'input' })
  const confirm = screen.getByLabelText('비밀번호 확인', { selector: 'input' })
  const nickname = screen.getByRole('textbox', { name: '닉네임' })
  const next = screen.getByRole('button', { name: '다음' })
  return { user, password, confirm, nickname, next, ...utils }
}

const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

async function fill(
  { user, password, confirm, nickname }: ReturnType<typeof setup>,
  values: { password: string; confirm?: string; nickname: string },
) {
  await user.type(password, values.password)
  await user.type(confirm, values.confirm ?? values.password)
  await user.type(nickname, values.nickname)
}

describe('SignupAccountScreen', () => {
  beforeEach(() => {
    router.push.mockClear()
    router.replace.mockClear()
  })

  it('새 비밀번호 자동 완성 · 비밀번호 보기 · 닉네임 글자 수를 보인다', () => {
    const { password, confirm } = setup()
    expect(password.getAttribute('autocomplete')).toBe('new-password')
    expect(confirm.getAttribute('autocomplete')).toBe('new-password')
    expect(screen.getAllByRole('button', { name: '비밀번호 보기' })).toHaveLength(2)
    expect(screen.getByText('0/10')).toBeDefined()
  })

  it('닉네임 글자 수가 늘고 10자를 넘으면 빨갛게 보인다', async () => {
    const { user, nickname } = setup()
    await user.type(nickname, '동네지기')
    expect(screen.getByText('4/10').classList).toContain('text-fg-sub')

    await user.type(nickname, '우리동네건강지')
    expect(screen.getByText('11/10').classList).toContain('text-danger')
  })

  it('맞으면 비밀번호 · 닉네임(앞뒤 공백 뺌)을 두고 동네 선택으로 간다', async () => {
    const utils = setup()
    await fill(utils, { password: 'dongne2026', nickname: '  동네지기 ' })
    await utils.user.click(utils.next)
    expect(router.push).toHaveBeenCalledWith('/setup/region')
    const saved = JSON.parse(screen.getByTestId('draft').textContent ?? '{}') as SignupDraft
    expect(saved.password).toBe('dongne2026')
    expect(saved.nickname).toBe('동네지기')
    expect(saved.verifiedAt).toBe(1)
  })

  it('규칙에 안 맞는 비밀번호는 다음을 누를 때 알리고 고치면 지운다', async () => {
    const utils = setup()
    await fill(utils, { password: 'abc123', nickname: '동네지기' })
    expect(screen.queryByRole('alert')).toBeNull()

    await utils.user.click(utils.next)
    expect(screen.getByRole('alert').textContent).toBe(
      '영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요.',
    )
    expect(isOff(utils.next)).toBe(true)
    expect(router.push).not.toHaveBeenCalled()

    await utils.user.type(utils.password, '45')
    await utils.user.type(utils.confirm, '45')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(isOff(utils.next)).toBe(false)
  })

  it('도움말이 길이 상한 · 띄어쓰기 규칙을 알린다', () => {
    setup()
    expect(screen.getByText('8~20자 · 영문과 숫자 포함 · 띄어쓰기 없이')).toBeDefined()
  })

  it('20자를 넘는 비밀번호는 자르지 않고 규칙 오류로 알린다', async () => {
    const utils = setup()
    const long = 'abcdefghij12345678901'
    await fill(utils, { password: long, nickname: '동네지기' })
    expect((utils.password as HTMLInputElement).value).toBe(long)
    expect(utils.password.hasAttribute('maxlength')).toBe(false)

    await utils.user.click(utils.next)
    expect(screen.getByRole('alert').textContent).toContain('8~20자')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('띄어쓰기가 있는 비밀번호는 규칙 오류로 알린다', async () => {
    const utils = setup()
    await fill(utils, { password: 'dongne 2026', nickname: '동네지기' })
    await utils.user.click(utils.next)
    expect(screen.getByRole('alert').textContent).toContain('띄어쓰기 없이')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('인증 시간이 지나 다시 왔으면 남은 닉네임이 채워져 있고 비밀번호는 비어 있다', () => {
    const { password, confirm, nickname } = setup({ ...VERIFIED, nickname: '동네지기' })
    expect((nickname as HTMLInputElement).value).toBe('동네지기')
    expect((password as HTMLInputElement).value).toBe('')
    expect((confirm as HTMLInputElement).value).toBe('')
  })

  it('확인이 다르면 확인 칸에 알린다', async () => {
    const utils = setup()
    await fill(utils, { password: 'dongne2026', confirm: 'dongne2025', nickname: '동네지기' })
    await utils.user.click(utils.next)
    expect(screen.getByRole('alert').textContent).toBe('비밀번호가 서로 달라요.')
    expect(utils.confirm.getAttribute('aria-invalid')).toBe('true')
  })

  it('닉네임이 10자를 넘으면 알린다', async () => {
    const utils = setup()
    await fill(utils, { password: 'dongne2026', nickname: '우리동네건강지킴이짱짱' })
    await utils.user.click(utils.next)
    expect(screen.getByRole('alert').textContent).toBe('닉네임은 2~10자로 써 주세요.')
  })

  it('빈 칸이 있으면 다음이 꺼져 있다', () => {
    const { next } = setup()
    expect(isOff(next)).toBe(true)
  })

  it('인증을 마치지 않았으면 이메일 입력으로 돌려보낸다', () => {
    render(
      <OnboardingProvider initialSignup={{ ...VERIFIED, verifiedAt: null }}>
        <SignupAccountScreen />
      </OnboardingProvider>,
    )
    expect(router.replace).toHaveBeenCalledWith('/signup/email')
  })

  it('주소로 바로 들어왔으면 뒤로는 인증 코드로 바꿔 간다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/signup/code')
  })
})

/** 뒤로 가기 캐시에 들어가기 직전 (#186) */
const freezePage = (persisted = true) =>
  act(() => {
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted }))
  })

describe('SignupAccountScreen 뒤로 가기 캐시 (#186)', () => {
  it('얼기 직전에 비밀번호 · 닉네임 칸과 가입 초안을 비우고 가입 처음(이메일 단계)으로 보낸다', async () => {
    router.replace.mockClear()
    const utils = setup()
    await fill(utils, { password: 'Secret-PW-123', nickname: '재채기탐정' })

    freezePage()

    expect(router.replace).toHaveBeenCalledWith('/signup/email')
    expect(screen.queryByLabelText('비밀번호', { selector: 'input' })).toBeNull()
    expect((utils.password as HTMLInputElement).value).toBe('')
  })
})
