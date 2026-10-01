import type { SymptomGroup } from '@/features/home/types'
import type { MeasuredStatus } from '@/lib/status'

/**
 * S07 동네 안내 화면 데이터. API(`GET /api/notices/{admCd}?week=`, docs/api-contract-draft.md)를 화면용으로 옮긴 모양이다.
 *
 * **발행된 안내는 수치가 있는 주에만 있다** — 백엔드도 `자료 부족` 집계로는 안내 초안을 만들지 않는다
 * (`backend/docs/entity-design.md` advisory "초안 생성 전제"). 타입으로 막아 `자료 부족` 주에 안내를 그릴 수 없게 한다.
 * `자료 부족` 이면 증상 비율 · 기준선 · 증상별 변화가 타입에 아예 없다(홈과 같은 규칙).
 */
export type RegionNotice = RegionNoticeCommon & RegionNoticeBody

/** 안내와 수치. 안내가 있으면 수치가 있는 주다 */
export type RegionNoticeBody =
  | { notice: PublishedRegionNotice; stats: MeasuredNoticeStats }
  | { notice: null; stats: MeasuredNoticeStats | InsufficientNoticeStats }

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
 * 운영자가 검토 · 발행한 안내. 수치(`stats`)는 운영자가 검토할 때 인용한 값이다 — 집계가 다시 계산돼도 안내의 수치는 바뀌지 않는다
 * (백엔드 advisory `cited_*`).
 */
export type PublishedRegionNotice = {
  /** 제목. 예: "발열·기침 보고가 지난 4주보다 많이 늘었어요" (AI 초안을 운영자가 검토한 문장) */
  title: string
  /** 발행일 (`YYYY-MM-DD`, 한국 날짜) */
  publishedOn: string
  /** 예방 행동. 순서대로 번호를 붙인다 */
  items: string[]
  /** 근거. 예: "질병관리청 예방수칙" */
  source: string
  /** 정정 이력. **최근 것이 먼저**다. 정정이 없으면 빈 목록이다 */
  corrections: NoticeCorrection[]
}

export type NoticeCorrection = {
  /** 정정일 (`YYYY-MM-DD`, 한국 날짜) */
  correctedOn: string
  /** 무엇을 왜 바로잡았는지. 예: "중복 보고를 제외해 참여자 수를 131명에서 128명으로 바로잡았어요. 안내 내용은 같아요." */
  reason: string
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
