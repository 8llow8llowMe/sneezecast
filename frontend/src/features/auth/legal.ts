/**
 * 동의 문서 버전. **이 상수가 정본이다** (backend/docs/entity-design.md §1-2 "정본은 프론트 legal 상수").
 * 백엔드는 `legal.*-version` 설정을 이 값과 맞춘다. 문서를 고치면 여기 버전을 올리고 백엔드 설정도 함께 올린다 —
 * 이전 버전 동의자는 다음 로그인 때 재동의 화면(Setup-3-reconsent)으로 간다.
 *
 * 항목 이름은 백엔드 `ConsentType`(§1-3)과 같다.
 * - AGE_OVER_19: S02-2 성인 확인 값으로 보낸다. 성인 기준을 정한 이용약관 버전을 넣는다
 * - 알림 수신 동의(`PUSH_NOTIFICATION`)는 백엔드가 푸시와 함께 2단계에 더한다. 지금은 보내지 않는다
 */
const TERMS_VERSION = '2026-10-01'

export const LEGAL_VERSIONS = {
  TERMS_OF_SERVICE: TERMS_VERSION,
  PRIVACY_POLICY: '2026-10-01',
  SENSITIVE_HEALTH_INFO: '2026-10-01',
  AGE_OVER_19: TERMS_VERSION,
} as const

export type ConsentType = keyof typeof LEGAL_VERSIONS

/** 동의한 항목과 그 근거 문서 버전 */
export type Consent = { type: ConsentType; documentVersion: string }

export function consentFor(type: ConsentType): Consent {
  return { type, documentVersion: LEGAL_VERSIONS[type] }
}

/**
 * 필수 약관 개정 안내 (Setup-3-reconsent). 재동의 화면이 시행일 · 바뀐 내용을 보이고, 동의는 지금 버전
 * (`LEGAL_VERSIONS.TERMS_OF_SERVICE`)으로 보낸다.
 *
 * **시안 예시 문구다.** 실제로 이용약관을 고칠 때 `TERMS_VERSION` 을 올리고 이 시행일 · 바뀐 내용을 함께 바꾼다.
 * 시행일은 한국 날짜(`YYYY-MM-DD`)다. 개별 보고 보관 기간 52주는 확정된 값이다(docs/design/auth/README.md).
 */
export const TERMS_REVISION = {
  effectiveDate: '2026-12-01',
  changes: ['운영자 안내의 정정·철회 절차를 추가했어요.', '개별 보고 보관 기간을 52주로 정했어요.'],
} as const satisfies { effectiveDate: string; changes: readonly string[] }
