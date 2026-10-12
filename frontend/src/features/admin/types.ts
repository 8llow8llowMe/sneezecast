import type { NoticeCorrection } from '@/features/notice/types'
import type { ReportSymptom } from '@/features/report/types'
import type { RegionStatus } from '@/lib/status'

/* ── 운영자 검토 (A01–A02 `/admin/review`, #219 · 연동 #272) ────────────────────────────────────────
 *
 * 화면 모델. 후보 목록의 응답은 docs/api-contract-draft.md "운영자 검토 후보"(#266, 확정)다 — 응답은 동네 하나가 한 행이고
 * 신호가 여럿일 수 있어 `admin-review-api.ts` 의 매핑이 이 모델로 옮긴다. 처리(수정 · 승인 · 발행 · 철회)의 응답은 "운영자 안내 처리"(#246, 확정)다.
 * 시안(Admin)에는 있지만 백엔드 1단계(backend/docs/entity-design.md §2-2 · §6)에 없는 값과, 응답에 따라 없을 수 있는 값
 * (자료 부족 후보의 수치 · 안내가 없는 후보의 초안 · 버전 · 신호가 없는 후보의 유형)은 **선택 값**으로 둔다.
 * 값이 없으면 화면이 그 칸 · 행을 숨긴다 — 지어낸 값이나 0 으로 채우지 않는다.
 */

/** 운영자 검토 대기 (A01–A02) */
export const ADMIN_REVIEW_PATH = '/admin/review'

/** 운영자 발행 이력 · 정정 · 철회 (A03, #220) */
export const ADMIN_HISTORY_PATH = '/admin/history'

/**
 * 후보 유형 (시안 필터 · 표의 `유형`).
 * - baseline: 기준선 대비 변화 — 집계 단계 SLIGHT · HIGH (후보 API `BASELINE` 신호, #266)
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
 * - waiting: 대기 — 아무도 초안을 고치거나 승인하지 않음(후보 API: 그 주에 안내가 없거나 철회된 안내뿐)
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

/**
 * 검토 중인 안내의 상태 (backend `AdvisoryStatus` 중 처리할 수 있는 넷). 승인하고 발행이 이 값으로 단계를 고른다 —
 * APPROVED 면 고치지 않았을 때 승인을 건너뛰고 발행만 한다. 발행됨 · 철회됨 안내는 후보에 처리할 안내로 싣지 않는다
 */
export type ReviewAdvisoryStatus = 'DRAFT' | 'AI_DRAFTED' | 'EDITED' | 'APPROVED'

/** 안내문 초안 최대 길이. 시민 화면의 안내 본문 한 단락 분량이다(시안 초안 약 150자) */
export const DRAFT_MAX_LENGTH = 500

/** 자료 부족 이유 (후보 API `insufficientReason` — LOW_SAMPLE · UNSTABLE · NO_BASELINE) */
export type InsufficientReason = 'low-sample' | 'unstable' | 'no-baseline'

export type ReviewCandidate = Readonly<{
  /** 후보 키. 후보 API 에는 ID 가 없어 동네 · 주로 만든다(`{districtCode}:{isoWeek}`). 처리 요청의 안내 ID 는 `advisoryId` 다 */
  id: string
  /**
   * 대표 유형 하나 — 신호가 여럿이면 기준선 변화 → 참여 급증 → 반복 보고 의심 순으로 고른다(전체는 `signals`).
   * 신호 없이 그 주에 안내가 있어 후보가 된 동네(후보 API `signals: []`)는 없다 — 유형을 지어내지 않는다
   */
  kind?: CandidateKind
  districtCode: string
  districtName: string
  /** 집계 주 (ISO 주 `YYYY-Www`) */
  isoWeek: string
  /** 참여자 수. 자료 부족 후보(참여 급변 · 기준선 없음)도 있다 — 그때는 수치 칸 대신 `자료 부족` 이다 */
  participants: number
  /** 증상 보고 비율(%, 참여자 대비). 자료 부족 후보에는 없다 */
  symptomRate?: number
  /** 가장 많이 보고된 증상군 (상세의 `발열·기침·인후통`). 자료 부족이거나 증상군 수가 같으면 없다 */
  leadingSymptom?: ReportSymptom
  state: CandidateState
  /** 안내문 초안 (AI 초안 · 운영자 수정본). 운영자가 고쳐 저장 · 발행한다. 안내 상세(`GET /{advisoryId}`)를 읽기 전 · 안내가 없으면 없다 */
  draft?: string
  /**
   * 동시 수정 확인 값 (backend advisory `@Version`). 고칠 때 함께 보내고, 다르면 409. 처리할 안내(검토 중)가 없으면 없다 —
   * 그때는 초안 만들기(`POST`)부터다
   */
  version?: number
  /** 연결된 예방수칙 이름 (질병관리청). 후보 API 에 없다(1단계에 출처가 없다) — 목에만 있다 */
  guides?: readonly string[]

  // ── 후보 API(#266)에서 오는 값 — 목에는 없을 수 있다 ─────────────────────────────────────
  /** 처리할 안내(검토 중 — DRAFT · AI_DRAFTED · EDITED · APPROVED)의 ID. 안내가 없거나 철회된 안내뿐이면 없다 */
  advisoryId?: string
  /** 처리할 안내의 상태. `advisoryId` 와 함께 있고 함께 없다 */
  advisoryStatus?: ReviewAdvisoryStatus
  /** 안내 제목 (advisory `title`). 안내 상세를 읽으면 있다 */
  title?: string
  /** 신호 전체, 기준선 변화 → 참여 급증 → 반복 보고 의심 순. 신호 없이 안내만 있는 후보는 빈 배열이다 */
  signals?: readonly CandidateKind[]
  /** 시도 · 시군구 표기 (`서울특별시 송파구`). 행정동 행이 없으면 없다 */
  sigungu?: string
  /** 현행 행정동인지. 폐지된 코드면 false */
  active?: boolean
  /** 집계 단계. `insufficient` 면 수치 · 상태색 없이 `자료 부족` 으로 그린다 */
  level?: RegionStatus
  /** 자료 부족 이유. 자료 부족이 아니면 없다 */
  insufficientReason?: InsufficientReason
  /** 이 동네 · 주로 초안을 만들 수 있는지 — 마감됐고 자료 부족이 아니다(아니면 초안 만들기 · 발행을 막는다) */
  draftable?: boolean
  /** 전주 참여자 수. 전주 집계가 없으면 없다 */
  previousParticipants?: number
  /** 그 주에 한 번 이상 수정된 보고 수 (반복 보고 신호의 근거) */
  revisedReports?: number
  /** 증상군 하나 이상 보고 수. 자료 부족 후보에는 없다 */
  symptomReports?: number

  // ── 백엔드 1단계에 없는 값 — 없으면 그 칸 · 행을 숨긴다 ──────────────────────────────
  /** 기준선 증상 보고 비율(%). 후보 API 에 있다(#266, 집계 판정 기준선) — 자료 부족 후보에는 없다 */
  baselineRate?: number
  /** 기준선 대비 변화(%p). 후보 API 에 있다(#266) — 자료 부족 후보에는 없다 */
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

/** 한 주의 검토 후보 목록 (후보 API 응답을 옮긴 화면 묶음) */
export type ReviewCandidateList = Readonly<{
  /** 조회한 집계 주 (ISO 주 `YYYY-Www`) — 화면 머리줄의 기준 주 */
  isoWeek: string
  /** 그 주 집계가 마감됐는지. 마감 전이면 어느 후보로도 초안을 만들 수 없다 */
  finalized: boolean
  /** 지금 쓰는 후보 기준 (목록 설명 줄의 `후보 기준` 문구) */
  criteria: Readonly<{
    /** 참여 급증 — 전주 대비 증가율(%) */
    surgeChangePercent: number
    /** 반복 보고 의심 — 참여자 대비 수정된 보고 비율(%) */
    repeatRevisedPercent: number
  }>
  /** 서버 순서(신호 있는 후보 → 단계 → 행정동 코드). 발행 중인 안내가 있는 동네는 빠진다 */
  candidates: readonly ReviewCandidate[]
}>

/* ── 발행 이력 (A03 `/admin/history`, #220 · 연동 #271) ────────────────────────────────────────
 *
 * 후보 하나의 처리 결과(보류 — 목에만 있다) 또는 안내(advisory) 하나가 한 행이다. 전이 이력은 backend advisory_history(§2-4 — from/to status ·
 * title · body · operator_id · ai_model · prompt_version · memo · created_at)에서 나오는 값만 필수로 둔다.
 * 후보 유형 · 담당 이름 · 검토 시간 · 단계별 세부 문구는 이력 응답에 없어 **선택 값**이다 — 없으면 화면이 숨긴다(검토 대기와 같은 원칙).
 * 응답에서 옮기는 규칙은 `admin-history-api.ts` 의 `toHistoryEntry` 다.
 */

/**
 * 이력 행의 결과.
 * - published: 발행 중(PUBLISHED)
 * - held: 보류 — 후보의 처리 결과(§2-5 에 없다, 검토 대기의 `held`)
 * - corrected: 정정됨 — 새 안내로 정정 발행해 내려간 이전 안내. 백엔드는 RETRACTED 이고 이력 응답의 `correctedByAdvisoryId` 가 있다(#267)
 * - retracted: 철회(RETRACTED, 끝 상태)
 */
export type HistoryOutcome = 'published' | 'held' | 'corrected' | 'retracted'

/** 정정 · 철회 사유 최대 길이. backend advisory_history `memo` VARCHAR(500) 과 같다 */
export const REASON_MAX_LENGTH = 500

/** 안내 제목 최대 길이. backend advisory `title` VARCHAR(100) 과 같다(넘으면 `ADVISORY_106`) */
export const TITLE_MAX_LENGTH = 100

/**
 * 타임라인 단계 (시안: 후보 등록 → AI 초안 생성 → 운영자 수정 → 승인·발행).
 * 정정은 이전 안내에 `corrected`, 새 안내에 `correction-published` 가 남는다
 */
export type TimelineStep =
  | 'registered'
  | 'ai-drafted'
  | 'edited'
  | 'published'
  | 'held'
  | 'corrected'
  | 'correction-published'
  | 'retracted'

export const TIMELINE_STEP_LABEL: Readonly<Record<TimelineStep, string>> = {
  registered: '후보 등록',
  'ai-drafted': 'AI 초안 생성',
  edited: '운영자 수정',
  published: '승인·발행',
  held: '보류',
  corrected: '정정됨',
  'correction-published': '정정 발행',
  retracted: '발행 철회',
}

export type TimelineEvent = Readonly<{
  step: TimelineStep
  /** 전이 시각(ISO) — advisory_history `created_at` */
  at: string
  /**
   * 세부 문구. 정정 · 철회 사유(`memo`)는 백엔드에서 나온다. 그 밖(시안의 `기준선 9% 대비 +9%p · 표본 128명` ·
   * `"유행" 표현 삭제 · 행동 문장 1개 추가`)은 1단계 이력에서 만들 수 없어 선택 값이다
   */
  note?: string
}>

export type HistoryEntry = Readonly<{
  /** 안내 id (보류는 후보 id) */
  id: string
  districtCode: string
  districtName: string
  /** 근거 집계 주 (ISO 주 `YYYY-Www`) */
  isoWeek: string
  /** 인용 참여자 수 (advisory `cited_participant_count`) */
  participants: number
  /** 인용 증상 보고 비율(%) */
  symptomRate: number
  outcome: HistoryOutcome
  /** 발행 · 처리 시각(ISO). 정정 · 철회돼도 처음 발행 시각이다 */
  processedAt: string
  /** 운영자 수정(EDITED)을 거쳐 발행했는지 — `수정 후 발행` · 수정 후 발행 비율 */
  edited: boolean
  /** 안내 제목 (advisory `title`). 정정 발행은 새 제목을 함께 보낸다(필수) */
  title: string
  /** 발행 본문 (보류는 그때의 초안) */
  body: string
  /** 동시 수정 확인 값 (advisory `@Version`). 정정 · 철회에 함께 보내고, 다르면 409 */
  version: number
  /** 전이 이력. 오래된 것부터 */
  timeline: readonly TimelineEvent[]
  /**
   * 이 안내가 정정한 이전 안내와 정정일 · 사유. 정정일 · 사유는 동네 안내(S07)의 정정 이력과 같은 모양이다. 사유는 운영자가 늘 적는다
   * (공개 응답은 사유를 내리지 않아 S07 쪽은 선택 값이다)
   */
  correction?: NoticeCorrection & { previousId: string; reason: string }
  /** 철회일(`YYYY-MM-DD`, 한국 날짜) · 사유 */
  retraction?: { retractedOn: string; reason: string }

  // ── 백엔드 1단계에 없는 값 — 없으면 그 칸 · 문구를 숨긴다 ──────────────────────────────
  /** 후보 유형. 신호는 검토 후보 조회 때 계산하고 안내에 남기지 않아 이력 응답에 없다(#267) — 목에만 있다 */
  kind?: CandidateKind
  /** 담당 표시 이름 (`운영자 A`). 백엔드는 `operator_id` 뿐이라 이름의 출처가 없다 */
  operatorName?: string
  /** 검토 시간(분). 검토 시작 시각을 저장하지 않는다 */
  reviewMinutes?: number
}>
