import { describe, expect, it } from 'vitest'

import { SIGNUP_EMAIL_VERIFICATION_EXPIRED_PATH } from '@/features/onboarding/paths'

import { isResetDone, isVerificationExpired, loginNoticeFrom } from './login-notice'

describe('loginNoticeFrom', () => {
  type Params = Parameters<typeof loginNoticeFrom>[0]
  const CASES: [Params, ReturnType<typeof loginNoticeFrom>][] = [
    [{ error: 'kakao-fail' }, 'kakao-fail'],
    [{ error: 'kakao-exists' }, 'kakao-exists'],
    [{ reason: 'expired' }, 'expired'],
    [{ error: ['kakao-fail', 'kakao-exists'] }, 'kakao-fail'],
    [{ error: 'kakao-exists', reason: 'expired' }, 'kakao-exists'],
    [{ error: 'unknown' }, null],
    [{}, null],
  ]
  it.each(CASES)('%o → %s', (params, expected) => {
    expect(loginNoticeFrom(params)).toBe(expected)
  })
})

describe('isResetDone', () => {
  it('reset-done 일 때만 참이다', () => {
    expect(isResetDone('reset-done')).toBe(true)
    expect(isResetDone(['reset-done'])).toBe(true)
    expect(isResetDone('expired')).toBe(false)
    expect(isResetDone(undefined)).toBe(false)
  })
})

describe('isVerificationExpired', () => {
  it('verification-expired 일 때만 참이다', () => {
    expect(isVerificationExpired('verification-expired')).toBe(true)
    expect(isVerificationExpired(['verification-expired', 'expired'])).toBe(true)
    expect(isVerificationExpired('expired')).toBe(false)
    expect(isVerificationExpired(undefined)).toBe(false)
  })

  it('S02-3 이 보내는 주소의 쿼리를 알아본다', () => {
    const url = new URL(SIGNUP_EMAIL_VERIFICATION_EXPIRED_PATH, 'https://example.com')
    expect(url.pathname).toBe('/signup/email')
    expect(isVerificationExpired(url.searchParams.get('reason') ?? undefined)).toBe(true)
  })
})
