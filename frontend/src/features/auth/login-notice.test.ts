import { describe, expect, it } from 'vitest'

import { SIGNUP_EMAIL_VERIFICATION_EXPIRED_PATH } from '@/features/onboarding/paths'

import {
  isResetDone,
  isVerificationExpired,
  kakaoFailPath,
  kakaoFailReasonFrom,
  loginNoticeFrom,
} from './login-notice'

describe('loginNoticeFrom', () => {
  type Params = Parameters<typeof loginNoticeFrom>[0]
  const CASES: [Params, ReturnType<typeof loginNoticeFrom>][] = [
    [{ error: 'kakao-fail' }, 'kakao-fail'],
    [{ reason: 'expired' }, 'expired'],
    [{ error: ['kakao-fail', 'unknown'] }, 'kakao-fail'],
    [{ error: 'kakao-fail', reason: 'expired' }, 'kakao-fail'],
    // 이메일 회원과 겹침은 #167 에서 계정 연결 확인 화면이 됐다 — 옛 주소는 알리지 않는다
    [{ error: 'kakao-exists' }, null],
    [{ error: 'kakao-exists', reason: 'expired' }, 'expired'],
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

describe('kakaoFailReasonFrom', () => {
  it.each([
    ['email-required', 'email-required'],
    ['email-unverified', 'email-unverified'],
    ['expired', 'expired'],
    ['suspended', 'suspended'],
    [['expired', 'suspended'], 'expired'],
    // 허용 목록 밖(서버 코드 · 문구 등)은 사유 없이 알린다
    ['AUTH_023', null],
    ['<script>', null],
    [undefined, null],
  ] as const)('%o → %s', (value, expected) => {
    expect(kakaoFailReasonFrom(value as string | string[] | undefined)).toBe(expected)
  })
})

describe('kakaoFailPath', () => {
  it('사유가 없으면 kakao-fail 만, 있으면 허용 목록 값을 함께 싣는다', () => {
    expect(kakaoFailPath(null)).toBe('/login?error=kakao-fail')
    expect(kakaoFailPath('expired')).toBe('/login?error=kakao-fail&kakao=expired')
  })

  it('로그인 화면이 같은 값으로 읽는다', () => {
    const url = new URL(kakaoFailPath('email-unverified'), 'https://example.com')
    expect(url.pathname).toBe('/login')
    expect(loginNoticeFrom({ error: url.searchParams.get('error') ?? undefined })).toBe(
      'kakao-fail',
    )
    expect(kakaoFailReasonFrom(url.searchParams.get('kakao') ?? undefined)).toBe('email-unverified')
  })
})
