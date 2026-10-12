import { ApiError } from '@/lib/api/api-error'
import { classifyApiError } from '@/lib/api/error-kind'
import { refreshSession } from '@/lib/session/session-store'

/*
 * 운영자 안내 API(docs/api-contract-draft.md "운영자 안내 처리" · "운영자 검토 후보" · "운영자 정정 · 발행 이력")의 요청이 함께 쓰는 값.
 * 발행 이력(`admin-history-client.ts`, #271)과 검토 대기(`admin-review-client.ts`, #272)가 같은 오류 갈래로 처리 결과를 나눈다.
 * 목 저장소 · 목 세션을 갖지 않는다 — 요청 갈래만 둔다.
 */

/** 운영자 안내 처리 · 검토 후보 · 발행 이력 API 앞부분 */
export const ADMIN_ADVISORIES_PATH = '/api/v1/advisories/admin'

/**
 * 다시 읽어 그릴 처리 거부 — 지금 상태에서 할 수 없음(`005` — 이미 정정 · 철회 · 발행), 버전 다름(`006`), 같은 동네 · 주에 발행 중인 안내가 있음
 * (`007`), 없는 안내(`001`). 거부되면 아무것도 바뀌지 않는다
 */
export const CONFLICT_CODES: ReadonlySet<string> = new Set([
  'ADVISORY_001',
  'ADVISORY_005',
  'ADVISORY_006',
  'ADVISORY_007',
])

/** 입력 검증 거부 — 본문 형식(`100`), 제목 · 본문 · 사유 · version(`105`~`113`), 숫자가 아닌 ID(`198`) */
export const INVALID_CODES: ReadonlySet<string> = new Set([
  'ADVISORY_100',
  ...Array.from({ length: 9 }, (_, index) => `ADVISORY_${105 + index}`),
  'ADVISORY_198',
])

/** 서버가 준 오류 코드. API 오류가 아니면(프로그램 오류 · 매핑 실패) null */
export function errorCode(error: unknown): string | null {
  return error instanceof ApiError ? error.code : null
}

/** 권한 없음(403)이면 세션 요약을 다시 맞춘다 — 역할이 바뀌었으면 운영자 가드가 권한 없음을 그린다. 오류는 그대로 둔다 */
export function syncRoleOnForbidden(error: unknown): void {
  if (classifyApiError(error) === 'forbidden') void refreshSession()
}
