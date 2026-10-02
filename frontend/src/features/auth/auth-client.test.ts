import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import {
  type AuthToken,
  clearSession,
  getSessionSnapshot,
  setSession,
} from '@/lib/session/session-store'
import { clearSessionExpiring, isSessionExpiring, notifySessionExpired } from '@/lib/session-expiry'

import {
  agreeHealthConsent,
  agreeTermsReconsent,
  changePassword,
  CODE_MAX_ATTEMPTS,
  EMAIL_VERIFICATION_TTL_SECONDS,
  EXAMPLE_PROFILES,
  getMockProfile,
  getMockSession,
  listSessions,
  loginWithEmail,
  logout,
  PASSWORD_RESET_TOKEN_TTL_SECONDS,
  resetMockSession,
  resetPassword,
  revokeOtherSessions,
  revokeSession,
  saveRegion,
  sendEmailCode,
  sendPasswordResetCode,
  setupPassword,
  signup,
  type SignupRequest,
  startKakaoLogin,
  subscribeMockSession,
  verifyEmailCode,
  verifyPasswordResetCode,
  withdrawHealthConsent,
  withdrawMembership,
} from './auth-client'
import { consentFor, LEGAL_VERSIONS } from './legal'
import { setMemberRegion } from './member-info'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))
vi.mock('@/lib/session/session-store', () => ({
  setSession: vi.fn(),
  clearSession: vi.fn(),
  getSessionSnapshot: vi.fn(),
}))
vi.mock('./member-info', () => ({ setMemberRegion: vi.fn() }))

beforeEach(() => {
  vi.mocked(apiRequest).mockReset()
  vi.mocked(setSession).mockReset()
  vi.mocked(clearSession).mockReset()
  vi.mocked(getSessionSnapshot).mockReset()
  vi.mocked(setMemberRegion).mockReset()
  vi.mocked(getSessionSnapshot).mockReturnValue({
    status: 'member',
    summary: { memberId: '1', role: 'USER', pendingConsents: [], reportWritable: true },
  })
})

function apiError(code: string, status: number): ApiError {
  return new ApiError({ status, code, message: '거절' })
}

const YEOKSAM1 = { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }

describe('loginWithEmail (목)', () => {
  it('재현용 잠긴 이메일이면 locked 다 (대소문자 · 앞뒤 공백 무시)', async () => {
    expect(await loginWithEmail(' Locked@Example.com ', 'anything', 'mock')).toEqual({
      status: 'locked',
    })
  })

  it('재현용 비밀번호면 wrong 이다', async () => {
    expect(await loginWithEmail('dong@example.com', 'wrong', 'mock')).toEqual({ status: 'wrong' })
  })

  it('재현용 이메일이면 limited · suspended 다', async () => {
    expect(await loginWithEmail('limit@example.com', 'dongne2026', 'mock')).toEqual({
      status: 'limited',
    })
    expect(await loginWithEmail('Suspended@example.com', 'dongne2026', 'mock')).toEqual({
      status: 'suspended',
    })
  })

  it('그 밖에는 성공이다', async () => {
    expect(await loginWithEmail('dong@example.com', 'dongne2026', 'mock')).toEqual({ status: 'ok' })
  })

  it('API · 세션 저장소를 부르지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    expect(apiRequest).not.toHaveBeenCalled()
    expect(setSession).not.toHaveBeenCalled()
  })
})

describe('startKakaoLogin (목)', () => {
  it('신규 회원으로 보고 카카오에서 왔다는 표시(?from=kakao)를 붙여 동네 선택으로 보낸다', async () => {
    expect(await startKakaoLogin()).toEqual({ redirectTo: '/setup/region?from=kakao' })
    expect(await startKakaoLogin({ switchAccount: true })).toEqual({
      redirectTo: '/setup/region?from=kakao',
    })
  })
})

describe('sendEmailCode (목)', () => {
  it.each([
    [' LIMIT@example.com ', 'limit'],
    ['dong@example.com', 'sent'],
    // 가입 여부를 드러내지 않는다 — 이미 가입한 이메일도 같은 응답이다
    ['exists@example.com', 'sent'],
  ] as const)('%s → %s', async (email, status) => {
    expect(await sendEmailCode(email, 'mock')).toEqual({ status })
  })
})

describe('verifyEmailCode (목)', () => {
  afterEach(() => vi.useRealTimers())

  it('맞는 6자리면 ok 만 돌려준다(토큰 없음)', async () => {
    await sendEmailCode('dong@example.com', 'mock')
    expect(await verifyEmailCode('dong@example.com', '482915', 'mock')).toEqual({ status: 'ok' })
  })

  it('보낸 코드가 없거나 이미 인증에 쓴 코드면 expired 다', async () => {
    expect(await verifyEmailCode('never@example.com', '482915', 'mock')).toEqual({
      status: 'expired',
    })
    await sendEmailCode('used@example.com', 'mock')
    await verifyEmailCode('used@example.com', '482915', 'mock')
    expect(await verifyEmailCode('used@example.com', '482915', 'mock')).toEqual({
      status: 'expired',
    })
  })

  it('5번까지 틀릴 수 있다 — 남은 시도가 4번부터 줄고 5번째에 locked 다', async () => {
    expect(CODE_MAX_ATTEMPTS).toBe(5)
    await sendEmailCode('try@example.com', 'mock')
    for (const remainingAttempts of [4, 3, 2, 1]) {
      expect(await verifyEmailCode('try@example.com', '000000', 'mock')).toEqual({
        status: 'wrong',
        remainingAttempts,
      })
    }
    expect(await verifyEmailCode('try@example.com', '000000', 'mock')).toEqual({ status: 'locked' })
    // 서버처럼 잠기면 코드를 지운다 — 맞는 코드를 넣어도 만료다
    expect(await verifyEmailCode('try@example.com', '482915', 'mock')).toEqual({
      status: 'expired',
    })
    // 다시 받으면 실패 수가 처음으로 돌아간다
    await sendEmailCode('try@example.com', 'mock')
    expect(await verifyEmailCode('try@example.com', '000000', 'mock')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })
  })

  it('다시 받으면 남은 시도가 처음으로 돌아간다', async () => {
    await sendEmailCode('again@example.com', 'mock')
    await verifyEmailCode('again@example.com', '000000', 'mock')
    await sendEmailCode('again@example.com', 'mock')
    expect(await verifyEmailCode('again@example.com', '000000', 'mock')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })
  })

  it('재현용 잠김 코드면 locked, 5분이 지나면 expired 다', async () => {
    await sendEmailCode('late@example.com', 'mock')
    expect(await verifyEmailCode('late@example.com', '999999', 'mock')).toEqual({
      status: 'locked',
    })
    expect(await verifyEmailCode('late@example.com', '482915', 'mock')).toEqual({
      status: 'expired',
    })

    vi.useFakeTimers()
    await sendEmailCode('late@example.com', 'mock')
    vi.setSystemTime(Date.now() + 301_000)
    expect(await verifyEmailCode('late@example.com', '482915', 'mock')).toEqual({
      status: 'expired',
    })
  })
})

describe('signup · saveRegion · agreeHealthConsent (목)', () => {
  const consents = [consentFor('TERMS_OF_SERVICE')]
  const emailSignup = (email: string) =>
    signup({ kind: 'email', email, password: 'dongne2026', nickname: '동네지기', consents }, 'mock')

  async function verify(email: string) {
    await sendEmailCode(email, 'mock')
    await verifyEmailCode(email, '482915', 'mock')
  }

  afterEach(() => vi.useRealTimers())

  it('인증을 마친 이메일 가입 · 카카오 가입은 성공한다', async () => {
    await verify('ok@example.com')
    expect(await emailSignup(' OK@example.com ')).toEqual({ status: 'ok' })
    expect(await signup({ kind: 'kakao', consents }, 'mock')).toEqual({ status: 'ok' })
  })

  it('인증하지 않았거나 인증을 가입에 이미 썼으면 verification-expired 다', async () => {
    expect(await emailSignup('unverified@example.com')).toEqual({
      status: 'verification-expired',
    })
    await verify('twice@example.com')
    await emailSignup('twice@example.com')
    expect(await emailSignup('twice@example.com')).toEqual({ status: 'verification-expired' })
  })

  it('인증한 지 30분이 지나면 verification-expired 다', async () => {
    expect(EMAIL_VERIFICATION_TTL_SECONDS).toBe(1800)
    vi.useFakeTimers()
    await verify('slow@example.com')
    vi.setSystemTime(Date.now() + 1_801_000)
    expect(await emailSignup('slow@example.com')).toEqual({ status: 'verification-expired' })
  })

  it('재현용 이메일이면 인증했어도 verification-expired 다', async () => {
    await verify('verify-expired@example.com')
    expect(await emailSignup('verify-expired@example.com')).toEqual({
      status: 'verification-expired',
    })
  })

  it('재현용 이메일이면 가입이 거부된다', async () => {
    await expect(emailSignup('signup-fail@example.com')).rejects.toThrow()
  })

  it('재현용 가입된 이메일이면 인증했어도 email-taken 이고, 인증하지 않았으면 verification-expired 다', async () => {
    expect(await emailSignup('taken@example.com')).toEqual({ status: 'verification-expired' })
    await verify('taken@example.com')
    expect(await emailSignup('taken@example.com')).toEqual({ status: 'email-taken' })
    // 서버처럼 가입되지 않았으니 인증 표시는 남는다
    expect(await emailSignup('Taken@example.com')).toEqual({ status: 'email-taken' })
  })

  it('내 동네 저장 · 건강정보 동의는 성공한다', async () => {
    await expect(saveRegion(YEOKSAM1, 'mock')).resolves.toEqual({ status: 'ok' })
    await expect(agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))).resolves.toBeUndefined()
  })
})

describe('비밀번호 재설정 (목)', () => {
  const consents = [consentFor('TERMS_OF_SERVICE')]

  /** 코드를 받고 맞혀 재설정 토큰을 받는다 */
  async function tokenFor(email: string): Promise<string> {
    await sendPasswordResetCode(email)
    const result = await verifyPasswordResetCode(email, '482915')
    if (result.status !== 'ok') throw new Error(`토큰을 받지 못했다: ${result.status}`)
    return result.resetToken
  }

  afterEach(() => vi.useRealTimers())

  it('코드 받기는 가입과 같은 재현 입력을 쓴다 — 가입 여부를 드러내지 않는다', async () => {
    expect(await sendPasswordResetCode(' LIMIT@example.com ')).toEqual({ status: 'limit' })
    expect(await sendPasswordResetCode('never-joined@example.com')).toEqual({ status: 'sent' })
  })

  it('코드 확인은 가입과 같은 한도다 — 000000 은 4번부터 줄고 5번째에 잠기며 999999 는 잠긴다', async () => {
    await sendPasswordResetCode('reset-try@example.com')
    for (const remainingAttempts of [4, 3, 2, 1]) {
      expect(await verifyPasswordResetCode('reset-try@example.com', '000000')).toEqual({
        status: 'wrong',
        remainingAttempts,
      })
    }
    expect(await verifyPasswordResetCode('reset-try@example.com', '000000')).toEqual({
      status: 'locked',
    })
    expect(await verifyPasswordResetCode('reset-try@example.com', '482915')).toEqual({
      status: 'expired',
    })

    await sendPasswordResetCode('reset-lock@example.com')
    expect(await verifyPasswordResetCode('reset-lock@example.com', '999999')).toEqual({
      status: 'locked',
    })
  })

  it('맞히면 일회용 토큰을 주고, 코드는 한 번만 쓴다', async () => {
    const token = await tokenFor('reset-ok@example.com')
    expect(token).not.toBe('')
    expect(await verifyPasswordResetCode('reset-ok@example.com', '482915')).toEqual({
      status: 'expired',
    })
    // 토큰은 맞힐 때마다 새로 준다
    expect(await tokenFor('reset-ok@example.com')).not.toBe(token)
  })

  it('토큰으로 바꾸고 토큰을 소비한다 — 같은 토큰으로 두 번 바꾸지 못한다', async () => {
    const token = await tokenFor('reset-once@example.com')
    expect(await resetPassword(token, 'newpass2026')).toEqual({ status: 'ok' })
    expect(await resetPassword(token, 'newpass2027')).toEqual({ status: 'verification-expired' })
  })

  it('토큰 없이 이메일만으로는 바꾸지 못한다 — 코드를 맞힌 뒤라도 그렇다', async () => {
    await tokenFor('victim@example.com')
    expect(await resetPassword('victim@example.com', 'newpass2026')).toEqual({
      status: 'verification-expired',
    })
    expect(await resetPassword('', 'newpass2026')).toEqual({ status: 'verification-expired' })
  })

  it('가입 인증과 따로다 — 가입 코드 · 인증으로 재설정을, 재설정 인증으로 가입을 마치지 못한다', async () => {
    await sendEmailCode('both@example.com', 'mock')
    // 가입 코드만 보냈으면 재설정 코드는 없다
    expect(await verifyPasswordResetCode('both@example.com', '482915')).toEqual({
      status: 'expired',
    })

    await tokenFor('other@example.com')
    expect(
      await signup(
        {
          kind: 'email',
          email: 'other@example.com',
          password: 'dongne2026',
          nickname: '동네지기',
          consents,
        },
        'mock',
      ),
    ).toEqual({ status: 'verification-expired' })
  })

  it('토큰은 15분이 지나면 verification-expired 다', async () => {
    expect(PASSWORD_RESET_TOKEN_TTL_SECONDS).toBe(900)
    vi.useFakeTimers()
    const token = await tokenFor('reset-slow@example.com')
    vi.setSystemTime(Date.now() + 901_000)
    expect(await resetPassword(token, 'newpass2026')).toEqual({ status: 'verification-expired' })
  })

  it('재현용 이메일: verify-expired 로 받은 토큰은 늘 만료, reset-fail 로 받은 토큰은 거부(토큰은 남는다)', async () => {
    const expiredToken = await tokenFor('verify-expired@example.com')
    expect(await resetPassword(expiredToken, 'newpass2026')).toEqual({
      status: 'verification-expired',
    })

    const failToken = await tokenFor('reset-fail@example.com')
    await expect(resetPassword(failToken, 'newpass2026')).rejects.toThrow()
    await expect(resetPassword(failToken, 'newpass2026')).rejects.toThrow()
  })
})

describe('목 회원 상태', () => {
  const consents = [consentFor('TERMS_OF_SERVICE')]

  beforeEach(() => resetMockSession())

  it('처음에는 비회원(guest)이다', () => {
    expect(getMockSession()).toBe('guest')
  })

  it('이메일 가입 → 로그인 → 건강정보 동의로 미동의 회원을 거쳐 동의한 회원이 된다', async () => {
    await sendEmailCode('flow@example.com', 'mock')
    await verifyEmailCode('flow@example.com', '482915', 'mock')
    await signup(
      {
        kind: 'email',
        email: 'flow@example.com',
        password: 'dongne2026',
        nickname: '동네지기',
        consents,
      },
      'mock',
    )
    // 가입 응답에는 토큰이 없다. 이어지는 로그인이 회원으로 만든다
    expect(getMockSession()).toBe('guest')

    await loginWithEmail('flow@example.com', 'dongne2026', 'mock')
    expect(getMockSession()).toBe('member-no-consent')

    await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
    expect(getMockSession()).toBe('member')
  })

  it('카카오 가입이 되면 바로 미동의 회원이다 (카카오 로그인 시작만으로는 바뀌지 않는다)', async () => {
    await startKakaoLogin()
    expect(getMockSession()).toBe('guest')
    await signup({ kind: 'kakao', consents }, 'mock')
    expect(getMockSession()).toBe('member-no-consent')
  })

  it('로그인 · 가입이 실패하면 바뀌지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'wrong', 'mock')
    await loginWithEmail('locked@example.com', 'dongne2026', 'mock')
    await expect(
      signup(
        {
          kind: 'email',
          email: 'signup-fail@example.com',
          password: 'dongne2026',
          nickname: '동네지기',
          consents,
        },
        'mock',
      ),
    ).rejects.toThrow()
    expect(getMockSession()).toBe('guest')
  })

  it('바뀔 때만 구독자를 부르고, 구독을 끊으면 더 부르지 않는다', async () => {
    const listener = vi.fn()
    const unsubscribe = subscribeMockSession(listener)

    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    expect(listener).toHaveBeenCalledTimes(1)

    unsubscribe()
    await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
    expect(listener).toHaveBeenCalledTimes(1)
  })
})

describe('목 프로필 · 로그아웃 · 동의 철회 · 탈퇴', () => {
  const consents = [consentFor('TERMS_OF_SERVICE')]

  beforeEach(() => resetMockSession())

  it('이메일 로그인은 입력한 이메일, 가입에서 받은 닉네임으로 프로필을 채운다', async () => {
    await sendEmailCode('new@example.com', 'mock')
    await verifyEmailCode('new@example.com', '482915', 'mock')
    await signup(
      {
        kind: 'email',
        email: 'new@example.com',
        password: 'dongne2026',
        nickname: '골목대장',
        consents,
      },
      'mock',
    )
    await loginWithEmail(' New@Example.com ', 'dongne2026', 'mock')
    expect(getMockProfile()).toEqual({
      provider: 'email',
      email: 'new@example.com',
      nickname: '골목대장',
      hasPassword: true,
      region: null,
      regionAbolished: false,
      termsReconsentRequired: false,
    })
  })

  it('가입 없이 로그인하면 닉네임은 예시 값이다', async () => {
    await loginWithEmail('dong2@example.com', 'dongne2026', 'mock')
    expect(getMockProfile()).toEqual({ ...EXAMPLE_PROFILES.email, email: 'dong2@example.com' })
  })

  it('카카오 가입은 카카오 예시 프로필이고, 건강정보 동의는 프로필을 그대로 둔다', async () => {
    await signup({ kind: 'kakao', consents }, 'mock')
    await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
    expect(getMockProfile()).toEqual(EXAMPLE_PROFILES.kakao)
  })

  it('로그아웃 · 탈퇴하면 비회원이 되고 프로필을 지운다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await logout('mock')
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()

    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await withdrawMembership()
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
  })

  it('동의 철회하면 로그아웃과 같이 비회원이 되고 프로필도 지운다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
    await withdrawHealthConsent()
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
  })

  it('비회원 세션에서 동의 철회해도 비회원 그대로다 (덮어쓰기로 연 경우)', async () => {
    await withdrawHealthConsent()
    expect(getMockSession()).toBe('guest')
  })

  it.each([
    ['logout-fail@example.com', logout],
    ['consent-withdraw-fail@example.com', withdrawHealthConsent],
    ['withdraw-fail@example.com', withdrawMembership],
  ] as const)('프로필 이메일이 %s 면 거부하고 세션을 그대로 둔다', async (email, action) => {
    await loginWithEmail(email, 'dongne2026', 'mock')
    await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
    await expect(action('mock')).rejects.toThrow()
    expect(getMockSession()).toBe('member')
    expect(getMockProfile()?.email).toBe(email)
  })
})

describe('로그인한 기기 (목)', () => {
  beforeEach(() => resetMockSession())

  it('시안의 예시 기기(이 기기 + 다른 기기 둘)를 준다 — 기기 이름 · 마지막 사용 시각만, IP · 지역은 없다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    const sessions = await listSessions()
    expect(sessions.map((session) => [session.deviceName, session.current])).toEqual([
      ['iPhone · Safari', true],
      ['Mac · Chrome', false],
      ['Galaxy · 삼성 인터넷', false],
    ])
    expect(Object.keys(sessions[0] ?? {}).sort()).toEqual([
      'current',
      'deviceName',
      'id',
      'lastActiveAt',
    ])
  })

  it('받은 목록을 고쳐도 목 서버 목록은 그대로다', async () => {
    const sessions = await listSessions()
    sessions.pop()
    expect(await listSessions()).toHaveLength(3)
  })

  it('한 기기를 로그아웃하면 목록에서 빠지고, 이미 없는 세션은 끝난 것으로 본다', async () => {
    await revokeSession('mock-session-mac')
    expect((await listSessions()).map((session) => session.id)).toEqual([
      'mock-session-this',
      'mock-session-galaxy',
    ])
    await expect(revokeSession('mock-session-mac')).resolves.toBeUndefined()
  })

  it('이 기기의 세션은 여기서 로그아웃하지 않는다 (거부)', async () => {
    await expect(revokeSession('mock-session-this')).rejects.toThrow()
    expect(await listSessions()).toHaveLength(3)
  })

  it('다른 기기 모두 로그아웃하면 이 기기만 남는다', async () => {
    await revokeOtherSessions()
    expect((await listSessions()).map((session) => session.id)).toEqual(['mock-session-this'])
  })

  it('비회원이 되면(로그아웃) 목록을 지워 다음 로그인은 예시 목록부터다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await revokeOtherSessions()
    await logout('mock')
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    expect(await listSessions()).toHaveLength(3)
  })

  it('재현 이메일이면 목록 · 로그아웃을 거부하고 목록을 그대로 둔다', async () => {
    await loginWithEmail('sessions-fail@example.com', 'dongne2026', 'mock')
    await expect(listSessions()).rejects.toThrow()

    await loginWithEmail('session-revoke-fail@example.com', 'dongne2026', 'mock')
    await expect(revokeSession('mock-session-mac')).rejects.toThrow()
    await expect(revokeOtherSessions()).rejects.toThrow()
    expect(await listSessions()).toHaveLength(3)
  })
})

describe('비밀번호 변경 · 설정 (목)', () => {
  const consents = [consentFor('TERMS_OF_SERVICE')]

  beforeEach(() => resetMockSession())

  it('예시 프로필: 이메일 회원은 비밀번호가 있고 카카오 회원은 없다', () => {
    expect(EXAMPLE_PROFILES.email.hasPassword).toBe(true)
    expect(EXAMPLE_PROFILES.kakao.hasPassword).toBe(false)
  })

  it('변경: 현재 비밀번호 wrong 이면 wrong-current, 그 밖에는 성공이다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    expect(await changePassword('wrong', 'newpass2026')).toEqual({ status: 'wrong-current' })
    expect(await changePassword('dongne2026', 'newpass2026')).toEqual({ status: 'ok' })
    // 새 비밀번호가 현재와 같아도 막지 않는다(계약에 없음)
    expect(await changePassword('dongne2026', 'dongne2026')).toEqual({ status: 'ok' })
  })

  it('설정에 성공하면 카카오 회원 프로필의 hasPassword 가 true 가 된다', async () => {
    await signup({ kind: 'kakao', consents }, 'mock')
    const listener = vi.fn()
    const unsubscribe = subscribeMockSession(listener)
    await setupPassword('newpass2026')
    unsubscribe()
    expect(getMockProfile()).toEqual({ ...EXAMPLE_PROFILES.kakao, hasPassword: true })
    expect(getMockSession()).toBe('member-no-consent')
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('비회원 세션(덮어쓰기로 연 경우)에서 설정해도 세션을 바꾸지 않는다', async () => {
    await setupPassword('newpass2026')
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
  })

  it('재현 이메일 · 재현 새 비밀번호면 거부하고 프로필을 그대로 둔다', async () => {
    await loginWithEmail('password-fail@example.com', 'dongne2026', 'mock')
    await expect(changePassword('dongne2026', 'newpass2026')).rejects.toThrow()
    await expect(changePassword('wrong', 'newpass2026')).rejects.toThrow()

    await signup({ kind: 'kakao', consents }, 'mock')
    await expect(setupPassword('fail2026')).rejects.toThrow()
    expect(getMockProfile()?.hasPassword).toBe(false)
  })
})

describe('legal', () => {
  it('항목 이름은 백엔드 ConsentType 과 같고 성인 확인은 이용약관 버전을 쓴다', () => {
    expect(Object.keys(LEGAL_VERSIONS).sort()).toEqual([
      'AGE_OVER_19',
      'PRIVACY_POLICY',
      'SENSITIVE_HEALTH_INFO',
      'TERMS_OF_SERVICE',
    ])
    expect(consentFor('AGE_OVER_19').documentVersion).toBe(LEGAL_VERSIONS.TERMS_OF_SERVICE)
  })
})

describe('약관 재동의 · 동네 다시 저장 (목)', () => {
  beforeEach(() => resetMockSession())

  it('재현 이메일로 로그인하면 조건이 켜진 프로필이 된다 (옛 동네는 시안 예시)', async () => {
    await loginWithEmail('Reconsent@example.com', 'dongne2026', 'mock')
    expect(getMockProfile()).toMatchObject({
      termsReconsentRequired: true,
      regionAbolished: false,
      region: null,
    })

    await loginWithEmail('reselect@example.com', 'dongne2026', 'mock')
    expect(getMockProfile()).toMatchObject({
      termsReconsentRequired: false,
      regionAbolished: true,
      region: { code: '99990110', name: '○○1동' },
    })
  })

  it('그 밖의 이메일은 조건이 없다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    expect(getMockProfile()).toMatchObject({
      termsReconsentRequired: false,
      regionAbolished: false,
    })
  })

  it('재동의에 성공하면 재동의 표시를 끄고 다른 값은 그대로 둔다', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026', 'mock')
    const before = getMockProfile()
    await expect(agreeTermsReconsent(consentFor('TERMS_OF_SERVICE'))).resolves.toBeUndefined()
    expect(getMockProfile()).toEqual({ ...before, termsReconsentRequired: false })
    expect(getMockSession()).toBe('member-no-consent')
  })

  it('재현 이메일이면 재동의가 거부되고 표시는 그대로다', async () => {
    await loginWithEmail('reconsent-fail@example.com', 'dongne2026', 'mock')
    await expect(agreeTermsReconsent(consentFor('TERMS_OF_SERVICE'))).rejects.toThrow()
    expect(getMockProfile()?.termsReconsentRequired).toBe(true)
  })

  it('동네를 저장하면 프로필의 동네를 바꾸고 폐지 표시를 끈다', async () => {
    await loginWithEmail('reselect@example.com', 'dongne2026', 'mock')
    await saveRegion({ code: '99990111', name: '○○새1동' }, 'mock')
    expect(getMockProfile()).toMatchObject({
      region: { code: '99990111', name: '○○새1동' },
      regionAbolished: false,
    })
  })

  it('재현 이메일이면 동네 저장이 거부되고 옛 동네 · 폐지 표시는 그대로다', async () => {
    await loginWithEmail('reselect-fail@example.com', 'dongne2026', 'mock')
    await expect(saveRegion(YEOKSAM1, 'mock')).rejects.toThrow()
    expect(getMockProfile()).toMatchObject({
      region: { code: '99990110', name: '○○1동' },
      regionAbolished: true,
    })
  })

  it('프로필이 없으면(덮어쓰기만 있음) 세션을 바꾸지 않는다', async () => {
    await saveRegion(YEOKSAM1, 'mock')
    await agreeTermsReconsent(consentFor('TERMS_OF_SERVICE'))
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
  })
})

/* ── 실데이터 (#163) ─────────────────────────────────────────────────────────────── */

const TOKEN: AuthToken = {
  memberId: '1843956734582784',
  role: 'USER',
  accessToken: 'access-token',
  accessTokenExpiresIn: 900,
  pendingConsents: [],
  reportWritable: false,
}

describe('sendEmailCode (API)', () => {
  it('인증 없이 이메일을 본문으로 보내고 sent 다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    expect(await sendEmailCode('dong@example.com', 'api')).toEqual({ status: 'sent' })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/email/send-code', {
      method: 'POST',
      body: { email: 'dong@example.com' },
      auth: false,
    })
  })

  it.each(['AUTH_001', 'AUTH_002'])('%s(429) 면 limit 이다', async (code) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, 429))
    expect(await sendEmailCode('dong@example.com', 'api')).toEqual({ status: 'limit' })
  })

  it('일시 장애 · 분류 밖 오류는 거부한다', async () => {
    const unavailable = unavailableError('network', 0)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailable)
    await expect(sendEmailCode('dong@example.com', 'api')).rejects.toBe(unavailable)
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_006', 503))
    await expect(sendEmailCode('dong@example.com', 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_103', 400))
    await expect(sendEmailCode('dong@example.com', 'api')).rejects.toThrow(ApiError)
  })

  it('목 저장소를 쓰지 않는다 — 실데이터로 보낸 코드는 목 확인에서 만료다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await sendEmailCode('only-api@example.com', 'api')
    expect(await verifyEmailCode('only-api@example.com', '482915', 'mock')).toEqual({
      status: 'expired',
    })
  })
})

describe('verifyEmailCode (API)', () => {
  const wrongCode = () => apiError('AUTH_003', 400)

  it('인증 없이 이메일 · 코드를 본문으로 보내고 ok 다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    expect(await verifyEmailCode('dong@example.com', '482915', 'api')).toEqual({ status: 'ok' })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/email/verify-code', {
      method: 'POST',
      body: { email: 'dong@example.com', code: '482915' },
      auth: false,
    })
  })

  it('AUTH_003 이면 wrong 이고 이메일별로 센 실패 수로 남은 시도를 채운다(1 아래로 내려가지 않는다)', async () => {
    const remaining: number[] = []
    for (let i = 0; i < CODE_MAX_ATTEMPTS + 1; i += 1) {
      vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
      const result = await verifyEmailCode(' Count@example.com ', '000000', 'api')
      if (result.status !== 'wrong') throw new Error(`wrong 이 아니다: ${result.status}`)
      remaining.push(result.remainingAttempts)
    }
    expect(remaining).toEqual([4, 3, 2, 1, 1, 1])
    // 다른 이메일은 따로 센다
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    expect(await verifyEmailCode('other-count@example.com', '000000', 'api')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })
  })

  it('코드를 다시 받으면 · 인증을 마치면 센 실패 수를 0 으로 되돌린다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    await verifyEmailCode('resend@example.com', '000000', 'api')
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await sendEmailCode('resend@example.com', 'api')
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    expect(await verifyEmailCode('resend@example.com', '000000', 'api')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })

    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await verifyEmailCode('resend@example.com', '482915', 'api')
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    expect(await verifyEmailCode('resend@example.com', '000000', 'api')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })
  })

  it.each([
    ['AUTH_004', 400, 'expired'],
    ['AUTH_005', 400, 'locked'],
  ] as const)('%s 면 %s 이고 센 실패 수를 0 으로 되돌린다', async (code, status, expected) => {
    const email = `reset-${code}@example.com`
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    await verifyEmailCode(email, '000000', 'api')
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    expect(await verifyEmailCode(email, '000000', 'api')).toEqual({ status: expected })
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    expect(await verifyEmailCode(email, '000000', 'api')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })
  })

  it('AUTH_010(IP 상한) 이면 locked 이고 센 실패 수는 그대로 둔다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    await verifyEmailCode('ip@example.com', '000000', 'api')
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_010', 429))
    expect(await verifyEmailCode('ip@example.com', '000000', 'api')).toEqual({ status: 'locked' })
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    expect(await verifyEmailCode('ip@example.com', '000000', 'api')).toEqual({
      status: 'wrong',
      remainingAttempts: 3,
    })
  })

  it('일시 장애 · 분류 밖 오류는 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_006', 503))
    await expect(verifyEmailCode('dong@example.com', '482915', 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('timeout', 0))
    await expect(verifyEmailCode('dong@example.com', '482915', 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(new TypeError('programming error'))
    await expect(verifyEmailCode('dong@example.com', '482915', 'api')).rejects.toThrow(TypeError)
  })
})

describe('signup (API)', () => {
  const request: SignupRequest = {
    kind: 'email',
    email: 'dong@example.com',
    password: 'dongne2026',
    nickname: '동네지기',
    consents: [
      consentFor('TERMS_OF_SERVICE'),
      consentFor('PRIVACY_POLICY'),
      consentFor('AGE_OVER_19'),
    ],
  }

  beforeEach(() => resetMockSession())

  it('인증 없이 계정 · 동의 값을 본문으로 보내고 ok 다(토큰이 없어 세션은 그대로)', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    expect(await signup(request, 'api')).toEqual({ status: 'ok' })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/signup', {
      method: 'POST',
      body: {
        email: 'dong@example.com',
        password: 'dongne2026',
        nickname: '동네지기',
        termsAgreed: true,
        privacyAgreed: true,
        ageOver19Confirmed: true,
        sensitiveHealthInfoAgreed: false,
      },
      auth: false,
    })
    expect(setSession).not.toHaveBeenCalled()
    expect(getMockSession()).toBe('guest')
  })

  it('동의 목록에 없는 항목은 false, 건강정보 동의가 있으면 true 로 보낸다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await signup(
      { ...request, consents: [consentFor('PRIVACY_POLICY'), consentFor('SENSITIVE_HEALTH_INFO')] },
      'api',
    )
    expect(vi.mocked(apiRequest).mock.calls[0]?.[1]?.body).toMatchObject({
      termsAgreed: false,
      privacyAgreed: true,
      ageOver19Confirmed: false,
      sensitiveHealthInfoAgreed: true,
    })
  })

  it.each([
    ['AUTH_007', 400, 'verification-expired'],
    ['MEMBER_001', 409, 'email-taken'],
  ] as const)('%s 면 %s 다', async (code, status, expected) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    expect(await signup(request, 'api')).toEqual({ status: expected })
  })

  it('검증 오류 · 일시 장애는 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_107', 400))
    await expect(signup(request, 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('no-envelope', 502))
    await expect(signup(request, 'api')).rejects.toThrow(ApiError)
  })

  it('카카오 가입은 출처와 무관하게 아직 목이다(#167)', async () => {
    expect(await signup({ kind: 'kakao', consents: [] }, 'api')).toEqual({ status: 'ok' })
    expect(apiRequest).not.toHaveBeenCalled()
  })
})

describe('loginWithEmail (API)', () => {
  beforeEach(() => resetMockSession())

  it('인증 없이 이메일 · 비밀번호를 본문으로 보내고, 응답 토큰을 그대로 세션 저장소에 넣는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(TOKEN)
    expect(await loginWithEmail('dong@example.com', 'dongne2026', 'api')).toEqual({
      status: 'ok',
    })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/login', {
      method: 'POST',
      body: { email: 'dong@example.com', password: 'dongne2026' },
      auth: false,
    })
    expect(setSession).toHaveBeenCalledWith(TOKEN)
    // 목 세션은 건드리지 않는다
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
  })

  it.each([
    ['AUTH_011', 401, 'wrong'],
    ['MEMBER_002', 403, 'wrong'],
    ['AUTH_102', 400, 'wrong'],
    ['AUTH_103', 400, 'wrong'],
    ['AUTH_113', 400, 'wrong'],
    ['AUTH_012', 429, 'locked'],
    ['AUTH_013', 429, 'limited'],
    ['MEMBER_003', 403, 'suspended'],
  ] as const)('%s(%i) 면 %s 이고 세션을 만들지 않는다', async (code, status, expected) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    expect(await loginWithEmail('dong@example.com', 'dongne2026', 'api')).toEqual({
      status: expected,
    })
    expect(setSession).not.toHaveBeenCalled()
  })

  it('세션 저장소 장애 · 일시 장애 · 분류 밖 오류는 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_017', 503))
    await expect(loginWithEmail('dong@example.com', 'dongne2026', 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(loginWithEmail('dong@example.com', 'dongne2026', 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_100', 400))
    await expect(loginWithEmail('dong@example.com', 'dongne2026', 'api')).rejects.toThrow(ApiError)
    expect(setSession).not.toHaveBeenCalled()
  })
})

describe('logout (API)', () => {
  beforeEach(() => resetMockSession())

  it('access 를 실어(auth 기본값) 부르고, 성공하면 세션 저장소를 비운다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await expect(logout('api')).resolves.toBeUndefined()
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/logout', { method: 'POST' })
    expect(clearSession).toHaveBeenCalledWith('logout')
  })

  it.each([
    ['SECURITY_001', 401],
    ['SECURITY_002', 401],
    ['SECURITY_007', 401],
    ['AUTH_014', 401],
    ['AUTH_015', 401],
  ])('토큰이 이미 무효(%s)면 성공처럼 세션을 비운다', async (code, status) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    await expect(logout('api')).resolves.toBeUndefined()
    expect(clearSession).toHaveBeenCalledWith('logout')
  })

  it('세션 저장소가 이미 비었으면(재발급이 재로그인으로 끝남) 다시 비우지 않고 만료 진행 표시만 끈다', async () => {
    vi.mocked(getSessionSnapshot).mockReturnValue({ status: 'guest' })
    notifySessionExpired()
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('SECURITY_001', 401))
    await expect(logout('api')).resolves.toBeUndefined()
    expect(clearSession).not.toHaveBeenCalled()
    expect(isSessionExpiring()).toBe(false)
  })

  it('성공해도 만료 진행 표시를 끈다', async () => {
    notifySessionExpired()
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await logout('api')
    expect(isSessionExpiring()).toBe(false)
  })

  it('거부하면 만료 진행 표시를 건드리지 않는다', async () => {
    notifySessionExpired()
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(logout('api')).rejects.toThrow(ApiError)
    expect(isSessionExpiring()).toBe(true)
    // 만료 진행 표시는 모듈 메모리라 다음 테스트에 남기지 않는다
    clearSessionExpiring()
  })

  it('일시 장애 · 분류 밖 오류면 거부하고 세션을 그대로 둔다', async () => {
    const unavailable = unavailableError('timeout', 0)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailable)
    await expect(logout('api')).rejects.toBe(unavailable)
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('SECURITY_008', 503))
    await expect(logout('api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_100', 400))
    await expect(logout('api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(new TypeError('programming error'))
    await expect(logout('api')).rejects.toThrow(TypeError)
    expect(clearSession).not.toHaveBeenCalled()
  })

  it('목 세션은 건드리지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await logout('api')
    expect(getMockSession()).toBe('member-no-consent')
  })

  it('목데이터면 API · 세션 저장소를 부르지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await logout('mock')
    expect(apiRequest).not.toHaveBeenCalled()
    expect(clearSession).not.toHaveBeenCalled()
    expect(getMockSession()).toBe('guest')
  })
})

describe('saveRegion (API)', () => {
  const SAVED = {
    code: '11680640',
    name: '역삼1동',
    sigungu: '서울특별시 강남구',
    abolished: false,
  }

  it('코드만 PUT 으로 보내고, 응답을 보낸 회원의 내 동네로 넣는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(SAVED)
    expect(await saveRegion({ code: '11680640', name: '역삼1동' }, 'api')).toEqual({ status: 'ok' })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/members/me/region', {
      method: 'PUT',
      body: { code: '11680640' },
    })
    expect(setMemberRegion).toHaveBeenCalledWith('1', SAVED)
  })

  it.each([
    ['REGION_001', 400],
    ['REGION_002', 400],
    ['REGION_101', 400],
    ['REGION_102', 400],
  ])('%s 면 invalid 이고 내 동네를 바꾸지 않는다', async (code, status) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    expect(await saveRegion(YEOKSAM1, 'api')).toEqual({ status: 'invalid' })
    expect(apiRequest).toHaveBeenCalledTimes(1)
    expect(setMemberRegion).not.toHaveBeenCalled()
  })

  it('동시 첫 저장 경합(REGION_003)이면 한 번만 다시 보낸다', async () => {
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('REGION_003', 409))
      .mockResolvedValueOnce(SAVED)
    expect(await saveRegion(YEOKSAM1, 'api')).toEqual({ status: 'ok' })
    expect(apiRequest).toHaveBeenCalledTimes(2)
    expect(setMemberRegion).toHaveBeenCalledWith('1', SAVED)

    vi.mocked(apiRequest).mockReset()
    const conflict = apiError('REGION_003', 409)
    vi.mocked(apiRequest).mockRejectedValueOnce(conflict).mockRejectedValueOnce(conflict)
    await expect(saveRegion(YEOKSAM1, 'api')).rejects.toBe(conflict)
    expect(apiRequest).toHaveBeenCalledTimes(2)
  })

  it('행정동 확인 장애(REGION_004) · 일시 장애면 거부하고 내 동네를 바꾸지 않는다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('REGION_004', 503))
    await expect(saveRegion(YEOKSAM1, 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('timeout', 0))
    await expect(saveRegion(YEOKSAM1, 'api')).rejects.toThrow(ApiError)
    expect(apiRequest).toHaveBeenCalledTimes(2)
    expect(setMemberRegion).not.toHaveBeenCalled()
  })

  it.each([{ status: 'guest' }, { status: 'restoring' }, { status: 'idle' }] as const)(
    '실데이터 세션이 회원이 아니면($status) 요청 없이 거부한다 — 목 세션만 있는 카카오 가입 포함, 목 프로필은 그대로다',
    async (session) => {
      await loginWithEmail('reselect@example.com', 'dongne2026', 'mock')
      vi.mocked(getSessionSnapshot).mockReturnValue(session)
      await expect(saveRegion(YEOKSAM1, 'api')).rejects.toThrow()
      expect(apiRequest).not.toHaveBeenCalled()
      expect(setMemberRegion).not.toHaveBeenCalled()
      expect(getMockProfile()).toMatchObject({ regionAbolished: true })
    },
  )

  it('목데이터면 API 를 부르지 않는다', async () => {
    await saveRegion(YEOKSAM1, 'mock')
    expect(apiRequest).not.toHaveBeenCalled()
    expect(setMemberRegion).not.toHaveBeenCalled()
  })
})
