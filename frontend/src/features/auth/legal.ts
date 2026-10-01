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
