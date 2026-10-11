import type { SymptomGroup } from '@/features/home/types'
import type { MeasuredStatus } from '@/lib/status'

/**
 * S07 동네 안내 화면 데이터. API(`GET /api/v1/advisories?districtCode=&week=`, docs/api-contract-draft.md "동네 안내" — 백엔드 #215,
 * 연동 #256)와 같은 동네 · 주의 행정동 주간 집계를 화면용으로 옮긴 모양이다(`advisory-api.ts`).
 *
 * **발행된 안내는 수치가 있는 주에만 있다** — 백엔드도 `자료 부족` 집계로는 안내 초안을 만들지 않는다
 * (`backend/docs/entity-design.md` advisory "초안 생성 전제"). 타입으로 막아 `자료 부족` 주에 안내를 그릴 수 없게 한다.
 * `자료 부족` 이면 증상 비율 · 기준선 · 증상별 변화가 타입에 아예 없다(홈과 같은 규칙).
 */
export type RegionNotice = RegionNoticeCommon & RegionNoticeBody

/**
 * 안내와 수치. 안내가 있으면 수치가 있는 주다.
 *
 * **철회된 안내는 발행된 안내(`notice`)가 아니다** — 안내가 없는 갈래에 철회 표시(`retraction`)만 둔다(#225). 철회된 안내의
 * 제목 · 본문 · 인용 수치는 타입에 아예 없어, 화면이 내린 안내를 다시 퍼뜨리거나 `data.notice` 만 보는 곳이 철회된 안내를
 * 발행 중인 안내로 그릴 수 없다. 안내가 없는 갈래의 `stats` 는 그 주 집계(시민 자가보고)이고 철회된 안내가 인용한 값이 아니다 —
 * 그래서 `자료 부족` 일 수도 있다(안내 API 는 집계를 싣지 않아 행정동 주간 집계에서 읽는다 — docs/api-contract-draft.md "동네 안내").
 */
export type RegionNoticeBody =
  | { notice: PublishedRegionNotice; retraction: null; stats: MeasuredNoticeStats }
  | {
      notice: null
      /** 이 동네 · 주의 안내를 운영자가 철회했으면 철회 표시, 처음부터 안내가 없었으면 null 이다 */
      retraction: NoticeRetraction | null
      stats: MeasuredNoticeStats | InsufficientNoticeStats
    }

type RegionNoticeCommon = {
  /** 행정동 코드 (SGIS 8자리) */
  regionCode: string
  regionName: string
  /** 기준 주 (ISO 주 `YYYY-Www`). 화면이 기간 · 주차 문구를 만든다 */
  isoWeek: string
  /** 질병관리청 공식 정보 화면(S08). 시민 자가보고와 섞지 않고 따로 잇는다 */
  officialHref: string
}

/**
 * 운영자가 검토 · 발행한 안내. 수치(`stats`)의 참여 · 증상 보고 비율 · 증상군 비율 · 단계는 운영자가 검토할 때 인용한 값이다 —
 * 집계가 다시 계산돼도 안내의 수치는 바뀌지 않는다(백엔드 advisory `cited_*`). 지난 4주 평균 · 증상군 변화는 인용값이 아니라 같은 주
 * 집계에서 읽는다(안내가 있는 주는 마감 주라 그 값도 바뀌지 않는다).
 *
 * 시안의 할 일 목록 · `근거:` 는 없다 — 백엔드 안내는 제목 · 본문 한 덩어리다(목록 · 근거를 따로 둘지는 운영자 화면 #246 과 함께 정한다).
 */
export type PublishedRegionNotice = {
  /** 제목. 예: "발열·기침 보고가 지난 4주보다 많이 늘었어요" (AI 초안을 운영자가 검토한 문장) */
  title: string
  /** 발행일 (`YYYY-MM-DD`, 한국 날짜) */
  publishedOn: string
  /** 본문 (AI 초안을 운영자가 검토 · 승인한 문장). 화면은 줄바꿈을 지킨 문단 하나로 그린다 */
  body: string
  /** 정정 이력. **최근 것이 먼저**다. 정정이 없으면 빈 목록이다 */
  corrections: NoticeCorrection[]
}

/**
 * 정정 표시. 사유는 선택 값이다 — 백엔드 #215 는 정정 사유(advisory_history `memo`)를 공개 응답에 내리지 않는다(철회 사유와 같다).
 * 없으면 화면이 사유 줄을 뺀다. 운영자 발행 이력(`features/admin/types.ts`)은 사유를 늘 채운다
 */
export type NoticeCorrection = {
  /** 정정일 (`YYYY-MM-DD`, 한국 날짜) */
  correctedOn: string
  /** 무엇을 왜 바로잡았는지. 예: "중복 보고를 제외해 참여자 수를 131명에서 128명으로 바로잡았어요. 안내 내용은 같아요." */
  reason?: string
}

/**
 * 철회 표시. 운영자가 발행한 안내를 내렸다(백엔드 advisory PUBLISHED → RETRACTED, `retracted_at` — 끝 상태).
 * 정정 표시(`NoticeCorrection`)와 같은 모양이고 사유만 선택 값이다 — 백엔드 #215 는 철회 사유(advisory_history `memo`)를 공개
 * 응답에 내리지 않는다(docs/api-contract-draft.md "동네 안내" 결정). 없으면 화면이 사유 줄을 뺀다
 */
export type NoticeRetraction = {
  /** 철회일 (`YYYY-MM-DD`, 한국 날짜) */
  retractedOn: string
  /** 왜 내렸는지. 예: "공식 자료와 달라 안내를 내렸어요." */
  reason?: string
}

export type MeasuredNoticeStats = {
  status: MeasuredStatus
  /** 이번 주 보고한 사람 수 (건강한 보고 포함 — 지표의 분모) */
  participants: number
  /** 참여자 대비 증상 보고 비율 (%) */
  symptomRate: number
  /** 지난 4주 평균 증상 보고 비율 (%) */
  baselineRate: number
  groups: SymptomGroup[]
}

export type InsufficientNoticeStats = {
  status: 'insufficient'
  participants: number
  /** 수치를 공개하는 참여 기준. 백엔드가 내려 준다 (시범 운영 초기값 100명) */
  publicThreshold: number
}
