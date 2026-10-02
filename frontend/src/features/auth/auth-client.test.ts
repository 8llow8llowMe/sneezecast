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
  signup,
  type SignupRequest,
  subscribeMockSession,
  verifyEmailCode,
  verifyPasswordResetCode,
  withdrawHealthConsent,
  withdrawMembership,
} from './auth-client'
import { consentFor, LEGAL_VERSIONS } from './legal'
import { getMemberInfoSnapshot, reloadMemberInfo, setMemberRegion } from './member-info'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))
vi.mock('@/lib/session/session-store', () => ({
  setSession: vi.fn(),
  clearSession: vi.fn(),
  getSessionSnapshot: vi.fn(),
}))
vi.mock('./member-info', () => ({
  setMemberRegion: vi.fn(),
  reloadMemberInfo: vi.fn(),
  getMemberInfoSnapshot: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(apiRequest).mockReset()
  vi.mocked(setSession).mockReset()
  vi.mocked(clearSession).mockReset()
  vi.mocked(getSessionSnapshot).mockReset()
  vi.mocked(setMemberRegion).mockReset()
  vi.mocked(reloadMemberInfo).mockReset()
  vi.mocked(getMemberInfoSnapshot).mockReset()
  vi.mocked(getMemberInfoSnapshot).mockReturnValue(null)
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
    await sendPasswordResetCode(email, 'mock')
    const result = await verifyPasswordResetCode(email, '482915', 'mock')
    if (result.status !== 'ok') throw new Error(`토큰을 받지 못했다: ${result.status}`)
    return result.resetToken
  }

  afterEach(() => vi.useRealTimers())

  it('코드 받기는 가입과 같은 재현 입력을 쓴다 — 가입 여부를 드러내지 않는다', async () => {
    expect(await sendPasswordResetCode(' LIMIT@example.com ', 'mock')).toEqual({ status: 'limit' })
    expect(await sendPasswordResetCode('never-joined@example.com', 'mock')).toEqual({
      status: 'sent',
    })
  })

  it('코드 확인은 가입과 같은 한도다 — 000000 은 4번부터 줄고 5번째에 잠기며 999999 는 잠긴다', async () => {
    await sendPasswordResetCode('reset-try@example.com', 'mock')
    for (const remainingAttempts of [4, 3, 2, 1]) {
      expect(await verifyPasswordResetCode('reset-try@example.com', '000000', 'mock')).toEqual({
        status: 'wrong',
        remainingAttempts,
      })
    }
    expect(await verifyPasswordResetCode('reset-try@example.com', '000000', 'mock')).toEqual({
      status: 'locked',
    })
    expect(await verifyPasswordResetCode('reset-try@example.com', '482915', 'mock')).toEqual({
      status: 'expired',
    })

    await sendPasswordResetCode('reset-lock@example.com', 'mock')
    expect(await verifyPasswordResetCode('reset-lock@example.com', '999999', 'mock')).toEqual({
      status: 'locked',
    })
  })

  it('맞히면 일회용 토큰을 주고, 코드는 한 번만 쓴다', async () => {
    const token = await tokenFor('reset-ok@example.com')
    expect(token).not.toBe('')
    expect(await verifyPasswordResetCode('reset-ok@example.com', '482915', 'mock')).toEqual({
      status: 'expired',
    })
    // 토큰은 맞힐 때마다 새로 준다
    expect(await tokenFor('reset-ok@example.com')).not.toBe(token)
  })

  it('토큰으로 바꾸고 토큰을 소비한다 — 같은 토큰으로 두 번 바꾸지 못한다', async () => {
    const token = await tokenFor('reset-once@example.com')
    expect(await resetPassword(token, 'newpass2026', 'dong@example.com', 'mock')).toEqual({
      status: 'ok',
    })
    expect(await resetPassword(token, 'newpass2027', 'dong@example.com', 'mock')).toEqual({
      status: 'verification-expired',
    })
  })

  it('바꾸면 서버처럼 모든 기기가 로그아웃된다 — 목 세션이 비회원이 되고 세션 저장소는 건드리지 않는다', async () => {
    resetMockSession()
    await loginWithEmail('reset-member@example.com', 'dongne2026', 'mock')
    const token = await tokenFor('reset-member@example.com')
    expect(await resetPassword(token, 'newpass2026', 'dong@example.com', 'mock')).toEqual({
      status: 'ok',
    })
    expect(getMockSession()).toBe('guest')
    expect(clearSession).not.toHaveBeenCalled()
    expect(apiRequest).not.toHaveBeenCalled()
  })

  it('다른 계정의 비밀번호를 바꾸면 목 세션은 그대로다', async () => {
    resetMockSession()
    await loginWithEmail('me@example.com', 'dongne2026', 'mock')
    const token = await tokenFor('someone-else@example.com')
    expect(await resetPassword(token, 'newpass2026', 'someone-else@example.com', 'mock')).toEqual({
      status: 'ok',
    })
    expect(getMockSession()).toBe('member-no-consent')
  })

  it('토큰 없이 이메일만으로는 바꾸지 못한다 — 코드를 맞힌 뒤라도 그렇다', async () => {
    await tokenFor('victim@example.com')
    expect(
      await resetPassword('victim@example.com', 'newpass2026', 'dong@example.com', 'mock'),
    ).toEqual({
      status: 'verification-expired',
    })
    expect(await resetPassword('', 'newpass2026', 'dong@example.com', 'mock')).toEqual({
      status: 'verification-expired',
    })
  })

  it('가입 인증과 따로다 — 가입 코드 · 인증으로 재설정을, 재설정 인증으로 가입을 마치지 못한다', async () => {
    await sendEmailCode('both@example.com', 'mock')
    // 가입 코드만 보냈으면 재설정 코드는 없다
    expect(await verifyPasswordResetCode('both@example.com', '482915', 'mock')).toEqual({
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
    expect(await resetPassword(token, 'newpass2026', 'dong@example.com', 'mock')).toEqual({
      status: 'verification-expired',
    })
  })

  it('재현용 이메일: verify-expired 로 받은 토큰은 늘 만료, reset-fail 로 받은 토큰은 거부(토큰은 남는다)', async () => {
    const expiredToken = await tokenFor('verify-expired@example.com')
    expect(await resetPassword(expiredToken, 'newpass2026', 'dong@example.com', 'mock')).toEqual({
      status: 'verification-expired',
    })

    const failToken = await tokenFor('reset-fail@example.com')
    await expect(
      resetPassword(failToken, 'newpass2026', 'dong@example.com', 'mock'),
    ).rejects.toThrow()
    await expect(
      resetPassword(failToken, 'newpass2026', 'dong@example.com', 'mock'),
    ).rejects.toThrow()
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

  it('카카오 가입이 되면 바로 미동의 회원이다', async () => {
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
    const sessions = await listSessions('mock')
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
    const sessions = await listSessions('mock')
    sessions.pop()
    expect(await listSessions('mock')).toHaveLength(3)
  })

  it('한 기기를 로그아웃하면 목록에서 빠지고, 이미 없는 세션은 끝난 것으로 본다', async () => {
    await revokeSession({ id: 'mock-session-mac', current: false }, 'mock')
    expect((await listSessions('mock')).map((session) => session.id)).toEqual([
      'mock-session-this',
      'mock-session-galaxy',
    ])
    await expect(
      revokeSession({ id: 'mock-session-mac', current: false }, 'mock'),
    ).resolves.toBeUndefined()
  })

  it('이 기기의 세션은 여기서 로그아웃하지 않는다 (거부)', async () => {
    await expect(
      revokeSession({ id: 'mock-session-this', current: true }, 'mock'),
    ).rejects.toThrow()
    expect(await listSessions('mock')).toHaveLength(3)
  })

  it('다른 기기 모두 로그아웃하면 이 기기만 남는다', async () => {
    await revokeOtherSessions('mock')
    expect((await listSessions('mock')).map((session) => session.id)).toEqual(['mock-session-this'])
  })

  it('비회원이 되면(로그아웃) 목록을 지워 다음 로그인은 예시 목록부터다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await revokeOtherSessions('mock')
    await logout('mock')
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    expect(await listSessions('mock')).toHaveLength(3)
  })

  it('재현 이메일이면 목록 · 로그아웃을 거부하고 목록을 그대로 둔다', async () => {
    await loginWithEmail('sessions-fail@example.com', 'dongne2026', 'mock')
    await expect(listSessions('mock')).rejects.toThrow()

    await loginWithEmail('session-revoke-fail@example.com', 'dongne2026', 'mock')
    await expect(
      revokeSession({ id: 'mock-session-mac', current: false }, 'mock'),
    ).rejects.toThrow()
    await expect(revokeOtherSessions('mock')).rejects.toThrow()
    expect(await listSessions('mock')).toHaveLength(3)
  })
})

describe('비밀번호 변경 (목)', () => {
  beforeEach(() => resetMockSession())

  it('예시 프로필: 이메일 회원은 비밀번호가 있고 카카오 회원은 없다(설정 API 는 없다 — #61)', () => {
    expect(EXAMPLE_PROFILES.email.hasPassword).toBe(true)
    expect(EXAMPLE_PROFILES.kakao.hasPassword).toBe(false)
  })

  it('변경: 현재 비밀번호 wrong 이면 wrong-current, 그 밖에는 성공이다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    expect(await changePassword('wrong', 'newpass2026', 'mock')).toEqual({
      status: 'wrong-current',
    })
    expect(await changePassword('dongne2026', 'newpass2026', 'mock')).toEqual({ status: 'ok' })
    // 새 비밀번호가 현재와 같아도 막지 않는다(계약에 없음)
    expect(await changePassword('dongne2026', 'dongne2026', 'mock')).toEqual({ status: 'ok' })
  })

  it('바꾸면 서버처럼 다른 기기를 로그아웃하고 이 기기는 남긴다 — 목 세션은 그대로다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    expect(await changePassword('dongne2026', 'newpass2026', 'mock')).toEqual({ status: 'ok' })
    expect((await listSessions('mock')).map((session) => session.id)).toEqual(['mock-session-this'])
    expect(getMockSession()).toBe('member-no-consent')
  })

  it('재현 이메일 · 재현 새 비밀번호면 거부하고 기기 목록을 그대로 둔다', async () => {
    await loginWithEmail('password-fail@example.com', 'dongne2026', 'mock')
    await expect(changePassword('dongne2026', 'newpass2026', 'mock')).rejects.toThrow()
    await expect(changePassword('wrong', 'newpass2026', 'mock')).rejects.toThrow()

    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await expect(changePassword('dongne2026', 'fail2026', 'mock')).rejects.toThrow()
    expect(await listSessions('mock')).toHaveLength(3)
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
})

describe('signup 카카오 (API, #167)', () => {
  const consents = [
    consentFor('TERMS_OF_SERVICE'),
    consentFor('PRIVACY_POLICY'),
    consentFor('AGE_OVER_19'),
  ]

  beforeEach(() => resetMockSession())

  it('인증 없이 동의 값만 보내고(이메일 · 닉네임은 가입표), 응답 토큰을 그대로 세션 저장소에 넣는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(TOKEN)
    expect(await signup({ kind: 'kakao', consents }, 'api')).toEqual({ status: 'ok' })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/kakao/signup', {
      method: 'POST',
      body: { termsAgreed: true, privacyAgreed: true, ageOver19Confirmed: true },
      auth: false,
    })
    expect(setSession).toHaveBeenCalledWith(TOKEN)
    // 목 세션은 건드리지 않는다
    expect(getMockSession()).toBe('guest')
  })

  it('동의 목록에 없는 항목은 false 로 보낸다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(TOKEN)
    await signup({ kind: 'kakao', consents: [consentFor('TERMS_OF_SERVICE')] }, 'api')
    expect(vi.mocked(apiRequest).mock.calls[0]?.[1]?.body).toEqual({
      termsAgreed: true,
      privacyAgreed: false,
      ageOver19Confirmed: false,
    })
  })

  it.each([
    ['AUTH_025', 400, 'expired'],
    ['MEMBER_001', 409, null],
  ] as const)(
    '%s 면 카카오 로그인부터 다시(사유 %s)이고 세션을 만들지 않는다',
    async (code, status, reason) => {
      vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
      expect(await signup({ kind: 'kakao', consents }, 'api')).toEqual({
        status: 'kakao-restart',
        reason,
      })
      expect(setSession).not.toHaveBeenCalled()
    },
  )

  it.each([
    ['AUTH_017', 503],
    ['AUTH_006', 503],
  ] as const)(
    '서비스가 그 밖의 업무 오류(%s)로 답하면 가입표를 이미 잃었으므로 사유 없이 다시 시작이다',
    async (code, status) => {
      vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
      expect(await signup({ kind: 'kakao', consents }, 'api')).toEqual({
        status: 'kakao-restart',
        reason: null,
      })
      expect(setSession).not.toHaveBeenCalled()
    },
  )

  it('필수 동의 검증 오류(가입표를 건드리지 않음) · 응답을 받지 못한 실패는 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_111', 400))
    await expect(signup({ kind: 'kakao', consents }, 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(signup({ kind: 'kakao', consents }, 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('timeout', 0))
    await expect(signup({ kind: 'kakao', consents }, 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('GATEWAY_003', 503))
    await expect(signup({ kind: 'kakao', consents }, 'api')).rejects.toThrow(ApiError)
    expect(setSession).not.toHaveBeenCalled()
  })

  it('목은 API 를 부르지 않고 카카오 예시 프로필의 미동의 회원이 된다', async () => {
    expect(await signup({ kind: 'kakao', consents }, 'mock')).toEqual({ status: 'ok' })
    expect(apiRequest).not.toHaveBeenCalled()
    expect(getMockSession()).toBe('member-no-consent')
    expect(getMockProfile()).toEqual(EXAMPLE_PROFILES.kakao)
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

describe('sendPasswordResetCode (API)', () => {
  it('인증 없이 이메일을 본문으로 보내고 sent 다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    expect(await sendPasswordResetCode('dong@example.com', 'api')).toEqual({ status: 'sent' })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/password/reset/send-code', {
      method: 'POST',
      body: { email: 'dong@example.com' },
      auth: false,
    })
  })

  it.each(['AUTH_001', 'AUTH_002'])('%s(429) 면 limit 이다', async (code) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, 429))
    expect(await sendPasswordResetCode('dong@example.com', 'api')).toEqual({ status: 'limit' })
  })

  it('일시 장애 · 분류 밖 오류는 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_006', 503))
    await expect(sendPasswordResetCode('dong@example.com', 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(sendPasswordResetCode('dong@example.com', 'api')).rejects.toThrow(ApiError)
  })

  it('목 저장소를 쓰지 않는다 — 실데이터로 보낸 코드는 목 확인에서 만료다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await sendPasswordResetCode('only-api-reset@example.com', 'api')
    expect(await verifyPasswordResetCode('only-api-reset@example.com', '482915', 'mock')).toEqual({
      status: 'expired',
    })
  })
})

describe('verifyPasswordResetCode (API)', () => {
  const wrongCode = () => apiError('AUTH_003', 400)

  it('인증 없이 이메일 · 코드를 본문으로 보내고 응답의 재설정 토큰을 돌려준다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ resetToken: 'q3J9x0b2V7mZ' })
    expect(await verifyPasswordResetCode('dong@example.com', '482915', 'api')).toEqual({
      status: 'ok',
      resetToken: 'q3J9x0b2V7mZ',
    })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/password/reset/verify-code', {
      method: 'POST',
      body: { email: 'dong@example.com', code: '482915' },
      auth: false,
    })
  })

  it('AUTH_003 이면 wrong 이고 남은 시도를 가입과 따로 센다', async () => {
    // 가입 인증에서 두 번 틀렸어도 재설정은 처음부터 센다
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode()).mockRejectedValueOnce(wrongCode())
    await verifyEmailCode('split@example.com', '000000', 'api')
    await verifyEmailCode('split@example.com', '000000', 'api')

    const remaining: number[] = []
    for (let i = 0; i < CODE_MAX_ATTEMPTS + 1; i += 1) {
      vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
      const result = await verifyPasswordResetCode(' Split@example.com ', '000000', 'api')
      if (result.status !== 'wrong') throw new Error(`wrong 이 아니다: ${result.status}`)
      remaining.push(result.remainingAttempts)
    }
    expect(remaining).toEqual([4, 3, 2, 1, 1, 1])
  })

  it('코드를 다시 받으면 · 인증을 마치면 센 실패 수를 0 으로 되돌린다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    await verifyPasswordResetCode('reset-again@example.com', '000000', 'api')
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await sendPasswordResetCode('reset-again@example.com', 'api')
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    expect(await verifyPasswordResetCode('reset-again@example.com', '000000', 'api')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })

    vi.mocked(apiRequest).mockResolvedValueOnce({ resetToken: 'token-1' })
    await verifyPasswordResetCode('reset-again@example.com', '482915', 'api')
    vi.mocked(apiRequest).mockRejectedValueOnce(wrongCode())
    expect(await verifyPasswordResetCode('reset-again@example.com', '000000', 'api')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })
  })

  it.each([
    ['AUTH_004', 400, 'expired'],
    ['AUTH_005', 400, 'locked'],
    ['AUTH_010', 429, 'locked'],
  ] as const)('%s 면 %s 다', async (code, status, expected) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    expect(await verifyPasswordResetCode('dong@example.com', '000000', 'api')).toEqual({
      status: expected,
    })
  })

  it('토큰이 없는 성공 응답 · 일시 장애 · 분류 밖 오류는 거부한다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await expect(verifyPasswordResetCode('dong@example.com', '482915', 'api')).rejects.toThrow()
    vi.mocked(apiRequest).mockResolvedValueOnce({ resetToken: '' })
    await expect(verifyPasswordResetCode('dong@example.com', '482915', 'api')).rejects.toThrow()
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_006', 503))
    await expect(verifyPasswordResetCode('dong@example.com', '482915', 'api')).rejects.toThrow(
      ApiError,
    )
  })
})

describe('resetPassword (API)', () => {
  /** 회원 정보 저장소가 이 탭 회원(memberId '1')의 내 정보를 읽어 둔 상태 */
  function knownEmail(email: string) {
    vi.mocked(getMemberInfoSnapshot).mockReturnValue({
      memberId: '1',
      info: {
        status: 'ready',
        value: {
          memberId: '1',
          email,
          nickname: '재채기탐정',
          provider: 'email',
          hasPassword: true,
          pendingConsents: [],
        },
      },
      region: { status: 'loading' },
    })
  }

  it('인증 없이 토큰 · 새 비밀번호만 본문으로 보낸다(이메일은 보내지 않는다)', async () => {
    vi.mocked(getSessionSnapshot).mockReturnValue({ status: 'guest' })
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    expect(await resetPassword('reset-token', 'newpass2026', 'dong@example.com', 'api')).toEqual({
      status: 'ok',
    })
    expect(apiRequest).toHaveBeenCalledTimes(1)
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/password/reset', {
      method: 'POST',
      body: { resetToken: 'reset-token', newPassword: 'newpass2026' },
      auth: false,
    })
  })

  it('이 탭 계정의 이메일과 같으면(앞뒤 공백 · 대소문자 무시) 이 탭 세션을 로그아웃으로 비운다 — 로그아웃 API 는 부르지 않는다', async () => {
    knownEmail('Dong@Example.com')
    notifySessionExpired()
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    expect(await resetPassword('reset-token', 'newpass2026', ' dong@example.com ', 'api')).toEqual({
      status: 'ok',
    })
    // 사용자가 고른 결과라 만료 안내가 아니다 — 화면이 이메일 로그인으로 간다
    expect(clearSession).toHaveBeenCalledWith('logout')
    expect(isSessionExpiring()).toBe(false)
    expect(apiRequest).toHaveBeenCalledTimes(1)
  })

  it('이 탭 계정과 다른 이메일이면 이 탭 세션을 건드리지 않는다(서버는 그 계정의 세션만 끊었다)', async () => {
    knownEmail('me@example.com')
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    expect(await resetPassword('reset-token', 'newpass2026', 'other@example.com', 'api')).toEqual({
      status: 'ok',
    })
    expect(clearSession).not.toHaveBeenCalled()
    expect(apiRequest).toHaveBeenCalledTimes(1)
  })

  it('이 탭 계정의 이메일을 모르면(내 정보 읽는 중 · 실패) 로그아웃 API 로 서버 세션까지 끊는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null).mockResolvedValueOnce(null)
    expect(await resetPassword('reset-token', 'newpass2026', 'dong@example.com', 'api')).toEqual({
      status: 'ok',
    })
    expect(apiRequest).toHaveBeenLastCalledWith('/api/v1/auth/logout', { method: 'POST' })
    expect(clearSession).toHaveBeenCalledTimes(1)
    expect(clearSession).toHaveBeenCalledWith('logout')
  })

  it('이메일을 모르고 로그아웃이 거부돼도(일시 장애) 이 탭 세션은 비운다 — 재설정은 성공이다', async () => {
    vi.mocked(apiRequest)
      .mockResolvedValueOnce(null)
      .mockRejectedValueOnce(unavailableError('network', 0))
    expect(await resetPassword('reset-token', 'newpass2026', 'dong@example.com', 'api')).toEqual({
      status: 'ok',
    })
    expect(apiRequest).toHaveBeenCalledTimes(2)
    expect(clearSession).toHaveBeenCalledWith('logout')
  })

  it('다른 회원의 내 정보만 있으면(회원이 바뀌는 중) 모르는 것으로 본다', async () => {
    knownEmail('dong@example.com')
    vi.mocked(getSessionSnapshot).mockReturnValue({
      status: 'member',
      summary: { memberId: '2', role: 'USER', pendingConsents: [], reportWritable: true },
    })
    vi.mocked(apiRequest).mockResolvedValueOnce(null).mockResolvedValueOnce(null)
    await resetPassword('reset-token', 'newpass2026', 'dong@example.com', 'api')
    expect(apiRequest).toHaveBeenLastCalledWith('/api/v1/auth/logout', { method: 'POST' })
  })

  it('이 탭에 세션이 없으면 비우지 않는다', async () => {
    vi.mocked(getSessionSnapshot).mockReturnValue({ status: 'guest' })
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    expect(await resetPassword('reset-token', 'newpass2026', 'dong@example.com', 'api')).toEqual({
      status: 'ok',
    })
    expect(clearSession).not.toHaveBeenCalled()
  })

  it.each([
    ['AUTH_018', 400, 'verification-expired'],
    ['AUTH_115', 400, 'verification-expired'],
    ['AUTH_116', 400, 'verification-expired'],
    ['AUTH_019', 429, 'limited'],
    ['AUTH_105', 400, 'invalid-password'],
    ['AUTH_106', 400, 'invalid-password'],
    ['AUTH_107', 400, 'invalid-password'],
  ] as const)('%s 면 %s 이고 세션은 그대로다', async (code, status, expected) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    expect(await resetPassword('reset-token', 'newpass2026', 'dong@example.com', 'api')).toEqual({
      status: expected,
    })
    expect(clearSession).not.toHaveBeenCalled()
  })

  it('세션 저장소 장애(AUTH_017) · 일시 장애는 거부하고 세션을 그대로 둔다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_017', 503))
    await expect(
      resetPassword('reset-token', 'newpass2026', 'dong@example.com', 'api'),
    ).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('timeout', 0))
    await expect(
      resetPassword('reset-token', 'newpass2026', 'dong@example.com', 'api'),
    ).rejects.toThrow(ApiError)
    expect(clearSession).not.toHaveBeenCalled()
  })

  it('목 세션 · 목 토큰은 건드리지 않는다', async () => {
    resetMockSession()
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await resetPassword('mock-reset-1', 'newpass2026', 'dong@example.com', 'api')
    expect(getMockSession()).toBe('member-no-consent')
  })
})

describe('changePassword (API)', () => {
  it('access 를 실어(auth 기본값) 현재 · 새 비밀번호를 본문으로 보내고 ok 다 — 이 기기 세션은 그대로다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    expect(await changePassword('dongne2026', 'newpass2026', 'api')).toEqual({ status: 'ok' })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/members/me/password', {
      method: 'POST',
      body: { currentPassword: 'dongne2026', newPassword: 'newpass2026' },
    })
    expect(clearSession).not.toHaveBeenCalled()
    expect(setSession).not.toHaveBeenCalled()
  })

  it.each([
    ['MEMBER_005', 400, 'wrong-current'],
    ['MEMBER_103', 400, 'wrong-current'],
    ['MEMBER_104', 400, 'wrong-current'],
    ['MEMBER_006', 429, 'locked'],
    ['MEMBER_105', 400, 'invalid-password'],
    ['MEMBER_106', 400, 'invalid-password'],
    ['MEMBER_107', 400, 'invalid-password'],
  ] as const)('%s 면 %s 다', async (code, status, expected) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, status))
    expect(await changePassword('dongne2026', 'newpass2026', 'api')).toEqual({ status: expected })
    expect(reloadMemberInfo).not.toHaveBeenCalled()
  })

  it('비밀번호 없는 계정(MEMBER_007)이면 no-password 이고 내 정보를 다시 읽는다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('MEMBER_007', 409))
    expect(await changePassword('dongne2026', 'newpass2026', 'api')).toEqual({
      status: 'no-password',
    })
    expect(reloadMemberInfo).toHaveBeenCalledTimes(1)
  })

  it('세션 저장소 장애(MEMBER_009) · 일시 장애 · 분류 밖 오류는 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('MEMBER_009', 503))
    await expect(changePassword('dongne2026', 'newpass2026', 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(changePassword('dongne2026', 'newpass2026', 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('MEMBER_100', 400))
    await expect(changePassword('dongne2026', 'newpass2026', 'api')).rejects.toThrow(ApiError)
    expect(clearSession).not.toHaveBeenCalled()
  })

  it('목 재현 값(wrong · fail2026)을 듣지 않는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null).mockResolvedValueOnce(null)
    expect(await changePassword('wrong', 'newpass2026', 'api')).toEqual({ status: 'ok' })
    expect(await changePassword('dongne2026', 'fail2026', 'api')).toEqual({ status: 'ok' })
  })
})

describe('로그인한 기기 (API)', () => {
  const SESSIONS = {
    sessions: [
      {
        sessionId: '3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61',
        deviceLabel: 'iPhone · Safari',
        createdAt: '2026-10-01T00:30:00Z',
        lastUsedAt: '2026-10-01T05:12:00Z',
        current: true,
      },
      {
        sessionId: '9b1e7c22-4d5f-4b6a-8c7d-1e2f3a4b5c6d',
        deviceLabel: 'Mac · Chrome',
        createdAt: '2026-09-20T01:00:00Z',
        lastUsedAt: '2026-09-30T15:30:00Z',
        current: false,
        // 계약 밖 값이 실려 와도 옮기지 않는다
        ip: '203.0.113.7',
      },
    ],
    totalCount: 2,
  }

  beforeEach(() => resetMockSession())

  it('목록은 access 를 실어 부르고 세션 id · 기기 이름 · 마지막 사용 시각 · 이 기기인지만 옮긴다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(SESSIONS)
    const sessions = await listSessions('api')
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/sessions')
    expect(sessions).toEqual([
      {
        id: '3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61',
        deviceName: 'iPhone · Safari',
        lastActiveAt: '2026-10-01T05:12:00Z',
        current: true,
      },
      {
        id: '9b1e7c22-4d5f-4b6a-8c7d-1e2f3a4b5c6d',
        deviceName: 'Mac · Chrome',
        lastActiveAt: '2026-09-30T15:30:00Z',
        current: false,
      },
    ])
  })

  it('목록이 실패하면 거부한다 — 예시 목록으로 채우지 않는다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(listSessions('api')).rejects.toThrow(ApiError)
  })

  it('다른 기기 하나를 로그아웃하면 그 세션 id 로 DELETE 하고 이 기기 세션은 그대로다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await revokeSession({ id: '9b1e7c22-4d5f-4b6a-8c7d-1e2f3a4b5c6d', current: false }, 'api')
    expect(apiRequest).toHaveBeenCalledWith(
      '/api/v1/auth/sessions/9b1e7c22-4d5f-4b6a-8c7d-1e2f3a4b5c6d',
      { method: 'DELETE' },
    )
    expect(clearSession).not.toHaveBeenCalled()
  })

  it('id 는 경로 조각으로만 붙인다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await revokeSession({ id: '../logout?x=1', current: false }, 'api')
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/sessions/..%2Flogout%3Fx%3D1', {
      method: 'DELETE',
    })
  })

  it('지금 기기(current)를 로그아웃하면 이 탭의 세션을 로그아웃으로 비운다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await revokeSession({ id: '3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61', current: true }, 'api')
    expect(clearSession).toHaveBeenCalledWith('logout')
  })

  it('세션 id 형식 오류(AUTH_114) · 일시 장애는 거부하고 세션을 그대로 둔다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_114', 400))
    await expect(revokeSession({ id: 'not-uuid', current: true }, 'api')).rejects.toThrow(ApiError)
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('timeout', 0))
    await expect(
      revokeSession({ id: '3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61', current: true }, 'api'),
    ).rejects.toThrow(ApiError)
    expect(clearSession).not.toHaveBeenCalled()
  })

  it('다른 기기 모두는 DELETE /sessions 이고 이 기기 세션은 그대로다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await revokeOtherSessions('api')
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/auth/sessions', { method: 'DELETE' })
    expect(clearSession).not.toHaveBeenCalled()
  })

  it('다른 기기 모두가 AUTH_014(이 기기 세션을 서버가 모름)면 로그인 만료로 비우고 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_014', 401))
    await expect(revokeOtherSessions('api')).rejects.toThrow(ApiError)
    expect(clearSession).toHaveBeenCalledWith('expired')

    // 이미 비었으면 다시 비우지 않는다
    vi.mocked(clearSession).mockClear()
    vi.mocked(getSessionSnapshot).mockReturnValue({ status: 'guest' })
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_014', 401))
    await expect(revokeOtherSessions('api')).rejects.toThrow(ApiError)
    expect(clearSession).not.toHaveBeenCalled()
  })

  it('다른 기기 모두의 일시 장애는 거부하고 세션을 그대로 둔다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('AUTH_017', 503))
    await expect(revokeOtherSessions('api')).rejects.toThrow(ApiError)
    expect(clearSession).not.toHaveBeenCalled()
  })

  it('목 서버 목록은 건드리지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    vi.mocked(apiRequest).mockResolvedValueOnce(null)
    await revokeOtherSessions('api')
    expect(await listSessions('mock')).toHaveLength(3)
  })
})
