// @vitest-environment jsdom
import type { ReactNode } from 'react'

import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  EMPTY_SIGNUP,
  type Membership,
  NO_MEMBERSHIP,
  OnboardingProvider,
  type SignupDraft,
  useOnboarding,
} from '@/features/onboarding/onboarding-context'
import type { District } from '@/features/region/types'
import { getSessionSnapshot } from '@/lib/session/session-store'
import { memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

import type * as authClient from './auth-client'
import {
  getMockProfile,
  loginWithEmail,
  saveRegion,
  sendEmailCode,
  signup,
  verifyEmailCode,
} from './auth-client'
import { clearLoginReturn, peekLoginReturn, saveLoginReturn } from './login-return-store'
import { LoginScreen } from './login-screen'
import { SignupEmailScreen } from './signup-email-screen'
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
    loginWithEmail: vi.fn(actual.loginWithEmail),
    saveRegion: vi.fn(actual.saveRegion),
  }
})

const DISTRICT: District = { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }
const EMAIL_DRAFT: SignupDraft = {
  method: 'email',
  email: 'dong@example.com',
  codeSentAt: 1,
  verifiedAt: 1,
  password: 'dongne2026',
  nickname: '동네지기',
}
const KAKAO_DRAFT: SignupDraft = { ...EMPTY_SIGNUP, method: 'kakao' }

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
  draft = KAKAO_DRAFT,
  district = DISTRICT,
  adult = true,
  membership = NO_MEMBERSHIP,
  page = <TermsScreen />,
}: {
  draft?: SignupDraft
  district?: District | null
  adult?: boolean
  membership?: Membership
  /** 처음 그릴 화면. Provider 는 그대로 두고 `show` 로 화면만 바꾼다(레이아웃 안 이동과 같다) */
  page?: ReactNode
} = {}) {
  const user = userEvent.setup()
  const tree = (child: ReactNode) => (
    <OnboardingProvider
      initialDistrict={district}
      initialSignup={draft}
      initialAdultConfirmed={adult}
      initialMembership={membership}
    >
      {child}
      <Probe />
    </OnboardingProvider>
  )
  const utils = render(tree(page))
  const show = (child: ReactNode) => utils.rerender(tree(child))
  return { user, show, ...utils }
}

/** 코드 확인 · 비밀번호 화면이 채우는 값을 흉내 낸다 */
function FillAccount() {
  const { updateSignup } = useOnboarding()
  return (
    <button
      type="button"
      onClick={() =>
        updateSignup({ verifiedAt: Date.now(), password: 'dongne2026', nickname: '새닉' })
      }
    >
      계정 채우기
    </button>
  )
}

const box = (name: string | RegExp) => screen.getByRole<HTMLInputElement>('checkbox', { name })
const submit = () => screen.getByRole('button', { name: '동의하고 가입하기' })
const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

describe('TermsScreen', () => {
  beforeEach(async () => {
    router.push.mockClear()
    router.replace.mockClear()
    vi.mocked(signup).mockReset()
    vi.mocked(loginWithEmail).mockReset()
    vi.mocked(saveRegion).mockReset()
    clearLoginReturn()
    // 목 서버에 이 이메일의 인증 완료 표시를 만든다 (가입이 확인하고 지운다)
    await sendEmailCode(EMAIL_DRAFT.email, 'mock')
    await verifyEmailCode(EMAIL_DRAFT.email, '482915', 'mock')
  })

  afterEach(() => resetApiSession())

  async function agreeAndSubmit(user: ReturnType<typeof userEvent.setup>) {
    await user.click(box('전체 동의'))
    await user.click(submit())
  }

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

  it('이메일 가입이면 가입 → 로그인 → 동네 저장 순서로 보내고, 로그인 뒤 비밀번호 · 인증을 지운다', async () => {
    const { user } = setup({ draft: EMAIL_DRAFT })
    await user.click(box(/서비스 이용약관/))
    await user.click(box(/개인정보 수집·이용/))
    await user.click(submit())

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
    expect(signup).toHaveBeenCalledWith(
      {
        kind: 'email',
        email: 'dong@example.com',
        password: 'dongne2026',
        nickname: '동네지기',
        consents: [
          { type: 'TERMS_OF_SERVICE', documentVersion: '2026-10-01' },
          { type: 'PRIVACY_POLICY', documentVersion: '2026-10-01' },
          { type: 'AGE_OVER_19', documentVersion: '2026-10-01' },
        ],
      },
      'mock',
    )
    expect(loginWithEmail).toHaveBeenCalledWith('dong@example.com', 'dongne2026', 'mock')
    expect(saveRegion).toHaveBeenCalledWith(DISTRICT, 'mock')
    // 로그인한 뒤 저장하므로 목 프로필이 내 동네를 들고 있다(재선택 판단 · 내 정보가 쓴다)
    expect(getMockProfile()?.region).toEqual({ code: '11680640', name: '역삼1동' })
    const [signedUp] = vi.mocked(signup).mock.invocationCallOrder
    const [loggedIn] = vi.mocked(loginWithEmail).mock.invocationCallOrder
    const [saved] = vi.mocked(saveRegion).mock.invocationCallOrder
    expect(signedUp).toBeLessThan(loggedIn ?? 0)
    expect(loggedIn).toBeLessThan(saved ?? 0)
    expect(state().draft.password).toBe('')
    expect(state().draft.verifiedAt).toBeNull()
    expect(state().membership).toEqual({ accountCreated: true, loggedIn: true, regionSaved: true })
  })

  it('실데이터 카카오 가입이면 가입 응답으로 회원이 된 뒤 동네 저장을 실제로 보낸다(#167)', async () => {
    selectApiSource()
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      void init
      const body = url.endsWith('/api/v1/auth/kakao/signup')
        ? memberToken({ reportWritable: false })
        : url.endsWith('/api/v1/members/me/region')
          ? { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구', abolished: false }
          : null
      return Promise.resolve(
        new Response(
          JSON.stringify({
            dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
            dataBody: body,
          }),
          { status: 200 },
        ),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const { user } = setup({ draft: KAKAO_DRAFT })
    await agreeAndSubmit(user)

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
    const calls = fetchMock.mock.calls.map(([url]) => new URL(url).pathname)
    // 가입 뒤 로그인 요청이 없다. 회원 정보 저장소가 읽는 내 정보 · 내 동네 요청은 따로 나갈 수 있다
    expect(calls[0]).toBe('/api/v1/auth/kakao/signup')
    expect(calls).not.toContain('/api/v1/auth/login')
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')
    expect(put && new URL(put[0]).pathname).toBe('/api/v1/members/me/region')
    expect(put?.[1]).toMatchObject({ body: JSON.stringify({ code: '11680640' }) })
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      method: 'POST',
      body: JSON.stringify({ termsAgreed: true, privacyAgreed: true, ageOver19Confirmed: true }),
    })
    expect(getSessionSnapshot()).toMatchObject({ status: 'member' })
    expect(loginWithEmail).not.toHaveBeenCalled()
    expect(state().membership).toEqual({ accountCreated: true, loggedIn: true, regionSaved: true })
  })

  it.each([
    [{ status: 'kakao-restart', reason: 'expired' }, '/login?error=kakao-fail&kakao=expired'],
    [{ status: 'kakao-restart', reason: null }, '/login?error=kakao-fail'],
  ] as const)(
    '카카오 가입표가 없으면(%o) 카카오 로그인부터 다시 하게 로그인 화면으로 간다',
    async (result, path) => {
      vi.mocked(signup).mockResolvedValueOnce(result)
      const { user } = setup({ draft: KAKAO_DRAFT })
      await agreeAndSubmit(user)
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith(path))
      expect(router.replace).toHaveBeenCalledTimes(1)
      expect(saveRegion).not.toHaveBeenCalled()
      expect(state().membership).toEqual(NO_MEMBERSHIP)
    },
  )

  it('실데이터 카카오 가입에 서비스가 업무 오류(AUTH_017)로 답하면 가입표를 잃었으므로 바로 카카오 로그인부터 다시 하게 한다', async () => {
    selectApiSource()
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              dataHeader: {
                success: false,
                resultCode: 'AUTH_017',
                resultMessage: '거절',
                fieldErrors: null,
              },
              dataBody: null,
            }),
            { status: 503 },
          ),
        ),
      ),
    )
    const { user } = setup({ draft: KAKAO_DRAFT })
    await agreeAndSubmit(user)
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/login?error=kakao-fail'))
    expect(screen.queryByText('가입하지 못했어요. 잠시 뒤 다시 시도해 주세요.')).toBeNull()
    expect(saveRegion).not.toHaveBeenCalled()
  })

  it('실데이터 카카오 가입이 응답을 받지 못하면(네트워크) 상자로 알리고 다시 누르게 한다', async () => {
    selectApiSource()
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))),
    )
    const { user } = setup({ draft: KAKAO_DRAFT })
    await agreeAndSubmit(user)
    expect((await screen.findByRole('alert')).textContent).toBe(
      '가입하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(router.replace).not.toHaveBeenCalled()
    expect(state().membership).toEqual(NO_MEMBERSHIP)
  })

  it('카카오 가입이면 동의만 보내고 로그인 없이 동네를 저장한다', async () => {
    const { user } = setup({ draft: KAKAO_DRAFT })
    await agreeAndSubmit(user)
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
    const request = vi.mocked(signup).mock.calls[0]?.[0]
    expect(request?.kind).toBe('kakao')
    expect(request).not.toHaveProperty('password')
    expect(loginWithEmail).not.toHaveBeenCalled()
    expect(state().membership).toEqual({ accountCreated: true, loggedIn: true, regionSaved: true })
  })

  it('가입 종류는 인증 값이 아니라 method 로 가린다 — 카카오면 남은 이메일 값을 보내지 않는다', async () => {
    const { user } = setup({ draft: { ...EMAIL_DRAFT, method: 'kakao' } })
    await agreeAndSubmit(user)
    await waitFor(() => expect(signup).toHaveBeenCalledTimes(1))
    expect(vi.mocked(signup).mock.calls[0]?.[0]).toEqual({
      kind: 'kakao',
      consents: expect.any(Array) as unknown,
    })
  })

  it('보내는 동안 두 번 눌러도 한 번만 가입한다', async () => {
    let resolve: (value: { status: 'ok' }) => void = () => {}
    vi.mocked(signup).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { user } = setup()
    await agreeAndSubmit(user)
    await user.click(submit())
    expect(isOff(submit())).toBe(true)
    expect(signup).toHaveBeenCalledTimes(1)
    await act(async () => {
      resolve({ status: 'ok' })
      await Promise.resolve()
    })
  })

  it('가입이 거부되면 alert 로 알리고 다시 누를 수 있다', async () => {
    vi.mocked(signup).mockRejectedValueOnce(new Error('network'))
    const { user } = setup({ draft: EMAIL_DRAFT })
    await agreeAndSubmit(user)
    expect((await screen.findByRole('alert')).textContent).toBe(
      '가입하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(isOff(submit())).toBe(false)
    expect(loginWithEmail).not.toHaveBeenCalled()
    expect(saveRegion).not.toHaveBeenCalled()
    // 다시 보낼 수 있게 비밀번호를 남긴다
    expect(state().draft.password).toBe('dongne2026')
  })

  it('인증 시간이 지났으면(AUTH_007) 인증 · 비밀번호를 지우고 안내와 함께 이메일 단계로 보낸다', async () => {
    vi.mocked(signup).mockResolvedValueOnce({ status: 'verification-expired' })
    const { user } = setup({ draft: EMAIL_DRAFT })
    await agreeAndSubmit(user)

    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/signup/email?reason=verification-expired'),
    )
    // 안내 없는 이메일 단계로 한 번 더 보내 쿼리를 덮지 않는다
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(state().draft).toEqual({
      ...EMAIL_DRAFT,
      codeSentAt: null,
      verifiedAt: null,
      password: '',
    })
    expect(state().membership).toEqual(NO_MEMBERSHIP)
    expect(loginWithEmail).not.toHaveBeenCalled()
    expect(saveRegion).not.toHaveBeenCalled()
  })

  describe('가입에 들고 온 돌아갈 곳 (#140)', () => {
    const REPORT = { next: '/', region: '11680640', intent: 'report' as const }

    it('카카오 가입표가 없어 로그인 화면으로 갈 때 돌아갈 곳을 쿼리로 싣고 값은 남긴다', async () => {
      saveLoginReturn(REPORT)
      vi.mocked(signup).mockResolvedValueOnce({ status: 'kakao-restart', reason: 'expired' })
      const { user } = setup({ draft: KAKAO_DRAFT })
      await agreeAndSubmit(user)
      await waitFor(() =>
        expect(router.replace).toHaveBeenCalledWith(
          '/login?error=kakao-fail&kakao=expired&region=11680640&intent=report',
        ),
      )
      expect(peekLoginReturn()).toEqual(REPORT)
    })

    it('가입된 이메일로 로그인할 때도 이메일 로그인 주소에 싣는다 — 로그인 뒤 그곳으로 간다', async () => {
      saveLoginReturn({ next: '/me', region: null, intent: null })
      vi.mocked(signup).mockResolvedValueOnce({ status: 'email-taken' })
      const { user } = setup({ draft: EMAIL_DRAFT })
      await agreeAndSubmit(user)
      await user.click(await screen.findByRole('button', { name: '이메일로 로그인' }))
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/login/email?next=%2Fme'))
    })

    it('가입을 마쳐도 S02-4 가 읽도록 값을 남긴다', async () => {
      saveLoginReturn(REPORT)
      const { user } = setup({ draft: KAKAO_DRAFT })
      await agreeAndSubmit(user)
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
      expect(peekLoginReturn()).toEqual(REPORT)
    })
  })

  it('가입된 이메일(MEMBER_001)이면 alert 로 알리고, 이메일로 로그인을 누르면 비밀번호를 지우고 이메일 로그인으로 바꿔 간다', async () => {
    vi.mocked(signup).mockResolvedValueOnce({ status: 'email-taken' })
    const { user } = setup({ draft: EMAIL_DRAFT })
    await agreeAndSubmit(user)

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('이미 가입된 이메일이에요.')
    expect(loginWithEmail).not.toHaveBeenCalled()
    expect(saveRegion).not.toHaveBeenCalled()
    expect(state().membership).toEqual(NO_MEMBERSHIP)

    await user.click(screen.getByRole('button', { name: '이메일로 로그인' }))
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/login/email'))
    // 비밀번호를 지워도 이메일 단계로 한 번 더 보내지 않는다
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(state().draft.password).toBe('')
  })

  it('실데이터 모드면 가입 → 로그인 → 동네 저장을 API 로 보내고 로그인 응답으로 회원이 된다', async () => {
    selectApiSource()
    // 두 번째 인자(요청 설정)는 아래에서 동네 저장 본문을 확인하려고 받는다
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      void init
      const body = url.endsWith('/api/v1/auth/login')
        ? memberToken({ reportWritable: false })
        : url.endsWith('/api/v1/members/me/region')
          ? { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구', abolished: false }
          : null
      return Promise.resolve(
        new Response(
          JSON.stringify({
            dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
            dataBody: body,
          }),
          { status: 200 },
        ),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const { user } = setup({ draft: EMAIL_DRAFT })
    await agreeAndSubmit(user)

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
    const calls = fetchMock.mock.calls.map(([url]) => new URL(url).pathname)
    expect(calls).toEqual([
      '/api/v1/auth/signup',
      '/api/v1/auth/login',
      '/api/v1/members/me/region',
    ])
    // 동네는 코드만 보낸다
    expect(fetchMock.mock.calls[2]?.[1]).toMatchObject({
      method: 'PUT',
      body: JSON.stringify({ code: '11680640' }),
    })
    expect(signup).toHaveBeenCalledWith(expect.objectContaining({ kind: 'email' }), 'api')
    expect(loginWithEmail).toHaveBeenCalledWith('dong@example.com', 'dongne2026', 'api')
    expect(saveRegion).toHaveBeenCalledWith(DISTRICT, 'api')
    expect(getSessionSnapshot()).toMatchObject({
      status: 'member',
      summary: { reportWritable: false },
    })
    expect(state().draft.password).toBe('')
  })

  it.each([
    ['맞지 않음', () => vi.mocked(loginWithEmail).mockResolvedValueOnce({ status: 'wrong' })],
    ['잠김', () => vi.mocked(loginWithEmail).mockResolvedValueOnce({ status: 'locked' })],
    ['응답 없음', () => vi.mocked(loginWithEmail).mockRejectedValueOnce(new Error('network'))],
  ])(
    '가입 뒤 로그인이 %s 이면 비밀번호를 남기고, 다시 누르면 가입 없이 로그인부터 한다',
    async (_, failLogin) => {
      failLogin()
      const { user } = setup({ draft: EMAIL_DRAFT })
      await agreeAndSubmit(user)
      expect((await screen.findByRole('alert')).textContent).toBe(
        '가입은 됐지만 로그인하지 못했어요. 잠시 뒤 다시 눌러 주세요.',
      )
      expect(state().draft.password).toBe('dongne2026')
      expect(state().membership).toEqual({
        accountCreated: true,
        loggedIn: false,
        regionSaved: false,
      })
      expect(saveRegion).not.toHaveBeenCalled()
      expect(isOff(submit())).toBe(false)

      await user.click(submit())
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
      expect(signup).toHaveBeenCalledTimes(1)
      expect(loginWithEmail).toHaveBeenCalledTimes(2)
      expect(state().draft.password).toBe('')
    },
  )

  it('동네 저장만 실패하면 다시 누를 때 가입 · 로그인을 다시 보내지 않는다', async () => {
    vi.mocked(saveRegion).mockRejectedValueOnce(new Error('network'))
    const { user } = setup({ draft: EMAIL_DRAFT })
    await agreeAndSubmit(user)
    expect((await screen.findByRole('alert')).textContent).toContain('동네를 저장하지 못했어요')

    await user.click(submit())
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
    expect(signup).toHaveBeenCalledTimes(1)
    expect(loginWithEmail).toHaveBeenCalledTimes(1)
    expect(saveRegion).toHaveBeenCalledTimes(2)
  })

  it('고른 동네를 서버가 받지 않으면(invalid) 앞 단계에서 다시 고르라고 알리고 다음 단계로 가지 않는다', async () => {
    vi.mocked(saveRegion).mockResolvedValueOnce({ status: 'invalid' })
    const { user } = setup({ draft: EMAIL_DRAFT })
    await agreeAndSubmit(user)
    expect((await screen.findByRole('alert')).textContent).toBe(
      '고른 동네를 저장할 수 없어요. 앞 단계에서 동네를 다시 골라 주세요.',
    )
    expect(router.replace).not.toHaveBeenCalled()
    expect(state().membership).toMatchObject({ accountCreated: true, loggedIn: true })
    expect(state().membership.regionSaved).toBe(false)
  })

  describe('새 가입 시도는 앞선 가입 마무리 진행을 쓰지 않는다', () => {
    const STALE: Membership = { accountCreated: true, loggedIn: false, regionSaved: false }

    it('가입 뒤 로그인을 못 한 채 카카오로 다시 시작하면 가입부터 다시 보낸다', async () => {
      const { user, show } = setup({ draft: EMAIL_DRAFT, membership: STALE, page: null })
      show(<LoginScreen notice={null} />)
      expect(state().membership).toEqual(NO_MEMBERSHIP)
      await user.click(screen.getByRole('button', { name: '카카오로 계속하기' }))
      await waitFor(() => expect(router.push).toHaveBeenCalledWith('/setup/region?from=kakao'))

      show(<TermsScreen />)
      await agreeAndSubmit(user)
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
      expect(signup).toHaveBeenCalledTimes(1)
      expect(vi.mocked(signup).mock.calls[0]?.[0].kind).toBe('kakao')
      expect(loginWithEmail).not.toHaveBeenCalled()
    })

    it('가입 뒤 로그인을 못 한 채 새 이메일로 코드를 받으면 가입부터 다시 보낸다', async () => {
      const { user, show } = setup({ draft: EMAIL_DRAFT, membership: STALE, page: null })
      show(<SignupEmailScreen />)
      const email = screen.getByRole('textbox', { name: '이메일' })
      await user.clear(email)
      await user.type(email, 'new@example.com')
      await user.click(screen.getByRole('button', { name: '인증 코드 받기' }))
      await waitFor(() => expect(router.push).toHaveBeenCalledWith('/signup/code'))
      expect(state().membership).toEqual(NO_MEMBERSHIP)

      // 코드 확인 · 비밀번호 화면을 거친 셈으로 목 서버에 인증 표시를 만들고 초안을 채운다
      await verifyEmailCode('new@example.com', '482915', 'mock')
      show(<FillAccount />)
      await user.click(screen.getByRole('button', { name: '계정 채우기' }))

      show(<TermsScreen />)
      await agreeAndSubmit(user)
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
      expect(signup).toHaveBeenCalledTimes(1)
      expect(vi.mocked(signup).mock.calls[0]?.[0]).toMatchObject({
        kind: 'email',
        email: 'new@example.com',
      })
      expect(loginWithEmail).toHaveBeenCalledWith('new@example.com', 'dongne2026', 'mock')
    })
  })

  describe('응답을 기다리는 동안 화면을 떠나도 서버에서 끝난 단계는 남긴다', () => {
    it('가입 응답 전에 떠났다 돌아와 다시 누르면 가입을 다시 보내지 않는다', async () => {
      let resolve: (value: { status: 'ok' }) => void = () => {}
      vi.mocked(signup).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
      const { user, show } = setup({ draft: EMAIL_DRAFT })
      await agreeAndSubmit(user)
      show(null)
      await act(async () => {
        resolve({ status: 'ok' })
        await Promise.resolve()
      })
      expect(state().membership).toEqual({
        accountCreated: true,
        loggedIn: false,
        regionSaved: false,
      })
      // 떠난 뒤에는 로그인을 이어 보내지 않는다 — 비밀번호는 다음 제출을 위해 남긴다
      expect(loginWithEmail).not.toHaveBeenCalled()
      expect(state().draft.password).toBe('dongne2026')
      expect(router.replace).not.toHaveBeenCalled()

      show(<TermsScreen />)
      await agreeAndSubmit(user)
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
      expect(signup).toHaveBeenCalledTimes(1)
      expect(loginWithEmail).toHaveBeenCalledTimes(1)
    })

    it('로그인 응답 전에 떠났다 돌아와 다시 누르면 로그인을 다시 보내지 않는다', async () => {
      let resolve: (value: { status: 'ok' }) => void = () => {}
      vi.mocked(loginWithEmail).mockImplementationOnce(
        () => new Promise((done) => (resolve = done)),
      )
      const { user, show } = setup({ draft: EMAIL_DRAFT })
      await agreeAndSubmit(user)
      await waitFor(() => expect(loginWithEmail).toHaveBeenCalledTimes(1))
      show(null)
      await act(async () => {
        resolve({ status: 'ok' })
        await Promise.resolve()
      })
      expect(state().membership).toEqual({
        accountCreated: true,
        loggedIn: true,
        regionSaved: false,
      })
      expect(state().draft.password).toBe('')
      expect(saveRegion).not.toHaveBeenCalled()

      show(<TermsScreen />)
      await agreeAndSubmit(user)
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/health-consent'))
      expect(signup).toHaveBeenCalledTimes(1)
      expect(loginWithEmail).toHaveBeenCalledTimes(1)
      expect(saveRegion).toHaveBeenCalledTimes(1)
    })

    it('인증 만료 응답 전에 떠났으면 인증 · 비밀번호는 지우되 이동하지 않는다', async () => {
      let resolve: (value: { status: 'verification-expired' }) => void = () => {}
      vi.mocked(signup).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
      const { user, show } = setup({ draft: EMAIL_DRAFT })
      await agreeAndSubmit(user)
      show(null)
      await act(async () => {
        resolve({ status: 'verification-expired' })
        await Promise.resolve()
      })
      expect(state().draft).toMatchObject({ verifiedAt: null, codeSentAt: null, password: '' })
      expect(router.replace).not.toHaveBeenCalled()
    })
  })

  it.each([
    ['동네가 없으면 동네 선택', { district: null }, '/setup/region'],
    ['성인 확인이 없으면 성인 확인', { adult: false }, '/setup/adult'],
    [
      '이미 가입을 마쳤으면 증상 보고 동의',
      { membership: { accountCreated: true, loggedIn: true, regionSaved: true } },
      '/setup/health-consent',
    ],
    ['가입 종류를 모르면 로그인 방법 선택', { draft: EMPTY_SIGNUP }, '/login'],
    [
      '이메일 가입인데 인증을 마치지 않았으면 이메일 단계',
      { draft: { ...EMAIL_DRAFT, verifiedAt: null } },
      '/signup/email',
    ],
    [
      '이메일 가입인데 비밀번호가 없으면 이메일 단계',
      { draft: { ...EMAIL_DRAFT, password: '' } },
      '/signup/email',
    ],
    [
      '동네가 없으면 가입 종류보다 먼저 동네 선택',
      { district: null, draft: EMPTY_SIGNUP },
      '/setup/region',
    ],
  ] as const)('%s 로 돌려보낸다', (_, options, path) => {
    setup(options)
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith(path)
    expect(screen.queryByRole('heading')).toBeNull()
  })

  it('가입을 마친 뒤 로그인만 남았으면 인증 값이 없어도 돌려보내지 않는다', () => {
    setup({
      draft: { ...EMAIL_DRAFT, verifiedAt: null },
      membership: { accountCreated: true, loggedIn: false, regionSaved: false },
    })
    expect(router.replace).not.toHaveBeenCalled()
    expect(screen.getByRole('heading', { level: 1 })).toBeDefined()
  })

  it('주소로 바로 들어왔으면 뒤로는 성인 확인으로 바꿔 간다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/setup/adult')
  })
})
