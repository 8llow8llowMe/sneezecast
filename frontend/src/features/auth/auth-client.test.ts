import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  agreeHealthConsent,
  loginWithEmail,
  saveRegion,
  sendEmailCode,
  signup,
  startKakaoLogin,
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
  it('신규 회원으로 보고 동네 선택으로 보낸다', async () => {
    expect(await startKakaoLogin()).toEqual({ redirectTo: '/setup/region' })
    expect(await startKakaoLogin({ switchAccount: true })).toEqual({ redirectTo: '/setup/region' })
  })
})

describe('sendEmailCode (목)', () => {
  it.each([
    ['exists@example.com', 'exists'],
    [' LIMIT@example.com ', 'limit'],
    ['dong@example.com', 'sent'],
  ] as const)('%s → %s', async (email, status) => {
    expect(await sendEmailCode(email)).toEqual({ status })
  })
})

describe('verifyEmailCode (목)', () => {
  afterEach(() => vi.useRealTimers())

  it('맞는 6자리면 인증 값을 돌려준다', async () => {
    await sendEmailCode('dong@example.com')
    const result = await verifyEmailCode('dong@example.com', '482915')
    expect(result.status).toBe('ok')
  })

  it('보낸 코드가 없거나 이미 인증에 쓴 코드면 expired 다', async () => {
    expect(await verifyEmailCode('never@example.com', '482915')).toEqual({ status: 'expired' })
    await sendEmailCode('used@example.com')
    await verifyEmailCode('used@example.com', '482915')
    expect(await verifyEmailCode('used@example.com', '482915')).toEqual({ status: 'expired' })
  })

  it('틀릴 때마다 남은 시도가 줄고 0 이면 locked 다', async () => {
    await sendEmailCode('try@example.com')
    expect(await verifyEmailCode('try@example.com', '000000')).toEqual({
      status: 'wrong',
      remainingAttempts: 3,
    })
    await verifyEmailCode('try@example.com', '000000')
    await verifyEmailCode('try@example.com', '000000')
    expect(await verifyEmailCode('try@example.com', '000000')).toEqual({ status: 'locked' })
    // 잠긴 뒤에는 맞는 코드도 받지 않는다
    expect(await verifyEmailCode('try@example.com', '482915')).toEqual({ status: 'locked' })
  })

  it('다시 받으면 남은 시도가 처음으로 돌아간다', async () => {
    await sendEmailCode('again@example.com')
    await verifyEmailCode('again@example.com', '000000')
    await sendEmailCode('again@example.com')
    expect(await verifyEmailCode('again@example.com', '000000')).toEqual({
      status: 'wrong',
      remainingAttempts: 3,
    })
  })

  it('재현용 잠김 코드면 locked, 5분이 지나면 expired 다', async () => {
    await sendEmailCode('late@example.com')
    expect(await verifyEmailCode('late@example.com', '999999')).toEqual({ status: 'locked' })

    vi.useFakeTimers()
    await sendEmailCode('late@example.com')
    vi.setSystemTime(Date.now() + 301_000)
    expect(await verifyEmailCode('late@example.com', '482915')).toEqual({ status: 'expired' })
  })
})

describe('signup · saveRegion · agreeHealthConsent (목)', () => {
  const consents = [consentFor('TERMS_OF_SERVICE')]

  it('이메일 · 카카오 가입 모두 성공한다', async () => {
    await expect(
      signup({
        kind: 'email',
        email: 'dong@example.com',
        verificationToken: 't',
        password: 'dongne2026',
        nickname: '동네지기',
        consents,
      }),
    ).resolves.toBeUndefined()
    await expect(signup({ kind: 'kakao', consents })).resolves.toBeUndefined()
  })

  it('재현용 이메일이면 가입이 거부된다', async () => {
    await expect(
      signup({
        kind: 'email',
        email: 'signup-fail@example.com',
        verificationToken: 't',
        password: 'dongne2026',
        nickname: '동네지기',
        consents,
      }),
    ).rejects.toThrow()
  })

  it('내 동네 저장 · 건강정보 동의는 성공한다', async () => {
    await expect(saveRegion('11680640')).resolves.toBeUndefined()
    await expect(agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))).resolves.toBeUndefined()
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
