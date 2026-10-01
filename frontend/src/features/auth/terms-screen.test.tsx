// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  EMPTY_SIGNUP,
  type Membership,
  NO_MEMBERSHIP,
  OnboardingProvider,
  type SignupDraft,
  useOnboarding,
} from '@/features/onboarding/onboarding-context'
import type { District } from '@/features/region/types'

import type * as authClient from './auth-client'
import { saveRegion, signup } from './auth-client'
import { TermsScreen } from './terms-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/setup/terms',
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return {
    ...actual,
    signup: vi.fn(actual.signup),
    saveRegion: vi.fn(actual.saveRegion),
  }
})

const DISTRICT: District = { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }
const EMAIL_DRAFT: SignupDraft = {
  email: 'dong@example.com',
  codeSentAt: 1,
  verificationToken: 'mock-verified',
  password: 'dongne2026',
  nickname: '동네지기',
}

function Probe() {
  const { signup: draft, membership, notificationOptIn } = useOnboarding()
  return (
    <span hidden data-testid="state">
      {JSON.stringify({ draft, membership, notificationOptIn })}
    </span>
  )
}
const state = () =>
  JSON.parse(screen.getByTestId('state').textContent ?? '{}') as {
    draft: SignupDraft
    membership: Membership
    notificationOptIn: boolean
  }

function setup({
  draft = EMPTY_SIGNUP,
  district = DISTRICT,
  adult = true,
  membership = NO_MEMBERSHIP,
}: {
  draft?: SignupDraft
  district?: District | null
  adult?: boolean
  membership?: Membership
} = {}) {
  const user = userEvent.setup()
  const utils = render(
    <OnboardingProvider
      initialDistrict={district}
      initialSignup={draft}
      initialAdultConfirmed={adult}
      initialMembership={membership}
    >
      <TermsScreen />
      <Probe />
    </OnboardingProvider>,
  )
  return { user, ...utils }
}

const box = (name: string | RegExp) => screen.getByRole<HTMLInputElement>('checkbox', { name })
const submit = () => screen.getByRole('button', { name: '동의하고 가입하기' })
const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

describe('TermsScreen', () => {
  beforeEach(() => {
    router.push.mockClear()
    router.replace.mockClear()
    vi.mocked(signup).mockReset()
    vi.mocked(saveRegion).mockReset()
  })

  it('3 / 4 단계이고 필수를 켜기 전에는 가입하기가 꺼져 있어 눌러도 보내지 않는다', async () => {
    const { user } = setup()
    expect(screen.getByText('3 / 4')).toBeDefined()
    expect(isOff(submit())).toBe(true)
    await user.click(submit())
    expect(signup).not.toHaveBeenCalled()
  })

  it('전체 동의는 선택까지 함께 켜고 끄고, 필수 하나를 끄면 함께 꺼진다', async () => {
    const { user } = setup()
    await user.click(box('전체 동의'))
    expect(box(/서비스 이용약관/).checked).toBe(true)
    expect(box(/개인정보 수집·이용/).checked).toBe(true)
    expect(box(/주간 보고 알림 받기/).checked).toBe(true)
    expect(state().notificationOptIn).toBe(true)

    // 선택만 꺼도 전체 동의는 켜진 채다 (시안 default)
    await user.click(box(/주간 보고 알림 받기/))
    expect(box('전체 동의').checked).toBe(true)

    await user.click(box(/서비스 이용약관/))
    expect(box('전체 동의').checked).toBe(false)
    expect(isOff(submit())).toBe(true)

    await user.click(box(/서비스 이용약관/))
    await user.click(box('전체 동의'))
    expect(box(/개인정보 수집·이용/).checked).toBe(false)
  })

  it('보기는 본문을 준비하고 있다고 알린다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '서비스 이용약관 보기' }))
    expect(
      screen.getAllByRole('status').some((r) => r.textContent === '약관 본문을 준비하고 있어요'),
    ).toBe(true)
  })

  it('이메일 가입이면 비밀번호까지 보내고, 가입 뒤 비밀번호 · 인증 값을 지우고 동네를 저장한다', async () => {
    const { user } = setup({ draft: EMAIL_DRAFT })
    await user.click(box(/서비스 이용약관/))
    await user.click(box(/개인정보 수집·이용/))
    await user.click(submit())

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
    expect(signup).toHaveBeenCalledWith({
      kind: 'email',
      email: 'dong@example.com',
      verificationToken: 'mock-verified',
      password: 'dongne2026',
      nickname: '동네지기',
      consents: [
        { type: 'TERMS_OF_SERVICE', documentVersion: '2026-10-01' },
        { type: 'PRIVACY_POLICY', documentVersion: '2026-10-01' },
        { type: 'AGE_OVER_19', documentVersion: '2026-10-01' },
      ],
    })
    expect(saveRegion).toHaveBeenCalledWith('11680640')
    expect(state().draft.password).toBe('')
    expect(state().draft.verificationToken).toBeNull()
    expect(state().membership).toEqual({ accountCreated: true, regionSaved: true })
  })

  it('카카오 가입이면 동의만 보낸다', async () => {
    const { user } = setup()
    await user.click(box('전체 동의'))
    await user.click(submit())
    await waitFor(() => expect(signup).toHaveBeenCalledTimes(1))
    const request = vi.mocked(signup).mock.calls[0]?.[0]
    expect(request?.kind).toBe('kakao')
    expect(request).not.toHaveProperty('password')
  })

  it('보내는 동안 두 번 눌러도 한 번만 가입한다', async () => {
    let resolve: () => void = () => {}
    vi.mocked(signup).mockImplementationOnce(() => new Promise<void>((done) => (resolve = done)))
    const { user } = setup()
    await user.click(box('전체 동의'))
    await user.click(submit())
    await user.click(submit())
    expect(isOff(submit())).toBe(true)
    expect(signup).toHaveBeenCalledTimes(1)
    await act(async () => {
      resolve()
      await Promise.resolve()
    })
  })

  it('가입이 거부되면 alert 로 알리고 다시 누를 수 있다', async () => {
    vi.mocked(signup).mockRejectedValueOnce(new Error('network'))
    const { user } = setup()
    await user.click(box('전체 동의'))
    await user.click(submit())
    expect((await screen.findByRole('alert')).textContent).toBe(
      '가입하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(isOff(submit())).toBe(false)
    expect(saveRegion).not.toHaveBeenCalled()
  })

  it('동네 저장만 실패하면 다시 누를 때 가입을 두 번 보내지 않는다', async () => {
    vi.mocked(saveRegion).mockRejectedValueOnce(new Error('network'))
    const { user } = setup()
    await user.click(box('전체 동의'))
    await user.click(submit())
    expect((await screen.findByRole('alert')).textContent).toContain('동네를 저장하지 못했어요')

    await user.click(submit())
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
    expect(signup).toHaveBeenCalledTimes(1)
    expect(saveRegion).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['동네가 없으면 동네 선택', { district: null }, '/setup/region'],
    ['성인 확인이 없으면 성인 확인', { adult: false }, '/setup/adult'],
    [
      '이미 가입을 마쳤으면 증상 보고 동의',
      { membership: { accountCreated: true, regionSaved: true } },
      '/setup/health-consent',
    ],
  ] as const)('%s 로 돌려보낸다', (_, options, path) => {
    setup(options)
    expect(router.replace).toHaveBeenCalledWith(path)
    expect(screen.queryByRole('heading')).toBeNull()
  })

  it('주소로 바로 들어왔으면 뒤로는 성인 확인으로 바꿔 간다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/setup/adult')
  })
})
