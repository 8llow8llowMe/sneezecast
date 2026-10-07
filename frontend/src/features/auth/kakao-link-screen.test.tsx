// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { OnboardingProvider } from '@/features/onboarding/onboarding-context'
import { assignLocation } from '@/lib/location'
import { getSessionSnapshot } from '@/lib/session/session-store'
import {
  errorResponse,
  memberToken,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { getMockProfile, resetMockSession } from './auth-client'
import type * as kakaoClient from './kakao-client'
import { type KakaoLinkResult, linkKakaoAccount, startKakaoLogin } from './kakao-client'
import { KakaoLinkScreen } from './kakao-link-screen'
import {
  clearLoginReturn,
  LOGIN_RETURN_STORAGE_KEY,
  peekLoginReturn,
  saveLoginReturn,
} from './login-return-store'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/login/kakao/link',
}))

vi.mock('./kakao-client', async (importOriginal) => {
  const actual = await importOriginal<typeof kakaoClient>()
  return {
    ...actual,
    linkKakaoAccount: vi.fn(actual.linkKakaoAccount),
    startKakaoLogin: vi.fn(actual.startKakaoLogin),
  }
})
vi.mock('@/lib/location', () => ({ assignLocation: vi.fn() }))

const MASKED = 'd***@example.com'

function renderLink(email: string | null = MASKED) {
  return render(
    <OnboardingProvider initialKakaoLinkEmail={email}>
      <KakaoLinkScreen />
    </OnboardingProvider>,
  )
}

const linkButton = () => screen.getByRole('button', { name: '연결하고 계속하기' })
const switchButton = () =>
  screen.getByRole<HTMLButtonElement>('button', { name: '다른 카카오 계정으로 계속하기' })

describe('KakaoLinkScreen', () => {
  beforeEach(() => {
    router.push.mockClear()
    router.replace.mockClear()
    vi.mocked(linkKakaoAccount).mockReset()
    vi.mocked(startKakaoLogin).mockReset()
    vi.mocked(assignLocation).mockReset()
    resetMockSession()
    clearLoginReturn()
  })

  afterEach(() => resetApiSession())

  it('연결을 묻는 문장 · 가린 이메일 · 두 버튼을 보인다', () => {
    renderLink()
    const box = screen
      .getAllByRole('alert')
      .find((region) => region.textContent?.includes('카카오 로그인을 연결할까요?'))
    expect(box?.textContent).toContain('이 이메일로 가입된 계정이 있어요.')
    expect(box?.textContent).toContain(MASKED)
    expect(linkButton()).toBeDefined()
    expect(switchButton()).toBeDefined()
    expect(screen.getByText('연결해도 이메일과 비밀번호로 계속 로그인할 수 있어요.')).toBeDefined()
  })

  it('가린 이메일이 없으면(새로고침 · 바로 들어옴) 그리지 않고 로그인 화면으로 바꿔 간다', () => {
    renderLink(null)
    expect(router.replace).toHaveBeenCalledWith('/login')
    expect(screen.queryByRole('button', { name: '연결하고 계속하기' })).toBeNull()
  })

  it('연결하고 계속하기는 연결한 뒤 홈으로 기록을 바꿔 간다(목은 연결한 이메일 계정 세션)', async () => {
    renderLink()
    await userEvent.setup().click(linkButton())
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
    expect(linkKakaoAccount).toHaveBeenCalledWith('mock')
    expect(getMockProfile()).toMatchObject({ provider: 'kakao', hasPassword: true })
  })

  it('연결하는 동안 두 번 눌러도 한 번만 보내고, 다른 계정 버튼도 꺼진다', async () => {
    let resolve: (value: KakaoLinkResult) => void = () => {}
    vi.mocked(linkKakaoAccount).mockImplementationOnce(
      () => new Promise((done) => (resolve = done)),
    )
    const user = userEvent.setup()
    renderLink()
    await user.click(linkButton())
    await user.click(linkButton())
    expect(linkKakaoAccount).toHaveBeenCalledTimes(1)
    expect(switchButton().disabled).toBe(true)
    await act(async () => {
      resolve({ status: 'ok' })
      await Promise.resolve()
    })
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it.each([
    [{ status: 'restart', reason: 'expired' }, '/login?error=kakao-fail&kakao=expired'],
    [{ status: 'restart', reason: null }, '/login?error=kakao-fail'],
  ] as const)(
    '확인표가 지났거나 연결할 수 없으면(%o) 카카오 로그인부터 다시 하게 로그인 화면으로 간다',
    async (result, path) => {
      vi.mocked(linkKakaoAccount).mockResolvedValueOnce(result)
      renderLink()
      await userEvent.setup().click(linkButton())
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith(path))
    },
  )

  it('연결이 거부되면 알리고 다시 누를 수 있다', async () => {
    vi.mocked(linkKakaoAccount).mockRejectedValueOnce(new Error('network'))
    const user = userEvent.setup()
    renderLink()
    await user.click(linkButton())
    expect(
      (await screen.findByText('연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.')).closest(
        '[role="alert"]',
      ),
    ).not.toBeNull()
    expect(router.replace).not.toHaveBeenCalled()
    expect(switchButton().disabled).toBe(false)

    await user.click(linkButton())
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
  })

  it('다른 카카오 계정으로 계속하기는 계정 고르기(switchAccount)로 카카오 로그인을 다시 시작한다', async () => {
    vi.mocked(startKakaoLogin).mockResolvedValueOnce({
      status: 'redirect',
      href: 'https://kauth.kakao.com/oauth/authorize?prompt=select_account&state=s',
      external: true,
    })
    renderLink()
    await userEvent.setup().click(switchButton())
    await waitFor(() =>
      expect(assignLocation).toHaveBeenCalledWith(
        'https://kauth.kakao.com/oauth/authorize?prompt=select_account&state=s',
      ),
    )
    expect(startKakaoLogin).toHaveBeenCalledWith('mock', { switchAccount: true })
    expect(linkKakaoAccount).not.toHaveBeenCalled()
  })

  it('목에서 다른 카카오 계정으로 계속하기는 신규 회원으로 보아 동네 선택으로 간다', async () => {
    renderLink()
    await userEvent.setup().click(switchButton())
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/setup/region?from=kakao'))
  })

  it('다른 계정으로 시작하지 못하면(요청 많음) 알린다', async () => {
    vi.mocked(startKakaoLogin).mockResolvedValueOnce({ status: 'limited' })
    renderLink()
    await userEvent.setup().click(switchButton())
    expect(
      await screen.findByText('요청이 많아 잠시 막혔어요. 잠시 뒤 다시 시도해 주세요.'),
    ).toBeDefined()
    expect(switchButton().disabled).toBe(false)
  })

  it('주소로 바로 들어온 셈이면 뒤로는 로그인 화면으로 바꿔 간다', async () => {
    renderLink()
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/login')
  })

  describe('카카오로 떠나기 전에 둔 돌아갈 곳 (#140)', () => {
    const ME = { next: '/me', region: '11440660', intent: null }

    it('연결에 성공하면 그곳으로 가고 둔 값을 지운다 (목)', async () => {
      saveLoginReturn(ME)
      renderLink()
      await userEvent.setup().click(linkButton())
      await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/me?region=11440660'))
      expect(peekLoginReturn()).toEqual({ next: '/', region: null, intent: null })
    })

    it('실데이터 연결 성공도 보고하려던 로그인이면 같은 동네 홈의 보고 진입으로 간다', async () => {
      selectApiSource()
      window.sessionStorage.setItem(
        LOGIN_RETURN_STORAGE_KEY,
        JSON.stringify({
          v: 1,
          next: '/',
          region: '11680640',
          intent: 'report',
          savedAt: Date.now(),
        }),
      )
      vi.stubGlobal(
        'fetch',
        vi.fn(() => Promise.resolve(okResponse(memberToken()))),
      )
      renderLink()
      await userEvent.setup().click(linkButton())
      await waitFor(() =>
        expect(router.replace).toHaveBeenCalledWith('/?region=11680640&report=start'),
      )
    })

    it('확인표가 지나 로그인 화면으로 가면 둔 값을 쿼리로 싣는다', async () => {
      saveLoginReturn(ME)
      vi.mocked(linkKakaoAccount).mockResolvedValueOnce({ status: 'restart', reason: 'expired' })
      renderLink()
      await userEvent.setup().click(linkButton())
      await waitFor(() =>
        expect(router.replace).toHaveBeenCalledWith(
          '/login?error=kakao-fail&kakao=expired&next=%2Fme&region=11440660',
        ),
      )
    })

    it('가린 이메일이 없어 로그인 화면으로 돌려보낼 때도 둔 값을 싣는다', () => {
      saveLoginReturn(ME)
      renderLink(null)
      expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fme&region=11440660')
    })

    it('다른 카카오 계정으로 계속하기는 둔 값을 그대로 다시 들고 간다', async () => {
      saveLoginReturn(ME)
      renderLink()
      await userEvent.setup().click(switchButton())
      // 목은 곧바로 가입 동네 고르기다 — 둔 둘러보기 동네를 처음 선택으로 싣는다(#227)
      await waitFor(() =>
        expect(router.push).toHaveBeenCalledWith('/setup/region?from=kakao&region=11440660'),
      )
      expect(peekLoginReturn()).toEqual(ME)
    })
  })

  it('실데이터면 POST /kakao/link 를 인증 없이 보내고 응답으로 회원이 된다', async () => {
    selectApiSource()
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      void url
      void init
      return Promise.resolve(okResponse(memberToken()))
    })
    vi.stubGlobal('fetch', fetchMock)
    renderLink()
    await userEvent.setup().click(linkButton())

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(new URL(String(url)).pathname).toBe('/api/v1/auth/kakao/link')
    expect(init).toMatchObject({ method: 'POST', credentials: 'include' })
    expect(init?.body).toBeUndefined()
    expect(getSessionSnapshot()).toMatchObject({ status: 'member' })
  })

  it('실데이터 확인표 만료(AUTH_026)면 사유를 실어 로그인 화면으로 간다', async () => {
    selectApiSource()
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(errorResponse('AUTH_026', 400))),
    )
    renderLink()
    await userEvent.setup().click(linkButton())
    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/login?error=kakao-fail&kakao=expired'),
    )
    expect(getSessionSnapshot()).not.toMatchObject({ status: 'member' })
  })

  it('실데이터에서 서비스가 업무 오류(AUTH_017)로 답하면 확인표를 잃었으므로 바로 카카오 로그인부터 다시 하게 한다', async () => {
    selectApiSource()
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(errorResponse('AUTH_017', 503))),
    )
    renderLink()
    await userEvent.setup().click(linkButton())
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/login?error=kakao-fail'))
    expect(screen.queryByText('연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.')).toBeNull()
  })

  it('실데이터에서 응답을 받지 못하면(네트워크) 상자로 알리고 다시 누르게 한다', async () => {
    selectApiSource()
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))),
    )
    renderLink()
    await userEvent.setup().click(linkButton())
    expect(await screen.findByText('연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.')).toBeDefined()
    expect(router.replace).not.toHaveBeenCalled()
  })
})

/** 뒤로 가기 캐시에 들어가기 직전 (#186) */
const freezePage = (persisted = true) =>
  act(() => {
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted }))
  })

describe('KakaoLinkScreen 뒤로 가기 캐시 (#186)', () => {
  it('얼기 직전에 가린 이메일을 비우고 로그인 화면으로 보낸다 — 다음 사람이 연결하고 계속하기를 누를 수 없다', () => {
    router.replace.mockClear()
    renderLink()
    expect(linkButton()).toBeDefined()

    freezePage()

    expect(screen.queryByRole('button', { name: '연결하고 계속하기' })).toBeNull()
    expect(screen.queryByText(MASKED)).toBeNull()
    expect(router.replace).toHaveBeenCalledWith('/login')
  })
})
