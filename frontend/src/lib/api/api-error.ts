/**
 * API 호출 실패를 하나의 모양으로 모은다.
 *
 * - 서버가 실패 봉투(`dataHeader.success === false`)를 주면 그 `resultCode` · `resultMessage` · `fieldErrors` 를 그대로 싣는다.
 * - 봉투가 없는 응답(Spring 기본 오류 · 프록시 HTML · 빈 본문) · 네트워크 실패 · 타임아웃은 일시 장애 `UNAVAILABLE` 이다
 *   (backend/docs/architecture-guide.md — FE 는 봉투가 없는 응답을 일시 장애로 다룬다).
 *
 * 메시지에는 서버 문구나 고정 문구만 싣는다. 요청 바디 · 토큰 · 주소 쿼리를 싣지 않는다.
 */

/** 봉투 없는 응답 · 네트워크 실패 · 타임아웃의 코드. 백엔드 코드 체계(`DOMAIN_NNN`)와 겹치지 않는다 */
export const UNAVAILABLE_CODE = 'UNAVAILABLE'

/** 일시 장애의 기본 문구. 화면은 이 문구를 쓰거나 동작에 맞는 문구("로그인하지 못했어요 …")로 바꾼다 */
export const UNAVAILABLE_MESSAGE = '지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.'

/** 필드 단위 검증 오류 (`dataHeader.fieldErrors[]`). 한 필드에 여러 개가 올 수 있고 서버가 순서를 고정해 준다 */
export type ApiFieldError = {
  /** 필드별 검증 코드 (예: `MEMBER_102`) */
  code: string
  /** 요청 바디의 필드 이름 (예: `nickname`). 필드를 모르면 `request` */
  field: string
  message: string
}

/** 일시 장애가 어디서 났는지. 화면은 구분하지 않는다(같은 안내) — 테스트 · 진단용이다 */
export type UnavailableReason = 'no-envelope' | 'network' | 'timeout'

type ApiErrorInit = {
  /** HTTP 상태. 응답을 받지 못했으면(네트워크 · 타임아웃) 0 */
  status: number
  code: string
  message: string
  fieldErrors?: readonly ApiFieldError[]
  unavailableReason?: UnavailableReason
  cause?: unknown
}

export class ApiError extends Error {
  override readonly name = 'ApiError'
  /** HTTP 상태. 응답을 받지 못했으면(네트워크 · 타임아웃) 0 */
  readonly status: number
  /** 서버 `resultCode` (예: `AUTH_011`). 일시 장애면 `UNAVAILABLE` */
  readonly code: string
  readonly fieldErrors: readonly ApiFieldError[]
  /** 일시 장애(`UNAVAILABLE`)일 때만 있다 */
  readonly unavailableReason: UnavailableReason | undefined

  constructor({ status, code, message, fieldErrors = [], unavailableReason, cause }: ApiErrorInit) {
    super(message, cause === undefined ? undefined : { cause })
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
    this.unavailableReason = unavailableReason
  }
}

/** 일시 장애 오류를 만든다 */
export function unavailableError(
  reason: UnavailableReason,
  status: number,
  cause?: unknown,
): ApiError {
  return new ApiError({
    status,
    code: UNAVAILABLE_CODE,
    message: UNAVAILABLE_MESSAGE,
    unavailableReason: reason,
    cause,
  })
}
