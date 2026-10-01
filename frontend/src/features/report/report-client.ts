import type { ReportAnswer, SubmittedReport } from './types'

/**
 * 보고 보내기 · 고치기 · 되돌리기. **API 연동 전 목 구현이다.**
 *
 * 연동 이슈에서 `src/lib/api/` 를 거쳐 다음으로 바꾼다 (docs/api-contract-draft.md).
 * - 처음 보내기 → `POST /api/reports`
 * - 같은 주 고치기 → `PUT /api/reports/{week}`
 * - 되돌리기 → `DELETE /api/reports/{week}`
 *
 * 함수 모양(Promise)을 지금부터 맞춰 두어 화면 코드가 연동 때 바뀌지 않게 한다.
 * 보고 내용은 브라우저 저장소에 남기지 않는다 — 건강 정보는 민감정보다 (루트 CLAUDE.md "개인정보").
 */

/** 오늘 날짜를 "11월 19일" 형식으로 */
function todayLabel(now = new Date()): string {
  return `${now.getMonth() + 1}월 ${now.getDate()}일`
}

export function submitReport(answer: ReportAnswer): Promise<SubmittedReport> {
  return Promise.resolve({ answer, reportedLabel: todayLabel() })
}

export function updateReport(answer: ReportAnswer): Promise<SubmittedReport> {
  return Promise.resolve({ answer, reportedLabel: todayLabel() })
}

export function cancelReport(): Promise<void> {
  return Promise.resolve()
}
