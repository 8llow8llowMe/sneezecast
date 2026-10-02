import { ApiError, UNAVAILABLE_CODE } from './api-error'

/**
 * 화면 · 세션 계층이 공통으로 다룰 오류 갈래. 코드 출처는 backend `SecurityErrorCode` · `JwtErrorCode`(게이트웨이도 같은 코드) ·
 * `AuthErrorCode` · `MemberErrorCode` · `GatewayErrorCode` 다.
 *
 * - `reissue`: access 가 만료 · 위조 · 폐기됐다(`SECURITY_002/003/004/005/007`, 401). 재발급하고 한 번 다시 보낸다
 * - `login-required`: 토큰 없이 회원 API 를 불렀다(`SECURITY_001`, 401)
 * - `forbidden`: 역할 · scope 가 모자란다(`SECURITY_006`, 403)
 * - `relogin`: refresh 가 없거나 만료 · 위조 · 재사용됐다(`AUTH_014/015`, 401). 다시 로그인한다
 * - `reissue-conflict`: 여러 탭이 동시에 재발급해 진 쪽이다(`AUTH_016`, 409). 세션은 그대로라 재발급을 한 번 다시 한다
 * - `unavailable`: 잠시 뒤 다시 시도하면 되는 장애(`SECURITY_008` · `AUTH_006/017` · `MEMBER_009` · `GATEWAY_003/004`,
 *   봉투 없는 응답 · 네트워크 · 타임아웃 `UNAVAILABLE`)
 * - `other`: 도메인 오류(로그인 실패 · 검증 · 탈퇴 회원 …). 화면이 `code` 로 직접 다룬다
 *
 * 401 재시도 · 재발급 · `notifySessionExpired()` 연결은 다음 연동 이슈에서 이 분류를 써서 만든다.
 */
export type ApiErrorKind =
  | 'reissue'
  | 'login-required'
  | 'forbidden'
  | 'relogin'
  | 'reissue-conflict'
  | 'unavailable'
  | 'other'

const KIND_BY_CODE: Readonly<Record<string, ApiErrorKind>> = {
  SECURITY_001: 'login-required',
  SECURITY_002: 'reissue',
  SECURITY_003: 'reissue',
  SECURITY_004: 'reissue',
  SECURITY_005: 'reissue',
  SECURITY_006: 'forbidden',
  SECURITY_007: 'reissue',
  SECURITY_008: 'unavailable',
  AUTH_006: 'unavailable',
  AUTH_014: 'relogin',
  AUTH_015: 'relogin',
  AUTH_016: 'reissue-conflict',
  AUTH_017: 'unavailable',
  MEMBER_009: 'unavailable',
  GATEWAY_003: 'unavailable',
  GATEWAY_004: 'unavailable',
  [UNAVAILABLE_CODE]: 'unavailable',
}

/** 오류 코드(`dataHeader.resultCode` 또는 `UNAVAILABLE`)를 갈래로 나눈다 */
export function classifyErrorCode(code: string): ApiErrorKind {
  return Object.hasOwn(KIND_BY_CODE, code) ? (KIND_BY_CODE[code] ?? 'other') : 'other'
}

/** 잡은 오류를 갈래로 나눈다. `ApiError` 가 아니면(호출한 쪽의 취소 · 프로그램 오류) `other` 다 */
export function classifyApiError(error: unknown): ApiErrorKind {
  return error instanceof ApiError ? classifyErrorCode(error.code) : 'other'
}
