import type { ReportSymptom } from '@/features/report/types'

/* ── 운영자 검토 (A01–A02 `/admin/review`, #219) ────────────────────────────────────────────────
 *
 * 화면 모델. **백엔드(#214)가 아직 없다** — 가정한 응답은 docs/api-contract-draft.md "운영자 검토 (#219, 프론트 초안)" 다.
 * 시안(Admin)에는 있지만 백엔드 1단계(backend/docs/entity-design.md §2-2 · §6)에 없는 값은 **선택 값**으로 둔다.
 * 값이 없으면 화면이 그 칸 · 행을 숨긴다 — 지어낸 값이나 0 으로 채우지 않는다.
 */

/** 운영자 검토 대기 (A01–A02) */
export const ADMIN_REVIEW_PATH = '/admin/review'

/**
 * 후보 유형 (시안 필터 · 표의 `유형`).
 * - baseline: 기준선 대비 변화 — 기준선(`district_baseline`)이 2단계라 1단계에는 이 유형이 나오지 않는다
 * - surge: 참여 급증 — 전주 대비 `participant_count` (1단계)
 * - repeat: 반복 보고 의심 — `revised_report_count / participant_count` (1단계)
 */
export const CANDIDATE_KINDS = ['baseline', 'surge', 'repeat'] as const
export type CandidateKind = (typeof CANDIDATE_KINDS)[number]

export const CANDIDATE_KIND_LABEL: Readonly<Record<CandidateKind, string>> = {
  baseline: '기준선 변화',
  surge: '참여 급증',
  repeat: '반복 보고 의심',
}

/** 상세 제목의 유형 이름 (시안 `○○1동 · 기준선 대비 변화`) */
export const CANDIDATE_KIND_TITLE: Readonly<Record<CandidateKind, string>> = {
  baseline: '기준선 대비 변화',
  surge: '참여 급증',
  repeat: '반복 보고 의심',
}

/**
 * 후보 처리 상태 (시안 표의 `상태`).
 * - waiting: 대기 — 아무도 초안을 고치거나 승인하지 않음
 * - reviewing: 검토 중 — 안내가 DRAFT · AI_DRAFTED · EDITED · APPROVED 중 하나(backend §2-5)
 * - held: 보류 — 이번 주 이 후보로는 안내를 내지 않기로 함. **§2-5 에 없다**(안내가 아니라 후보의 처리 결과다). 목록에 남는다
 *
 * 발행한 후보는 목록에서 빠진다(발행 이력 A03 `/admin/history`, #220).
 */
export type CandidateState = 'waiting' | 'reviewing' | 'held'

export const CANDIDATE_STATE_LABEL: Readonly<Record<CandidateState, string>> = {
  waiting: '대기',
  reviewing: '검토 중',
  held: '보류',
}

/** 안내문 초안 최대 길이. 시민 화면의 안내 본문 한 단락 분량이다(시안 초안 약 150자) */
export const DRAFT_MAX_LENGTH = 500

export type ReviewCandidate = Readonly<{
  id: string
  kind: CandidateKind
  districtCode: string
  districtName: string
  /** 집계 주 (ISO 주 `YYYY-Www`) */
  isoWeek: string
  /** 참여자 수. 후보는 표본 100명 이상에서만 나온다(그 아래는 시민 화면에서 `자료 부족`) */
  participants: number
  /** 증상 보고 비율(%, 참여자 대비) */
  symptomRate: number
  /** 가장 많이 보고된 증상군 (상세의 `발열·기침·인후통`) */
  leadingSymptom: ReportSymptom
  state: CandidateState
  /** 안내문 초안 (AI 초안 · 운영자 수정본). 운영자가 고쳐 저장 · 발행한다 */
  draft: string
  /** 동시 수정 확인 값 (backend advisory `@Version`). 고칠 때 함께 보내고, 다르면 409 */
  version: number
  /** 연결된 예방수칙 이름 (질병관리청) */
  guides: readonly string[]

  // ── 백엔드 1단계에 없는 값 — 없으면 그 칸 · 행을 숨긴다 ──────────────────────────────
  /** 기준선 증상 보고 비율(%). 2단계 `district_baseline` */
  baselineRate?: number
  /** 기준선 대비 변화(%p). 2단계 */
  baselineDeltaPp?: number
  /** 최근 주 증상 보고 비율(%), 오래된 주부터 이번 주까지(시안 8주 막대). 기준선과 함께 그린다 */
  recentRates?: readonly number[]
  /** 짧은 시간 몰림 건수. 보고 시각 분포를 집계하지 않아 셀 수 없다 */
  burstCount?: number
  /** 같은 기기 반복 보고 건수(집계 제외). 기기를 저장하지 않아 셀 수 없다 */
  sameDeviceRepeatCount?: number
  /** 이번 주 신규 참여 비율(%). 집계에 신규 참여자 수가 없다 */
  newParticipantRate?: number
  /** 검토 시작 시각(ISO). 저장하는 곳이 없다 */
  reviewStartedAt?: string
}>
