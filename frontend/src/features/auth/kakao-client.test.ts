import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import type * as sessionStore from '@/lib/session/session-store'
import { type AuthToken, setSession } from '@/lib/session/session-store'

import { EXAMPLE_PROFILES, getMockProfile, getMockSession, resetMockSession } from './auth-client'
import {
  isKakaoAuthorizeUrl,
  kakaoLogin,
  linkKakaoAccount,
  MOCK_KAKAO_MASKED_EMAIL,
  startKakaoLogin,
} from './kakao-client'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))
vi.mock('@/lib/session/session-store', async (importOriginal) => ({
  ...(await importOriginal<typeof sessionStore>()),
  setSession: vi.fn(),
}))

const TOKEN: AuthToken = {
  memberId: '1843956734582784',
  role: 'USER',
  accessToken: 'access-1',
  accessTokenExpiresIn: 900,
  pendingConsents: [],
  reportWritable: false,
}

const AUTHORIZE_URL =
  'https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=k&redirect_uri=https%3A%2F%2Fdev.sneezecast.com%2Flogin%2Fkakao%2Fcallback&state=s1'

function apiError(code: string, status: number): ApiError {
  return new ApiError({ status, code, message: '거절' })
}

beforeEach(() => {
  vi.mocked(apiRequest).mockReset()
  vi.mocked(setSession).mockReset()
  resetMockSession()
})

describe('isKakaoAuthorizeUrl', () => {
  it.each([
    [AUTHORIZE_URL, true],
    ['https://kauth.kakao.com:443/oauth/authorize?state=s', true],
    // 다른 스킴 · 호스트 · 경로 · 포트 · 사용자 정보는 따르지 않는다
    ['http://kauth.kakao.com/oauth/authorize?state=s', false],
    ['https://kauth.kakao.com.evil.example/oauth/authorize', false],
    ['https://evil.example/oauth/authorize?kauth.kakao.com', false],
    ['https://kapi.kakao.com/oauth/authorize', false],
    ['https://kauth.kakao.com/oauth/token', false],
    ['https://kauth.kakao.com:8443/oauth/authorize', false],
    ['https://user:pass@kauth.kakao.com/oauth/authorize', false],
    ['//kauth.kakao.com/oauth/authorize', false],
    ['javascript:alert(1)', false],
    ['', false],
    [null, false],
    [42, false],
  ] as const)('%s → %s', (value, expected) => {
    expect(isKakaoAuthorizeUrl(value)).toBe(expected)
  })
})

describe('startKakaoLogin', () => {
  it('목은 요청 없이 신규 회원으로 보아 동네 선택(?from=kakao)으로 보낸다', async () => {
    expect(await startKakaoLogin('mock')).toEqual({
      status: 'redirect',
      href: '/setup/region?from=kakao',
      external: false,
    })
    expect(await startKakaoLogin('mock', { switchAccount: true })).toEqual({
      status: 'redirect',
      href: '/setup/region?from=kakao',
      external: false,
    })
    expect(apiRequest).not.toHaveBeenCalled()
  })

  it('실데이터는 인증 없이 인가 주소를 받아 앱 밖으로 보낸다(switchAccount 기본 false)', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ authorizeUrl: AUTHORIZE_URL })
    expect(await startKakaoLogin('api')).toEqual({
      status: 'redirect',
      href: AUTHORIZE_URL,
      external: true,
    })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/kakao/authorize', {
      query: { switchAccount: false },
      auth: false,
    })
  })

  it('다른 카카오 계정으로 계속하기는 switchAccount=true 로 받는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ authorizeUrl: AUTHORIZE_URL })
    await startKakaoLogin('api', { switchAccount: true })
    expect(vi.mocked(apiRequest).mock.calls[0]?.[1]).toEqual({
      query: { switchAccount: true },
      auth: false,
    })
  })

  it.each([
    ['https://evil.example/oauth/authorize'],
    ['http://kauth.kakao.com/oauth/authorize'],
    [undefined],
  ])('인가 주소가 카카오 인가 주소가 아니면(%s) 따르지 않고 거부한다', async (authorizeUrl) => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ authorizeUrl })
    await expect(startKakaoLogin('api')).rejects.toThrow('unexpected authorize url')
  })

  it('본문이 비면 거부한다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await expect(startKakaoLogin('api')).rejects.toThrow(Error)
  })

  it('AUTH_028(429)이면 limited 다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_028', 429))
    expect(await startKakaoLogin('api')).toEqual({ status: 'limited' })
  })

  it('일시 장애 · 저장소 장애는 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(startKakaoLogin('api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_006', 503))
    await expect(startKakaoLogin('api')).rejects.toThrow(ApiError)
  })
})

describe('kakaoLogin (API)', () => {
  it('code · state 를 본문으로 인증 없이 한 번 보낸다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ result: 'SIGNUP_REQUIRED', nickname: '재채기' })
    expect(await kakaoLogin('code-1', 'state-1', 'api')).toEqual({ status: 'signup-required' })
    expect(apiRequest).toHaveBeenCalledTimes(1)
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/kakao/login', {
      method: 'POST',
      body: { code: 'code-1', state: 'state-1' },
      auth: false,
    })
    expect(setSession).not.toHaveBeenCalled()
  })

  it('LOGGED_IN 이면 로그인 응답 필드만 세션 저장소에 넣는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ result: 'LOGGED_IN', ...TOKEN })
    expect(await kakaoLogin('code-1', 'state-1', 'api')).toEqual({ status: 'logged-in' })
    expect(setSession).toHaveBeenCalledWith(TOKEN)
    // 목 세션은 건드리지 않는다
    expect(getMockSession()).toBe('guest')
  })

  it('LOGGED_IN 인데 로그인 응답 필드가 빠졌으면 세션을 만들지 않고 거부한다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ result: 'LOGGED_IN', memberId: '1' })
    await expect(kakaoLogin('code-1', 'state-1', 'api')).rejects.toThrow(Error)
    expect(setSession).not.toHaveBeenCalled()
  })

  it('LINK_REQUIRED 면 가린 이메일을 돌려준다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({
      result: 'LINK_REQUIRED',
      email: 'd***@example.com',
    })
    expect(await kakaoLogin('code-1', 'state-1', 'api')).toEqual({
      status: 'link-required',
      maskedEmail: 'd***@example.com',
    })
    expect(setSession).not.toHaveBeenCalled()
  })

  it('LINK_REQUIRED 인데 이메일이 없거나 모르는 결과면 거부한다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ result: 'LINK_REQUIRED' })
    await expect(kakaoLogin('code-1', 'state-1', 'api')).rejects.toThrow(Error)
    vi.mocked(apiRequest).mockResolvedValueOnce({ result: 'SOMETHING_NEW' })
    await expect(kakaoLogin('code-1', 'state-1', 'api')).rejects.toThrow(Error)
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await expect(kakaoLogin('code-1', 'state-1', 'api')).rejects.toThrow(Error)
  })

  it.each([
    ['AUTH_023', 400, 'email-required'],
    ['AUTH_024', 400, 'email-unverified'],
    ['MEMBER_003', 403, 'suspended'],
  ] as const)('%s 면 사유 %s 의 실패다', async (code, status, reason) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    expect(await kakaoLogin('code-1', 'state-1', 'api')).toEqual({ status: 'failed', reason })
  })

  it.each([
    ['AUTH_020', 400],
    ['AUTH_021', 400],
    ['AUTH_022', 503],
    // 탈퇴는 이메일 로그인처럼 따로 드러내지 않는다
    ['MEMBER_002', 403],
    ['AUTH_017', 503],
    ['AUTH_117', 400],
  ] as const)('%s 는 사유 없는 실패로 거부한다', async (code, status) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    await expect(kakaoLogin('code-1', 'state-1', 'api')).rejects.toThrow(ApiError)
    expect(setSession).not.toHaveBeenCalled()
  })
})

describe('kakaoLogin (목)', () => {
  it('요청 없이 code 로 결과를 고른다', async () => {
    expect(await kakaoLogin('anything', 'mock', 'mock')).toEqual({ status: 'signup-required' })
    expect(await kakaoLogin('link', 'mock', 'mock')).toEqual({
      status: 'link-required',
      maskedEmail: MOCK_KAKAO_MASKED_EMAIL,
    })
    expect(getMockSession()).toBe('guest')
    expect(apiRequest).not.toHaveBeenCalled()
  })

  it('login 이면 카카오 회원 목 세션이 된다', async () => {
    expect(await kakaoLogin('login', 'mock', 'mock')).toEqual({ status: 'logged-in' })
    expect(getMockSession()).toBe('member-no-consent')
    expect(getMockProfile()).toEqual(EXAMPLE_PROFILES.kakao)
    expect(setSession).not.toHaveBeenCalled()
  })

  it('서버 오류 코드 모양의 code 는 실데이터가 그 오류를 받은 것과 같다', async () => {
    expect(await kakaoLogin('AUTH_023', 'mock', 'mock')).toEqual({
      status: 'failed',
      reason: 'email-required',
    })
    await expect(kakaoLogin('AUTH_020', 'mock', 'mock')).rejects.toThrow(ApiError)
  })
})

describe('linkKakaoAccount', () => {
  it('바디 없이 인증 없이 보내고, 응답 토큰을 그대로 세션 저장소에 넣는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(TOKEN)
    expect(await linkKakaoAccount('api')).toEqual({ status: 'ok' })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/kakao/link', {
      method: 'POST',
      auth: false,
    })
    expect(setSession).toHaveBeenCalledWith(TOKEN)
  })

  it.each([
    ['AUTH_026', 400, 'expired'],
    ['AUTH_027', 409, null],
  ] as const)('%s 면 카카오 로그인부터 다시(사유 %s)다', async (code, status, reason) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    expect(await linkKakaoAccount('api')).toEqual({ status: 'restart', reason })
    expect(setSession).not.toHaveBeenCalled()
  })

  it.each([
    ['AUTH_017', 503],
    ['AUTH_006', 503],
    ['MEMBER_004', 404],
  ] as const)(
    '서비스가 그 밖의 업무 오류(%s)로 답하면 확인표를 이미 잃었으므로 사유 없이 다시 시작이다',
    async (code, status) => {
      vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
      expect(await linkKakaoAccount('api')).toEqual({ status: 'restart', reason: null })
      expect(setSession).not.toHaveBeenCalled()
    },
  )

  it('응답을 받지 못한 실패(네트워크 · 타임아웃 · 봉투 없음 · 게이트웨이)는 거부한다 — 확인표가 남았을 수 있다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('timeout', 0))
    await expect(linkKakaoAccount('api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(linkKakaoAccount('api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('no-envelope', 502))
    await expect(linkKakaoAccount('api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('GATEWAY_004', 504))
    await expect(linkKakaoAccount('api')).rejects.toThrow(ApiError)
  })

  it('목은 요청 없이 연결한 이메일 계정(카카오 · 비밀번호 있음)의 미동의 회원이 된다', async () => {
    expect(await linkKakaoAccount('mock')).toEqual({ status: 'ok' })
    expect(apiRequest).not.toHaveBeenCalled()
    expect(getMockSession()).toBe('member-no-consent')
    expect(getMockProfile()).toMatchObject({
      provider: 'kakao',
      email: 'dong@example.com',
      hasPassword: true,
    })
  })
})
