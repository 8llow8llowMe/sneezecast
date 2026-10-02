import type { ApiFieldError } from './api-error'

/**
 * 백엔드 공통 응답 봉투 (backend `common-core` 의 `Response<T>` · `DataHeader` · `ValidationErrorItem`).
 *
 * ```json
 * { "dataHeader": { "success": false, "resultCode": "MEMBER_102", "resultMessage": "…",
 *                   "fieldErrors": [{ "code": "MEMBER_102", "field": "nickname", "message": "…" }] },
 *   "dataBody": null }
 * ```
 *
 * - 성공이면 `resultCode` · `resultMessage` · `fieldErrors` 가 null 이고 `dataBody` 에 본문(본문 없는 API 는 null)이 있다.
 * - 실패면 `dataBody` 가 null 이고 `resultMessage` 는 늘 문자열이다. `fieldErrors` 는 검증 실패일 때만 있다.
 * - 게이트웨이의 토큰 거부(`SECURITY_00x`) · 게이트웨이 자체 오류(`GATEWAY_00x`)도 같은 봉투다.
 */
export type DataHeader =
  | { success: true }
  | { success: false; resultCode: string; resultMessage: string; fieldErrors: ApiFieldError[] }

export type Envelope = { dataHeader: DataHeader; dataBody: unknown }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readFieldErrors(value: unknown): ApiFieldError[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item): ApiFieldError[] => {
    if (!isRecord(item)) return []
    const { code, field, message } = item
    if (typeof code !== 'string' || typeof field !== 'string' || typeof message !== 'string') {
      return []
    }
    return [{ code, field, message }]
  })
}

/**
 * 파싱한 JSON 이 공통 봉투면 정리한 봉투를, 아니면 null 을 돌려준다.
 *
 * 실패인데 `resultCode` 가 문자열이 아니면 봉투로 보지 않는다 — 코드 없이는 화면이 분기할 수 없어 일시 장애로 다룬다.
 * `resultMessage` 가 문자열이 아니면 빈 문자열로 둔다. 모양이 틀린 `fieldErrors` 항목은 버린다.
 */
export function readEnvelope(json: unknown): Envelope | null {
  if (!isRecord(json) || !isRecord(json.dataHeader)) return null
  const header = json.dataHeader
  if (header.success === true) {
    return { dataHeader: { success: true }, dataBody: json.dataBody ?? null }
  }
  if (header.success !== false || typeof header.resultCode !== 'string') return null
  return {
    dataHeader: {
      success: false,
      resultCode: header.resultCode,
      resultMessage: typeof header.resultMessage === 'string' ? header.resultMessage : '',
      fieldErrors: readFieldErrors(header.fieldErrors),
    },
    dataBody: null,
  }
}
