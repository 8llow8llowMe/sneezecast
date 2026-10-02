import { describe, expect, it } from 'vitest'

import { ApiError, UNAVAILABLE_CODE, unavailableError } from './api-error'
import { classifyApiError, classifyErrorCode } from './error-kind'

function apiError(code: string, status = 400): ApiError {
  return new ApiError({ status, code, message: '서버 문구' })
}

describe('classifyErrorCode', () => {
  it.each(['SECURITY_002', 'SECURITY_003', 'SECURITY_004', 'SECURITY_005', 'SECURITY_007'])(
    '%s 는 access 를 재발급할 대상이다',
    (code) => {
      expect(classifyErrorCode(code)).toBe('reissue')
    },
  )

  it('SECURITY_001 은 로그인이 필요하다', () => {
    expect(classifyErrorCode('SECURITY_001')).toBe('login-required')
  })

  it('SECURITY_006 은 권한이 없다', () => {
    expect(classifyErrorCode('SECURITY_006')).toBe('forbidden')
  })

  it.each(['AUTH_014', 'AUTH_015'])('%s 는 다시 로그인해야 한다', (code) => {
    expect(classifyErrorCode(code)).toBe('relogin')
  })

  it('AUTH_016 은 재발급 경합이라 한 번 다시 시도한다', () => {
    expect(classifyErrorCode('AUTH_016')).toBe('reissue-conflict')
  })

  it.each([
    'SECURITY_008',
    'AUTH_006',
    'AUTH_017',
    'MEMBER_009',
    'GATEWAY_003',
    'GATEWAY_004',
    UNAVAILABLE_CODE,
  ])('%s 는 일시 장애다', (code) => {
    expect(classifyErrorCode(code)).toBe('unavailable')
  })

  it.each(['AUTH_011', 'MEMBER_002', 'DISTRICT_001', 'GATEWAY_001', 'GATEWAY_002', 'AUTH_100'])(
    '%s 같은 도메인 오류는 화면이 코드로 직접 다룬다',
    (code) => {
      expect(classifyErrorCode(code)).toBe('other')
    },
  )
})

describe('classifyApiError', () => {
  it('ApiError 는 코드로 분류한다', () => {
    expect(classifyApiError(apiError('SECURITY_002', 401))).toBe('reissue')
    expect(classifyApiError(unavailableError('timeout', 0))).toBe('unavailable')
  })

  it('ApiError 가 아니면(호출한 쪽의 취소 등) other 다', () => {
    expect(classifyApiError(new DOMException('aborted', 'AbortError'))).toBe('other')
    expect(classifyApiError(new Error('x'))).toBe('other')
    expect(classifyApiError(undefined)).toBe('other')
  })
})
