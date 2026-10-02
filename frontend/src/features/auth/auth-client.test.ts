import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

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
  startKakaoLogin,
  subscribeMockSession,
  verifyEmailCode,
  verifyPasswordResetCode,
  withdrawHealthConsent,
  withdrawMembership,
} from './auth-client'
import { consentFor, LEGAL_VERSIONS } from './legal'

const YEOKSAM1 = { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }

describe('loginWithEmail (목)', () => {
  it('재현용 잠긴 이메일이면 locked 다 (대소문자 · 앞뒤 공백 무시)', async () => {
    expect(await loginWithEmail(' Locked@Example.com ', 'anything')).toEqual({ status: 'locked' })
  })

  it('재현용 비밀번호면 wrong 이다', async () => {
    expect(await loginWithEmail('dong@example.com', 'wrong')).toEqual({ status: 'wrong' })
  })

  it('그 밖에는 성공이다', async () => {
    expect(await loginWithEmail('dong@example.com', 'dongne2026')).toEqual({ status: 'ok' })
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
    expect(await sendEmailCode(email)).toEqual({ status })
  })
})

describe('verifyEmailCode (목)', () => {
  afterEach(() => vi.useRealTimers())

  it('맞는 6자리면 ok 만 돌려준다(토큰 없음)', async () => {
    await sendEmailCode('dong@example.com')
    expect(await verifyEmailCode('dong@example.com', '482915')).toEqual({ status: 'ok' })
  })

  it('보낸 코드가 없거나 이미 인증에 쓴 코드면 expired 다', async () => {
    expect(await verifyEmailCode('never@example.com', '482915')).toEqual({ status: 'expired' })
    await sendEmailCode('used@example.com')
    await verifyEmailCode('used@example.com', '482915')
    expect(await verifyEmailCode('used@example.com', '482915')).toEqual({ status: 'expired' })
  })

  it('5번까지 틀릴 수 있다 — 남은 시도가 4번부터 줄고 5번째에 locked 다', async () => {
    expect(CODE_MAX_ATTEMPTS).toBe(5)
    await sendEmailCode('try@example.com')
    for (const remainingAttempts of [4, 3, 2, 1]) {
      expect(await verifyEmailCode('try@example.com', '000000')).toEqual({
        status: 'wrong',
        remainingAttempts,
      })
    }
    expect(await verifyEmailCode('try@example.com', '000000')).toEqual({ status: 'locked' })
    // 서버처럼 잠기면 코드를 지운다 — 맞는 코드를 넣어도 만료다
    expect(await verifyEmailCode('try@example.com', '482915')).toEqual({ status: 'expired' })
    // 다시 받으면 실패 수가 처음으로 돌아간다
    await sendEmailCode('try@example.com')
    expect(await verifyEmailCode('try@example.com', '000000')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })
  })

  it('다시 받으면 남은 시도가 처음으로 돌아간다', async () => {
    await sendEmailCode('again@example.com')
    await verifyEmailCode('again@example.com', '000000')
    await sendEmailCode('again@example.com')
    expect(await verifyEmailCode('again@example.com', '000000')).toEqual({
      status: 'wrong',
      remainingAttempts: 4,
    })
  })

  it('재현용 잠김 코드면 locked, 5분이 지나면 expired 다', async () => {
    await sendEmailCode('late@example.com')
    expect(await verifyEmailCode('late@example.com', '999999')).toEqual({ status: 'locked' })
    expect(await verifyEmailCode('late@example.com', '482915')).toEqual({ status: 'expired' })

    vi.useFakeTimers()
    await sendEmailCode('late@example.com')
    vi.setSystemTime(Date.now() + 301_000)
    expect(await verifyEmailCode('late@example.com', '482915')).toEqual({ status: 'expired' })
  })
})

describe('signup · saveRegion · agreeHealthConsent (목)', () => {
  const consents = [consentFor('TERMS_OF_SERVICE')]
  const emailSignup = (email: string) =>
    signup({ kind: 'email', email, password: 'dongne2026', nickname: '동네지기', consents })

  async function verify(email: string) {
    await sendEmailCode(email)
    await verifyEmailCode(email, '482915')
  }

  afterEach(() => vi.useRealTimers())

  it('인증을 마친 이메일 가입 · 카카오 가입은 성공한다', async () => {
    await verify('ok@example.com')
    expect(await emailSignup(' OK@example.com ')).toEqual({ status: 'ok' })
    expect(await signup({ kind: 'kakao', consents })).toEqual({ status: 'ok' })
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

  it('내 동네 저장 · 건강정보 동의는 성공한다', async () => {
    await expect(saveRegion(YEOKSAM1)).resolves.toBeUndefined()
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
    await sendEmailCode('both@example.com')
    // 가입 코드만 보냈으면 재설정 코드는 없다
    expect(await verifyPasswordResetCode('both@example.com', '482915')).toEqual({
      status: 'expired',
    })

    await tokenFor('other@example.com')
    expect(
      await signup({
        kind: 'email',
        email: 'other@example.com',
        password: 'dongne2026',
        nickname: '동네지기',
        consents,
      }),
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
    await sendEmailCode('flow@example.com')
    await verifyEmailCode('flow@example.com', '482915')
    await signup({
      kind: 'email',
      email: 'flow@example.com',
      password: 'dongne2026',
      nickname: '동네지기',
      consents,
    })
    // 가입 응답에는 토큰이 없다. 이어지는 로그인이 회원으로 만든다
    expect(getMockSession()).toBe('guest')

    await loginWithEmail('flow@example.com', 'dongne2026')
    expect(getMockSession()).toBe('member-no-consent')

    await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
    expect(getMockSession()).toBe('member')
  })

  it('카카오 가입이 되면 바로 미동의 회원이다 (카카오 로그인 시작만으로는 바뀌지 않는다)', async () => {
    await startKakaoLogin()
    expect(getMockSession()).toBe('guest')
    await signup({ kind: 'kakao', consents })
    expect(getMockSession()).toBe('member-no-consent')
  })

  it('로그인 · 가입이 실패하면 바뀌지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'wrong')
    await loginWithEmail('locked@example.com', 'dongne2026')
    await expect(
      signup({
        kind: 'email',
        email: 'signup-fail@example.com',
        password: 'dongne2026',
        nickname: '동네지기',
        consents,
      }),
    ).rejects.toThrow()
    expect(getMockSession()).toBe('guest')
  })

  it('바뀔 때만 구독자를 부르고, 구독을 끊으면 더 부르지 않는다', async () => {
    const listener = vi.fn()
    const unsubscribe = subscribeMockSession(listener)

    await loginWithEmail('dong@example.com', 'dongne2026')
    await loginWithEmail('dong@example.com', 'dongne2026')
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
    await sendEmailCode('new@example.com')
    await verifyEmailCode('new@example.com', '482915')
    await signup({
      kind: 'email',
      email: 'new@example.com',
      password: 'dongne2026',
      nickname: '골목대장',
      consents,
    })
    await loginWithEmail(' New@Example.com ', 'dongne2026')
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
    await loginWithEmail('dong2@example.com', 'dongne2026')
    expect(getMockProfile()).toEqual({ ...EXAMPLE_PROFILES.email, email: 'dong2@example.com' })
  })

  it('카카오 가입은 카카오 예시 프로필이고, 건강정보 동의는 프로필을 그대로 둔다', async () => {
    await signup({ kind: 'kakao', consents })
    await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
    expect(getMockProfile()).toEqual(EXAMPLE_PROFILES.kakao)
  })

  it('로그아웃 · 탈퇴하면 비회원이 되고 프로필을 지운다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026')
    await logout()
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()

    await loginWithEmail('dong@example.com', 'dongne2026')
    await withdrawMembership()
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
  })

  it('동의 철회하면 로그아웃과 같이 비회원이 되고 프로필도 지운다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026')
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
    await loginWithEmail(email, 'dongne2026')
    await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
    await expect(action()).rejects.toThrow()
    expect(getMockSession()).toBe('member')
    expect(getMockProfile()?.email).toBe(email)
  })
})

describe('로그인한 기기 (목)', () => {
  beforeEach(() => resetMockSession())

  it('시안의 예시 기기(이 기기 + 다른 기기 둘)를 준다 — 기기 이름 · 마지막 사용 시각만, IP · 지역은 없다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026')
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
    await loginWithEmail('dong@example.com', 'dongne2026')
    await revokeOtherSessions()
    await logout()
    await loginWithEmail('dong@example.com', 'dongne2026')
    expect(await listSessions()).toHaveLength(3)
  })

  it('재현 이메일이면 목록 · 로그아웃을 거부하고 목록을 그대로 둔다', async () => {
    await loginWithEmail('sessions-fail@example.com', 'dongne2026')
    await expect(listSessions()).rejects.toThrow()

    await loginWithEmail('session-revoke-fail@example.com', 'dongne2026')
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
    await loginWithEmail('dong@example.com', 'dongne2026')
    expect(await changePassword('wrong', 'newpass2026')).toEqual({ status: 'wrong-current' })
    expect(await changePassword('dongne2026', 'newpass2026')).toEqual({ status: 'ok' })
    // 새 비밀번호가 현재와 같아도 막지 않는다(계약에 없음)
    expect(await changePassword('dongne2026', 'dongne2026')).toEqual({ status: 'ok' })
  })

  it('설정에 성공하면 카카오 회원 프로필의 hasPassword 가 true 가 된다', async () => {
    await signup({ kind: 'kakao', consents })
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
    await loginWithEmail('password-fail@example.com', 'dongne2026')
    await expect(changePassword('dongne2026', 'newpass2026')).rejects.toThrow()
    await expect(changePassword('wrong', 'newpass2026')).rejects.toThrow()

    await signup({ kind: 'kakao', consents })
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
    await loginWithEmail('Reconsent@example.com', 'dongne2026')
    expect(getMockProfile()).toMatchObject({
      termsReconsentRequired: true,
      regionAbolished: false,
      region: null,
    })

    await loginWithEmail('reselect@example.com', 'dongne2026')
    expect(getMockProfile()).toMatchObject({
      termsReconsentRequired: false,
      regionAbolished: true,
      region: { code: '99990110', name: '○○1동' },
    })
  })

  it('그 밖의 이메일은 조건이 없다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026')
    expect(getMockProfile()).toMatchObject({
      termsReconsentRequired: false,
      regionAbolished: false,
    })
  })

  it('재동의에 성공하면 재동의 표시를 끄고 다른 값은 그대로 둔다', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026')
    const before = getMockProfile()
    await expect(agreeTermsReconsent(consentFor('TERMS_OF_SERVICE'))).resolves.toBeUndefined()
    expect(getMockProfile()).toEqual({ ...before, termsReconsentRequired: false })
    expect(getMockSession()).toBe('member-no-consent')
  })

  it('재현 이메일이면 재동의가 거부되고 표시는 그대로다', async () => {
    await loginWithEmail('reconsent-fail@example.com', 'dongne2026')
    await expect(agreeTermsReconsent(consentFor('TERMS_OF_SERVICE'))).rejects.toThrow()
    expect(getMockProfile()?.termsReconsentRequired).toBe(true)
  })

  it('동네를 저장하면 프로필의 동네를 바꾸고 폐지 표시를 끈다', async () => {
    await loginWithEmail('reselect@example.com', 'dongne2026')
    await saveRegion({ code: '99990111', name: '○○새1동' })
    expect(getMockProfile()).toMatchObject({
      region: { code: '99990111', name: '○○새1동' },
      regionAbolished: false,
    })
  })

  it('재현 이메일이면 동네 저장이 거부되고 옛 동네 · 폐지 표시는 그대로다', async () => {
    await loginWithEmail('reselect-fail@example.com', 'dongne2026')
    await expect(saveRegion(YEOKSAM1)).rejects.toThrow()
    expect(getMockProfile()).toMatchObject({
      region: { code: '99990110', name: '○○1동' },
      regionAbolished: true,
    })
  })

  it('프로필이 없으면(덮어쓰기만 있음) 세션을 바꾸지 않는다', async () => {
    await saveRegion(YEOKSAM1)
    await agreeTermsReconsent(consentFor('TERMS_OF_SERVICE'))
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
  })
})
