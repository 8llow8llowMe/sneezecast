import type {
  ReviewCandidateAdvisoryResponse,
  ReviewCandidateItemResponse,
  ReviewCandidateListResponse,
} from '@/features/admin/admin-review-api'
import type { ReviewCandidate } from '@/features/admin/types'

/**
 * 목 후보를 처리 요청에 싣는 값(초안 · 버전)이 있는 모양으로 좁힌다. 화면 모델은 안내가 없는 후보(실데이터)를 위해 둘 다 선택 값이지만
 * 목 후보는 늘 있다 — 없으면 테스트가 잘못 고른 것이라 던진다
 */
export function processable(candidate: ReviewCandidate | undefined) {
  if (candidate?.version === undefined || candidate.draft === undefined) {
    throw new Error('처리할 수 있는 목 후보가 아니다')
  }
  return { ...candidate, version: candidate.version, draft: candidate.draft }
}

/* 운영자 검토 후보 API(#266) 응답을 만드는 테스트 도구. 값은 모두 예시다 — 안내 상세(`operatorAdvisory`)와 같은 가락1동(11240660) 2025-W46 */

export const CANDIDATE_WEEK = '2025-W46'

/** enum 메타데이터. 매핑은 `code` 만 본다 */
export function codeName(value: string) {
  return { code: value, name: value, description: '' }
}

/** 후보 동네의 안내(ID · 상태 · 버전) */
export function candidateAdvisory(
  status: string,
  advisoryId = '701',
  version = 3,
): ReviewCandidateAdvisoryResponse {
  return { advisoryId, status: codeName(status), version }
}

/** 수치가 있는 후보 한 행. 기본은 기준선 변화(HIGH) · 초안을 만들 수 있음 · 안내 없음 */
export function candidateItem(
  overrides: Partial<ReviewCandidateItemResponse> = {},
): ReviewCandidateItemResponse {
  return {
    districtCode: '11240660',
    districtName: '가락1동',
    sigungu: '서울특별시 송파구',
    active: true,
    signals: [codeName('BASELINE')],
    level: codeName('HIGH'),
    insufficientReason: null,
    draftable: true,
    participantCount: 150,
    previousParticipantCount: 110,
    revisedReportCount: 12,
    symptomaticCount: 30,
    symptomRate: 20,
    baselineRate: 10,
    baselineDeltaPp: 10,
    groups: [
      { group: codeName('RESPIRATORY'), count: 20, rate: 13.3 },
      { group: codeName('ENTERIC'), count: 12, rate: 8 },
    ],
    advisory: null,
    ...overrides,
  }
}

/** 후보 목록 응답 */
export function candidateListResponse(
  candidates: ReviewCandidateItemResponse[],
  isoWeek = CANDIDATE_WEEK,
): ReviewCandidateListResponse {
  return { isoWeek, finalized: true, surgeChangePercent: 30, repeatRevisedPercent: 20, candidates }
}
