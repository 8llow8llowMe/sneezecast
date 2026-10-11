import { classifyApiError } from '@/lib/api/error-kind'
import type { DataSource } from '@/lib/data-source'
import { isoWeekMonday, kstIsoWeek, parseIsoWeek } from '@/lib/iso-week'
import { refreshSession } from '@/lib/session/session-store'

import { fetchPastReports } from './report-api'
import type { ReportAnswer } from './types'

/* ── 지난 보고 내역 (S10 `/me/reports`, #194 · 연동 #251) ─────────────────────────────────────────
 *
 * 계약은 백엔드 #216 의 `GET /api/v1/reports/me`(이번 주를 뺀 지난 보고, 최근 주부터 — docs/api-contract-draft.md "지난 보고 내역")다.
 * 이번 주 보고는 화면이 이번 주 보고 저장소(`use-submitted-report.ts`)에서 따로 읽는다.
 *
 * - 실데이터: `fetchPastReports`(`report-api.ts`). 실패 · 옮길 수 없는 응답은 거부한다 — 화면은 "불러오지 못했어요" + `다시 시도` 다.
 *   권한 없음(`SECURITY_006`)이면 이번 주 보고(`current-report.ts`)와 같게 세션 요약을 다시 맞춘다(`refreshSession`)
 * - 목: 지금 KST 주의 앞 주들 예시(`MOCK_PAST_ANSWERS`). 보고하지 않은 주가 하나 끼어 있다. `?mock-reports=empty` 면 빈 목록이다
 *
 * 보고 내용은 브라우저 저장소 · 주소 · 로그에 남기지 않는다.
 */

/** 개별 보고 보관 기간(주). 확정 값이다 (docs/design/auth/README.md, 건강정보 동의 고지와 같다) */
export const REPORT_RETENTION_WEEKS = 52

/** 목 재현 쿼리. `empty` 면 지난 보고가 없다. 목데이터 모드에서만 쓴다 */
export const MOCK_REPORTS_PARAM = 'mock-reports'

/** 지난 보고 하나. 같은 주는 하나뿐이다(고쳐 보내면 마지막 보고만 남는다) */
export type PastReport = Readonly<{
  /** ISO 주 (`2026-W40`, KST) */
  isoWeek: string
  answer: ReportAnswer
}>

/** 지난 주부터 거꾸로 본 목 예시. null 은 보고하지 않은 주다 */
const MOCK_PAST_ANSWERS: readonly (ReportAnswer | null)[] = [
  { kind: 'none' },
  { kind: 'symptom', symptoms: ['respiratory'] },
  { kind: 'none' },
  null,
  { kind: 'symptom', symptoms: ['respiratory', 'gastrointestinal'] },
  { kind: 'none' },
]

const WEEK_MS = 7 * 86_400_000

/** `isoWeek` 의 `weeks` 주 앞 ISO 주 */
function weeksBefore(isoWeek: string, weeks: number): string {
  const parsed = parseIsoWeek(isoWeek)
  if (!parsed) throw new Error(`unreadable week: ${isoWeek}`)
  // 월요일 UTC 자정은 KST 로 같은 월요일 09:00 이라 같은 주다
  return kstIsoWeek(new Date(isoWeekMonday(parsed).getTime() - weeks * WEEK_MS))
}

/**
 * 이번 주를 뺀 지난 보고(최근 주부터). `now` 는 목의 지금 시각(테스트가 정한다), `empty` 는 목 빈 목록 재현이다 — 실데이터는 둘 다
 * 보지 않는다(이번 주는 서버가 정한다). 실패하면 거부한다
 */
export function listPastReports(
  source: DataSource,
  { now = new Date(), empty = false }: { now?: Date; empty?: boolean } = {},
): Promise<readonly PastReport[]> {
  if (source === 'api') {
    return fetchPastReports().catch((error: unknown) => {
      // 권한 없음(동의 철회 직후 등)이면 이번 주 보고와 같게 요약을 다시 맞춘다 — 보고할 수 없다고 바뀌면 화면이 내 정보로 돌아간다
      if (classifyApiError(error) === 'forbidden') void refreshSession()
      throw error
    })
  }
  if (empty) return Promise.resolve([])
  const thisWeek = kstIsoWeek(now)
  const reports: PastReport[] = []
  MOCK_PAST_ANSWERS.forEach((answer, index) => {
    if (answer) reports.push({ isoWeek: weeksBefore(thisWeek, index + 1), answer })
  })
  return Promise.resolve(reports)
}
