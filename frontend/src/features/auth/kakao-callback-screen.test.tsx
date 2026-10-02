// @vitest-environment jsdom
import { StrictMode } from 'react'

import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { OnboardingProvider, useOnboarding } from '@/features/onboarding/onboarding-context'
import { KAKAO_CALLBACK_PATH } from '@/features/onboarding/paths'
import { writeSessionHint } from '@/lib/session/session-hint'
import { getSessionSnapshot } from '@/lib/session/session-store'
import {
  errorResponse,
  holdRequests,
  memberToken,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { getMockSession, resetMockSession } from './auth-client'
import { KakaoCallbackScreen, readKakaoCallback } from './kakao-callback-screen'
import type * as kakaoClient from './kakao-client'
import { kakaoLogin, type KakaoLoginResult } from './kakao-client'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/login/kakao/callback',
}))

vi.mock('./kakao-client', async (importOriginal) => {
  const actual = await importOriginal<typeof kakaoClient>()
  return { ...actual, kakaoLogin: vi.fn(actual.kakaoLogin) }
})

const CALLBACK = KAKAO_CALLBACK_PATH

function LinkProbe() {
  const { kakaoLinkEmail } = useOnboarding()
  return <span data-testid="link">{kakaoLinkEmail ?? 'none'}</span>
}

function visit(search: string, { strict = false } = {}) {
  window.history.replaceState(null, '', `${CALLBACK}${search}`)
  const tree = (
    <OnboardingProvider>
      <KakaoCallbackScreen />
      <LinkProbe />
    </OnboardingProvider>
  )
  return render(strict ? <StrictMode>{tree}</StrictMode> : tree)
}

/** 응답을 테스트가 정할 때까지 붙잡아 둔다 */
function holdLogin() {
  let resolve: (result: KakaoLoginResult) => void = () => {}
  let reject: (error: Error) => void = () => {}
  vi.mocked(kakaoLogin).mockImplementationOnce(
    () =>
      new Promise((res, rej) => {
        resolve = res
        reject = rej
      }),
  )
  return {
    resolve: (result: KakaoLoginResult) => resolve(result),
    reject: () => reject(new Error()),
  }
}

describe('readKakaoCallback', () => {
  it.each([
    ['?code=c1&state=s1', { code: 'c1', state: 's1' }],
    ['?state=s1&code=c1&extra=1', { code: 'c1', state: 's1' }],
    // 사용자 취소 등 카카오가 error 를 붙이면 code 가 있어도 보내지 않는다
    ['?error=access_denied&error_description=cancel&state=s1', null],
    ['?code=c1&state=s1&error=x', null],
    ['?code=c1', null],
    ['?state=s1', null],
    ['?code=&state=s1', null],
    ['', null],
  ])('%s → %o', (search, expected) => {
    expect(readKakaoCallback(search)).toEqual(expected)
  })
})

describe('KakaoCallbackScreen', () => {
  beforeEach(() => {
    router.replace.mockClear()
    // 붙잡아 둔 채 끝난 테스트의 1회용 구현이 남지 않게 원래 구현으로 되돌린다
    vi.mocked(kakaoLogin).mockReset()
    resetMockSession()
  })

  afterEach(() => resetApiSession())

  it('기다리는 동안 안내를 보인다', () => {
    holdLogin()
    visit('?code=c1&state=s1')
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
      '카카오 로그인을 확인하고 있어요',
    )
  })

  it('code · state 를 주소에서 지운 뒤 한 번만 보낸다(StrictMode 의 effect 두 번 실행)', async () => {
    let searchAtRequest: string | null = null
    vi.mocked(kakaoLogin).mockImplementationOnce(() => {
      searchAtRequest = window.location.search
      return Promise.resolve({ status: 'signup-required' })
    })
    visit('?code=c1&state=s1', { strict: true })
    await waitFor(() => expect(router.replace).toHaveBeenCalled())

    expect(kakaoLogin).toHaveBeenCalledTimes(1)
    expect(kakaoLogin).toHaveBeenCalledWith('c1', 's1', 'mock')
    // 요청을 보낼 때 이미 주소에서 지웠다 — 방문 기록 · 리퍼러 · 새로고침에 code 가 남지 않는다
    expect(searchAtRequest).toBe('')
    expect(window.location.pathname).toBe(CALLBACK)
    expect(window.location.search).toBe('')
  })

  it('로그인됨이면 홈으로 기록을 바꿔 간다', async () => {
    vi.mocked(kakaoLogin).mockResolvedValueOnce({ status: 'logged-in' })
    visit('?code=c1&state=s1')
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
    expect(router.replace).toHaveBeenCalledTimes(1)
  })

  it('가입이 필요하면 카카오 가입 종류를 되살리는 동네 선택(?from=kakao)으로 간다', async () => {
    vi.mocked(kakaoLogin).mockResolvedValueOnce({ status: 'signup-required' })
    visit('?code=c1&state=s1')
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/setup/region?from=kakao'))
  })

  it('연결이 필요하면 가린 이메일을 메모리(Provider)에만 두고 계정 연결 확인으로 간다', async () => {
    vi.mocked(kakaoLogin).mockResolvedValueOnce({
      status: 'link-required',
      maskedEmail: 'd***@example.com',
    })
    visit('?code=c1&state=s1')
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/login/kakao/link'))
    expect(screen.getByTestId('link').textContent).toBe('d***@example.com')
    // 주소에는 이메일을 싣지 않는다
    expect(String(router.replace.mock.calls[0]?.[0])).not.toContain('example.com')
  })

  it('사유가 있는 실패면 사유를 실어 로그인 화면으로 간다', async () => {
    vi.mocked(kakaoLogin).mockResolvedValueOnce({ status: 'failed', reason: 'email-required' })
    visit('?code=c1&state=s1')
    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith('/login?error=kakao-fail&kakao=email-required'),
    )
  })

  it('거부되면 사유 없이 로그인 화면으로 간다', async () => {
    const hold = holdLogin()
    visit('?code=c1&state=s1')
    await waitFor(() => expect(kakaoLogin).toHaveBeenCalled())
    hold.reject()
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/login?error=kakao-fail'))
  })

  it.each([
    ['사용자 취소(error)', '?error=access_denied&error_description=User%20denied&state=s1'],
    ['code 없음', '?state=s1'],
    ['state 없음', '?code=c1'],
    ['쿼리 없음(새로고침)', ''],
  ])('%s 이면 보내지 않고 주소를 지운 뒤 로그인 화면으로 간다', async (_, search) => {
    visit(search)
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/login?error=kakao-fail'))
    expect(kakaoLogin).not.toHaveBeenCalled()
    expect(window.location.search).toBe('')
  })

  it('응답 전에 화면을 떠나면 이동하지 않는다', async () => {
    const hold = holdLogin()
    const { unmount } = visit('?code=c1&state=s1')
    await waitFor(() => expect(kakaoLogin).toHaveBeenCalled())
    unmount()
    await act(async () => {
      hold.resolve({ status: 'logged-in' })
      await Promise.resolve()
    })
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('실데이터면 쿠키의 출처로 POST /kakao/login 을 보내고 LOGGED_IN 응답으로 회원이 된다', async () => {
    selectApiSource()
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      void url
      void init
      return Promise.resolve(okResponse({ result: 'LOGGED_IN', ...memberToken() }))
    })
    vi.stubGlobal('fetch', fetchMock)
    visit('?code=c1&state=s1')

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(new URL(String(url)).pathname).toBe('/api/v1/auth/kakao/login')
    // code 는 주소 쿼리가 아니라 본문으로만 간다
    expect(new URL(String(url)).search).toBe('')
    expect(init).toMatchObject({
      method: 'POST',
      credentials: 'include',
      body: JSON.stringify({ code: 'c1', state: 's1' }),
    })
    expect(new Headers(init?.headers).has('Authorization')).toBe(false)
    expect(getSessionSnapshot()).toMatchObject({ status: 'member' })
    expect(getMockSession()).toBe('guest')
  })

  it('실데이터에서 새로고침 복원(재발급)이 진행 중이면 그 응답을 받은 뒤에 카카오 로그인을 보낸다', async () => {
    selectApiSource()
    // 세션 힌트가 있어 복원이 재발급을 보낸다 — 늦게 온 재발급의 refresh 쿠키가 카카오 로그인의 쿠키를 덮지 않게 순서를 지킨다
    writeSessionHint(true)
    const server = holdRequests()
    visit('?code=c1&state=s1')

    await waitFor(() => expect(server.requests()).toEqual(['POST /api/v1/auth/token/reissue']))
    // 재발급을 기다리는 동안에는 카카오 로그인을 보내지 않는다
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20))
    })
    expect(server.requests()).toEqual(['POST /api/v1/auth/token/reissue'])

    server.reply('POST /api/v1/auth/token/reissue', errorResponse('AUTH_014', 401))
    await waitFor(() =>
      expect(server.requests()).toEqual([
        'POST /api/v1/auth/token/reissue',
        'POST /api/v1/auth/kakao/login',
      ]),
    )
    server.reply(
      'POST /api/v1/auth/kakao/login',
      okResponse({ result: 'LOGGED_IN', ...memberToken() }),
    )
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
    expect(getSessionSnapshot()).toMatchObject({ status: 'member' })
  })
})
