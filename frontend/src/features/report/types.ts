/**
 * 주간 건강 보고. 성인 본인이 주 1회 "증상 없음" 또는 증상군을 보고한다 (루트 CLAUDE.md "보고와 집계").
 * 이름 · 주소 · 위치 · 자유 서술은 받지 않는다 — 고르는 값만 있다.
 */

/** 보고 증상군. 백엔드 `SymptomGroup`(호흡기 · 장관)과 같다 */
export type ReportSymptom = 'respiratory' | 'gastrointestinal'

export type ReportAnswer =
  { kind: 'none' } | { kind: 'symptom'; symptoms: readonly [ReportSymptom, ...ReportSymptom[]] }

/** 이번 주에 보낸 보고. 같은 주에 고쳐 보내면 마지막 보고만 집계된다 */
export type SubmittedReport = {
  answer: ReportAnswer
  /** 예: "11월 19일" — 수정 안내 문구에 쓴다 */
  reportedLabel: string
}

/**
 * 보고 흐름을 여는 주소 쿼리 (docs/design/SCREENS.md S05 `/?report=start`).
 * 보고 흐름(`report-flow.tsx`)이 아니라 이 파일에 둔다 — 쿼리 이름만 쓰는 화면(지도 · 공식 정보 · 내 정보의 보고 링크)이
 * 보고 흐름 · 공유 시트 코드를 첫 로드에 끌어오지 않게 한다(#184)
 */
export const REPORT_PARAM = 'report'

/**
 * 보고 흐름 단계. 주소 `?report=` 값과 같다.
 * `share` 는 완료 화면 위에 연 함께 채우기 공유 시트다(#151) — 완료 다음 단계라 같은 쿼리에 둔다(`report-flow.tsx`)
 */
export const REPORT_STEPS = ['start', 'symptom', 'confirm', 'done', 'share'] as const
export type ReportStep = (typeof REPORT_STEPS)[number]

/** 보고 흐름이 화면에 보이는 이번 주 정보 */
export type ReportWeek = {
  regionName: string
  /** 예: "11월 17일~23일" */
  weekRangeLabel: string
  /** 예: "11월 17일(월)~23일(일)" */
  reportPeriodLabel: string
}
