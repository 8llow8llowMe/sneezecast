import { describe, expect, it } from 'vitest'

import { NO_CHECKS, requiredAgreed, setAll } from './terms-checks'

describe('가입 동의 체크 규칙', () => {
  it('전체 동의는 필수 둘이 모두 켜졌을 때만 켜진다 (선택은 꺼져도 된다)', () => {
    expect(requiredAgreed({ terms: true, privacy: true, notification: false })).toBe(true)
    expect(requiredAgreed({ terms: true, privacy: false, notification: true })).toBe(false)
    expect(requiredAgreed(NO_CHECKS)).toBe(false)
  })

  it('전체 동의를 켜면 선택까지 모두 켜고 끄면 모두 끈다', () => {
    expect(setAll(true)).toEqual({ terms: true, privacy: true, notification: true })
    expect(setAll(false)).toEqual(NO_CHECKS)
  })
})
