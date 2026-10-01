import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  agreeHealthConsent,
  CODE_MAX_ATTEMPTS,
  EMAIL_VERIFICATION_TTL_SECONDS,
  getMockSession,
  loginWithEmail,
  resetMockSession,
  saveRegion,
  sendEmailCode,
  signup,
  startKakaoLogin,
  subscribeMockSession,
  verifyEmailCode,
} from './auth-client'
import { consentFor, LEGAL_VERSIONS } from './legal'

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
    await expect(saveRegion('11680640')).resolves.toBeUndefined()
    await expect(agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))).resolves.toBeUndefined()
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
