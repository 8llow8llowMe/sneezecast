import type {
  AdvisoryHistoryRowResponse,
  OperatorAdvisoryResponse,
  PublicationHistoryItemResponse,
  PublicationHistoryResponse,
} from '@/features/admin/admin-history-api'

/* 운영자 발행 이력 API(#267) 응답을 만드는 테스트 도구. 값은 모두 예시다 */

/** enum 메타데이터. 매핑은 `code` 만 본다 */
function status(code: string) {
  return { code, name: code, description: '' }
}

/** 이력 한 행. `from` 이 null 이면 생성이다 */
export function historyRow(
  from: string | null,
  to: string,
  createdAt: string,
  memo: string | null = null,
): AdvisoryHistoryRowResponse {
  return {
    historyId: `h-${to}-${createdAt}`,
    fromStatus: from === null ? null : status(from),
    toStatus: status(to),
    title: '제목',
    body: '본문',
    operatorId: '9001',
    aiModel: null,
    promptVersion: null,
    memo,
    createdAt,
  }
}

/** 운영자 안내 한 건. 기본은 ○○1동 2025-W46 의 발행 중인 안내(참여 128명 중 23명 — 18%)다 */
export function operatorAdvisory(
  overrides: Partial<OperatorAdvisoryResponse> & { statusCode?: string } = {},
): OperatorAdvisoryResponse {
  const { statusCode = 'PUBLISHED', ...rest } = overrides
  return {
    advisoryId: '701',
    districtCode: '11240660',
    isoWeek: '2025-W46',
    status: status(statusCode),
    title: '발열·기침 보고가 지난 4주보다 많이 늘었어요',
    body: '손을 자주 씻어 주세요.',
    citation: {
      level: { code: 'HIGH', name: '많이 늘었어요', description: '' },
      participantCount: 128,
      symptomaticCount: 23,
      respiratoryCount: 20,
      entericCount: 5,
      ruleVersion: '2026-10-08',
    },
    correctsAdvisoryId: null,
    publishedAt: '2025-11-11T05:08:00Z',
    retractedAt: null,
    version: 4,
    createdAt: '2025-11-11T05:02:00Z',
    updatedAt: '2025-11-11T05:08:00Z',
    ...rest,
  }
}

/** 초안 → 수정 → 승인 → 발행을 거친 이력(2025-11-11 14:02~14:08 KST) */
export const PUBLISHED_ROWS: readonly AdvisoryHistoryRowResponse[] = [
  historyRow(null, 'DRAFT', '2025-11-11T05:02:00Z'),
  historyRow('DRAFT', 'EDITED', '2025-11-11T05:06:00Z', '문장 다듬기'),
  historyRow('EDITED', 'APPROVED', '2025-11-11T05:07:00Z'),
  historyRow('APPROVED', 'PUBLISHED', '2025-11-11T05:08:00Z'),
]

export function historyItem(
  overrides: Partial<PublicationHistoryItemResponse> = {},
): PublicationHistoryItemResponse {
  return {
    advisory: operatorAdvisory(),
    districtName: '가락1동',
    sigungu: '서울특별시 송파구',
    correctedByAdvisoryId: null,
    histories: [...PUBLISHED_ROWS],
    ...overrides,
  }
}

/** `GET /history` 응답(끝 주 2025-W46 로 끝나는 4주) */
export function historyResponse(
  entries: PublicationHistoryItemResponse[] = [historyItem()],
): PublicationHistoryResponse {
  return { fromIsoWeek: '2025-W43', toIsoWeek: '2025-W46', entries }
}

/**
 * 정정 뒤의 이력 — 701 을 702 로 정정했다(2025-11-20 14:08 KST, 사유 `reason`). 새 안내가 먼저다(서버 순서)
 */
export function correctedHistory(reason = '참여자 수를 바로잡았어요.'): PublicationHistoryResponse {
  const at = '2025-11-20T05:08:00Z'
  return historyResponse([
    historyItem({
      advisory: operatorAdvisory({
        advisoryId: '702',
        title: '고친 제목',
        body: '고친 본문',
        correctsAdvisoryId: '701',
        publishedAt: at,
        version: 1,
        createdAt: at,
        updatedAt: at,
      }),
      histories: [
        historyRow(null, 'APPROVED', at, reason),
        historyRow('APPROVED', 'PUBLISHED', at),
      ],
    }),
    historyItem({
      advisory: operatorAdvisory({ statusCode: 'RETRACTED', retractedAt: at, version: 5 }),
      correctedByAdvisoryId: '702',
      histories: [...PUBLISHED_ROWS, historyRow('PUBLISHED', 'RETRACTED', at, reason)],
    }),
  ])
}
