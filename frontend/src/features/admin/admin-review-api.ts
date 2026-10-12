import { GROUP_KEY, LEVEL_STATUS } from '@/features/home/weekly-aggregate'
import type { ReportSymptom } from '@/features/report/types'

import type {
  AdvisoryHistoryRowResponse,
  CodeName,
  OperatorAdvisoryResponse,
} from './admin-history-api'
import type {
  CandidateKind,
  CandidateState,
  InsufficientReason,
  ReviewAdvisoryStatus,
  ReviewCandidate,
  ReviewCandidateList,
} from './types'

/*
 * 운영자 검토 후보(docs/api-contract-draft.md "운영자 검토 후보", 백엔드 #266)와 안내 상세("운영자 안내 처리" 의 `GET /{advisoryId}`, #246)의
 * 응답 모양과 화면 모델(`ReviewCandidate`)로 옮기는 순수 함수(연동 #272). 요청은 `admin-review-client.ts` 가 보낸다.
 *
 * **응답에 없는 값은 지어내지 않는다**: 예방수칙(`guides`) · 최근 비율 · 몰림 · 같은 기기 · 신규 참여 · 검토 시작은 비워 둔다 —
 * 화면이 그 칸 · 행을 숨긴다. 초안 · 제목은 목록에 없어 상세를 읽어야 있다(`withAdvisoryDetail`).
 */

/** 후보 동네 · 주의 안내 하나(backend `AdvisoryReviewCandidateAdvisoryItem`) — 상세 · 처리 요청에 쓸 ID · 상태 · 버전만 */
export type ReviewCandidateAdvisoryResponse = {
  advisoryId: string
  /** DRAFT · AI_DRAFTED · EDITED · APPROVED · PUBLISHED · RETRACTED */
  status: CodeName
  version: number
}

/**
 * 검토 후보 동네 하나(backend `AdvisoryReviewCandidateItem`). **자료 부족이면 `?` 키가 없다**(0 · null 로도 싣지 않는다).
 * 그 밖에 값이 없는 키는 null 이다
 */
export type ReviewCandidateItemResponse = {
  districtCode: string
  /** 행정동 행이 없으면 null */
  districtName: string | null
  sigungu: string | null
  active: boolean
  /** BASELINE → SURGE → REPEAT 순서. 신호 없이 그 주에 안내가 있어 후보가 되면 빈 배열 */
  signals: CodeName[]
  /** NORMAL · SLIGHT · HIGH · INSUFFICIENT */
  level: CodeName
  /** LOW_SAMPLE · UNSTABLE · NO_BASELINE. 자료 부족이 아니면 null */
  insufficientReason: CodeName | null
  draftable: boolean
  participantCount: number
  /** 전주 집계 행이 없으면 null */
  previousParticipantCount: number | null
  revisedReportCount: number
  symptomaticCount?: number
  symptomRate?: number
  baselineRate?: number
  baselineDeltaPp?: number
  /** 호흡기 → 장관 */
  groups?: { group: CodeName; count: number; rate: number }[]
  /** 발행 중 > 검토 중(가장 최근) > 가장 최근 철회 순으로 하나. 없으면 null */
  advisory: ReviewCandidateAdvisoryResponse | null
}

/** `GET /api/v1/advisories/admin/candidates?week=` 의 `dataBody` (backend `AdvisoryReviewCandidateListResponse`) */
export type ReviewCandidateListResponse = {
  isoWeek: string
  finalized: boolean
  surgeChangePercent: number
  repeatRevisedPercent: number
  candidates: ReviewCandidateItemResponse[]
}

/** `GET /api/v1/advisories/admin/{advisoryId}` 의 `dataBody` (backend `AdvisoryOperatorDetailResponse`). 이력은 오래된 것부터 */
export type OperatorAdvisoryDetailResponse = {
  advisory: OperatorAdvisoryResponse
  histories: AdvisoryHistoryRowResponse[]
}

/** 신호 코드 → 유형. 대표 유형을 고르는 우선순위 순서이기도 하다(기준선 변화 → 참여 급증 → 반복 보고 의심) */
const SIGNAL_KIND: ReadonlyMap<string, CandidateKind> = new Map([
  ['BASELINE', 'baseline'],
  ['SURGE', 'surge'],
  ['REPEAT', 'repeat'],
])
const KIND_ORDER: readonly CandidateKind[] = [...SIGNAL_KIND.values()]

const INSUFFICIENT_REASON: Readonly<Record<string, InsufficientReason>> = {
  LOW_SAMPLE: 'low-sample',
  UNSTABLE: 'unstable',
  NO_BASELINE: 'no-baseline',
}

const REVIEW_ADVISORY_STATUSES: readonly ReviewAdvisoryStatus[] = [
  'DRAFT',
  'AI_DRAFTED',
  'EDITED',
  'APPROVED',
]

/** 처리할 수 있는(검토 중) 안내 상태면 그 값. 아니면 undefined */
function reviewAdvisoryStatus(code: string): ReviewAdvisoryStatus | undefined {
  return REVIEW_ADVISORY_STATUSES.find((status) => status === code)
}

/**
 * 안내 상태 → 후보 상태. 발행 중은 목록에서 빠진다(`published`). 철회된 안내는 고칠 수 없어 안내가 없는 것과 같은 `대기` 다.
 * 모르는 상태면 undefined
 */
function advisoryState(code: string): CandidateState | 'published' | undefined {
  if (reviewAdvisoryStatus(code)) return 'reviewing'
  if (code === 'RETRACTED') return 'waiting'
  if (code === 'PUBLISHED') return 'published'
  return undefined
}

/** 아는 신호만 우선순위 순서로. 모르는 신호는 뺀다 */
function signalKinds(signals: readonly CodeName[]): CandidateKind[] {
  const known = new Set(signals.map((signal) => SIGNAL_KIND.get(signal.code)))
  return KIND_ORDER.filter((kind) => known.has(kind))
}

/**
 * 보고 수가 가장 많은 증상군. **가장 큰 수가 같은 증상군이 둘 이상이면 고르지 않는다** — 한쪽을 `가장 많이 보고된` 으로 보이면
 * 사실과 다르다. 가장 큰 수가 0 이거나 증상군이 없으면(자료 부족) 없다. 모르는 증상군은 세지 않는다
 */
function leadingSymptomOf(
  groups: ReviewCandidateItemResponse['groups'],
): ReportSymptom | undefined {
  let leading: ReportSymptom | undefined
  let top = 0
  let tied = false
  for (const item of groups ?? []) {
    const key = GROUP_KEY[item.group.code]
    if (!key) continue
    if (item.count > top) {
      leading = key
      top = item.count
      tied = false
    } else if (item.count === top && top > 0) {
      tied = true
    }
  }
  return tied ? undefined : leading
}

/**
 * 후보 하나를 화면 모델로 옮긴다. 후보 키는 동네 · 주다(응답에 후보 ID 가 없다).
 * 발행 중인 안내가 있거나(목록에서 뺀다) 안내 상태 · 단계를 모르면(계약과 다름) null 이다 — 부르는 쪽이 그 후보만 뺀다
 * (한 행 때문에 운영자 화면 전체를 막지 않는다 — 발행 이력과 같다)
 */
export function toReviewCandidate(
  item: ReviewCandidateItemResponse,
  isoWeek: string,
): ReviewCandidate | null {
  const level = LEVEL_STATUS[item.level.code]
  if (!level) return null
  const state = item.advisory ? advisoryState(item.advisory.status.code) : 'waiting'
  if (state === undefined || state === 'published') return null
  // 처리할 안내는 검토 중일 때만 싣는다 — 철회된 안내는 고칠 수 없어 초안 만들기부터다
  const processable = state === 'reviewing' ? item.advisory : null
  const advisoryStatus = processable ? reviewAdvisoryStatus(processable.status.code) : undefined

  const signals = signalKinds(item.signals)
  const kind = signals[0]
  const insufficientReason = item.insufficientReason
    ? INSUFFICIENT_REASON[item.insufficientReason.code]
    : undefined
  const leadingSymptom = leadingSymptomOf(item.groups)

  return {
    id: `${item.districtCode}:${isoWeek}`,
    ...(kind && { kind }),
    signals,
    districtCode: item.districtCode,
    // 행정동 행이 없으면(이름을 모름) 코드로 보인다 — 이름을 지어내지 않는다
    districtName: item.districtName ?? item.districtCode,
    ...(item.sigungu !== null && { sigungu: item.sigungu }),
    active: item.active,
    isoWeek,
    level,
    ...(insufficientReason && { insufficientReason }),
    draftable: item.draftable,
    participants: item.participantCount,
    ...(item.previousParticipantCount !== null && {
      previousParticipants: item.previousParticipantCount,
    }),
    revisedReports: item.revisedReportCount,
    ...(item.symptomaticCount !== undefined && { symptomReports: item.symptomaticCount }),
    ...(item.symptomRate !== undefined && { symptomRate: item.symptomRate }),
    ...(item.baselineRate !== undefined && { baselineRate: item.baselineRate }),
    ...(item.baselineDeltaPp !== undefined && { baselineDeltaPp: item.baselineDeltaPp }),
    ...(leadingSymptom && { leadingSymptom }),
    state,
    ...(processable &&
      advisoryStatus && {
        advisoryId: processable.advisoryId,
        advisoryStatus,
        version: processable.version,
      }),
  }
}

/** 후보 목록 응답을 화면 묶음으로 옮긴다. 후보는 서버 순서이고, 발행 중이거나 옮길 수 없는 후보는 뺀다 */
export function toReviewCandidateList(response: ReviewCandidateListResponse): ReviewCandidateList {
  return {
    isoWeek: response.isoWeek,
    finalized: response.finalized,
    criteria: {
      surgeChangePercent: response.surgeChangePercent,
      repeatRevisedPercent: response.repeatRevisedPercent,
    },
    candidates: response.candidates.flatMap((item) => {
      const candidate = toReviewCandidate(item, response.isoWeek)
      return candidate ? [candidate] : []
    }),
  }
}

/** 응답 모양이 계약과 다를 때. 화면은 불러오지 못함으로 다룬다 */
function malformed(what: string): Error {
  return new Error(`운영자 안내 상세 응답이 계약과 다르다: ${what}`)
}

/**
 * 안내 상세로 후보의 제목 · 초안 · 버전 · 상태를 덮는다(후보 목록에는 제목 · 본문이 없다). 처리 응답(안내 한 건)도 같은 규칙으로 덮는다 —
 * 부르는 쪽이 `{ advisory, histories: [] }` 로 싼다(`admin-review-client.ts`).
 * - 검토 중(DRAFT~APPROVED): 상세의 ID · 안내 상태 · 버전 · 제목 · 본문을 싣고 `검토 중` 이다
 * - 그사이 발행됐으면 null — 목록에서 뺀다
 * - 철회됐으면 `대기` 로 돌리고 안내 값(ID · 버전 · 제목 · 초안)을 지운다 — 초안 만들기부터다
 *
 * 다른 동네 · 주의 안내이거나, 후보가 처리할 안내 ID 를 갖고 있는데 다른 안내이거나, 모르는 상태면 계약과 달라 거부한다
 * (지어낸 값으로 채우지 않는다)
 */
export function withAdvisoryDetail(
  candidate: ReviewCandidate,
  detail: OperatorAdvisoryDetailResponse,
): ReviewCandidate | null {
  const { advisory } = detail
  if (advisory.districtCode !== candidate.districtCode || advisory.isoWeek !== candidate.isoWeek) {
    throw malformed(`다른 동네 · 주의 안내 (${advisory.districtCode} · ${advisory.isoWeek})`)
  }
  if (candidate.advisoryId !== undefined && advisory.advisoryId !== candidate.advisoryId) {
    throw malformed(`다른 안내 (${advisory.advisoryId}, 후보는 ${candidate.advisoryId})`)
  }
  const state = advisoryState(advisory.status.code)
  if (state === undefined) throw malformed(`모르는 상태 ${advisory.status.code}`)
  if (state === 'published') return null
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 안내 값을 지운 나머지 후보 값만 남긴다
  const { advisoryId, advisoryStatus, version, title, draft, ...rest } = candidate
  const status = reviewAdvisoryStatus(advisory.status.code)
  if (state !== 'reviewing' || status === undefined) return { ...rest, state }
  return {
    ...rest,
    state,
    advisoryId: advisory.advisoryId,
    advisoryStatus: status,
    version: advisory.version,
    title: advisory.title,
    draft: advisory.body,
  }
}
