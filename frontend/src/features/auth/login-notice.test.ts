import { describe, expect, it } from 'vitest'

import { isResetDone, loginNoticeFrom } from './login-notice'

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
