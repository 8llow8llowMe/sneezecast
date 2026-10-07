import type { MeasuredStatus } from '@/lib/status'

/**
 * 홈 화면 데이터. API(`GET /api/regions/{admCd}/weekly` 등, docs/api-contract-draft.md)를 화면용으로
 * 옮긴 모양이다. 날짜 · 주차 같은 문구는 API 연동 때 매핑 계층이 만든다 — 지금은 목 데이터가 채운다.
 *
 * **`자료 부족` 이면 증상 비율 · 기준선 · 증상별 변화가 타입에 아예 없다.** 화면 코드가 실수로
 * 수치를 그릴 수 없게 하려는 것이다 (루트 CLAUDE.md "표본이 적으면 수치 · 색상으로 위험을 암시하지 않는다").
 */
export type HomeWeekly = HomeCommon & (MeasuredWeek | InsufficientWeek)

type HomeCommon = {
  regionName: string
  /** 예: "11월 3주" */
  weekLabel: string
  /** 집계 기간. 예: "11월 17일~23일" (판단 기준 · 보고 확인) */
  weekRangeLabel: string
  /** 보고 기간. 예: "11월 17일(월)~23일(일)" (보고 시작) */
  reportPeriodLabel: string
  /** 예: "오늘 09:00 갱신" */
  updatedLabel: string
  /** 이번 주 보고한 사람 수 (건강한 보고 포함 — 지표의 분모) */
  participants: number
  /** 수치를 공개하는 참여 기준. 백엔드가 내려 준다 (시범 운영 초기값 100명) */
  publicThreshold: number
  notice: PublishedNotice | null
  official: OfficialSummary
}

export type MeasuredWeek = {
  status: MeasuredStatus
  /** 상태 카드 큰 글자 아래 한 문장. 예: "발열·기침 보고가 지난 4주보다 많이 늘었어요" */
  summary: string
  /** 참여자 대비 증상 보고 비율 (%) */
  symptomRate: number
  /** 지난 4주 평균 증상 보고 비율 (%) */
  baselineRate: number
  /** 이번 주 증상을 보고한 사람 수 */
  symptomReports: number
  groups: SymptomGroup[]
  /** 판정 기준. 기준선보다 몇 %p 높으면 조금 · 많이로 보는지. 백엔드가 판정에 쓴 값을 그대로 내려 준다 */
  thresholds: { slightDeltaPp: number; highDeltaPp: number }
}

/** 수치가 있는 주의 홈 데이터. 판단 기준 시트는 이 경우에만 연다 */
export type MeasuredHomeWeekly = Extract<HomeWeekly, MeasuredWeek>

export type InsufficientWeek = {
  status: 'insufficient'
  summary: string
}

export type SymptomGroupKey = 'respiratory' | 'gastrointestinal'

/** 지난주 대비 변화. 늘어난 경우만 상태색을 쓴다 */
export type GroupTrend = 'down' | 'flat' | 'slight' | 'high'

export type SymptomGroup = {
  key: SymptomGroupKey
  trend: GroupTrend
  /** 최근 주별 증상 보고 비율 (%), 오래된 주부터. 마지막 값이 이번 주다 */
  series: number[]
}

/**
 * 운영자가 검토 · 발행한 이번 주 동네 안내. 발행 전이면 null 이다. **철회된 안내도 null 이다**(#225) — 이 모양에는 철회 표시가 없어
 * 홈 안내 섹션 · 지도의 `이 동네 안내 보기` 가 철회된 안내를 발행 중인 안내로 요약할 수 없다. 연동 때 매핑 계층은 발행 중인 안내만
 * 여기에 옮긴다. 철회 사실은 동네 안내 화면(S07, `features/notice/types.ts` 의 `retraction`)에서만 보인다
 */
export type PublishedNotice = {
  /** 예: "11월 18일 발행" */
  publishedLabel: string
  items: string[]
  /** 근거. 예: "질병관리청 예방수칙" */
  source: string
  href: string
}

/**
 * 질병관리청 공식 정보 요약. 시민 자가보고와 **한 UI 요소에 섞지 않는다** — 화면에서 항상 따로 그린다.
 */
export type OfficialSummary = {
  /** 예: "전국 인플루엔자 유행주의보" */
  headline: string
  /** 예: "질병관리청 · 전국 · 주간 발표 기준" */
  sourceLine: string
  /** 예: "인플루엔자" */
  disease: string
  /** 예: "유행주의보 · 전국" */
  stage: string
  /** 예: "질병관리청 주간 표본감시" */
  basis: string
  href: string
}
