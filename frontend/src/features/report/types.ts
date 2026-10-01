/**
 * 주간 건강 보고. 성인 본인이 주 1회 "증상 없음" 또는 증상군을 보고한다 (루트 CLAUDE.md "보고와 집계").
 * 이름 · 주소 · 위치 · 자유 서술은 받지 않는다 — 고르는 값만 있다.
 */

/** 보고 증상군. `other` 는 "그 외 증상만 있었어요" 로, 다른 둘과 함께 고를 수 없다 */
export type ReportSymptom = 'respiratory' | 'gastrointestinal' | 'other'

export type ReportAnswer =
  { kind: 'none' } | { kind: 'symptom'; symptoms: readonly [ReportSymptom, ...ReportSymptom[]] }

/** 이번 주에 보낸 보고. 같은 주에 고쳐 보내면 마지막 보고만 집계된다 */
export type SubmittedReport = {
  answer: ReportAnswer
  /** 예: "11월 19일" — 수정 안내 문구에 쓴다 */
  reportedLabel: string
}

/** 보고 흐름 단계. 주소 `?report=` 값과 같다 */
export const REPORT_STEPS = ['start', 'symptom', 'confirm', 'done'] as const
export type ReportStep = (typeof REPORT_STEPS)[number]

/** 보고 흐름이 화면에 보이는 이번 주 정보 */
export type ReportWeek = {
  regionName: string
  /** 예: "11월 17일~23일" */
  weekRangeLabel: string
  /** 예: "11월 17일(월)~23일(일)" */
  reportPeriodLabel: string
}
