import { apiRequest } from '@/lib/api/client'
import { parseIsoWeek } from '@/lib/iso-week'

import type { ReportAnswer, ReportSymptom, SubmittedReport } from './types'

/* ── 주간 보고 API (`/api/v1/reports/current`, 실데이터) ─────────────────────────────────────
 *
 * 본인의 **이번 주** 보고 하나를 쓰고 · 읽고 · 지운다(docs/api-contract-draft.md "주간 보고"). 세 요청 모두 access 를 싣고
 * 건강정보 동의(scope `report:write`)가 있어야 한다 — 없으면 `SECURITY_006`(403)이다.
 *
 * - **보고 주는 보내지 않는다.** 서버가 받은 시각(KST)으로 ISO 주를 정한다. 같은 주는 `PUT` 하나로 처음 보내기 · 고치기를 함께 한다
 * - 응답은 화면 모델(`SubmittedReport`)로 옮긴다. 회원 · 보고 식별자는 응답에 없다
 *
 * 요청을 언제 보낼지 · 결과를 어디 둘지 · 오류를 어떻게 해석할지는 `report-client.ts`(보내기 · 되돌리기)와 이번 주 보고 저장소
 * (`current-report.ts`)가 정한다. 증상은 민감정보라 요청 · 응답을 로그 · 브라우저 저장소에 남기지 않는다.
 */

export const CURRENT_REPORT_PATH = '/api/v1/reports/current'

/** backend `SymptomGroup` 이름 */
type SymptomGroupCode = 'RESPIRATORY' | 'ENTERIC'

const GROUP_BY_SYMPTOM: Readonly<Record<ReportSymptom, SymptomGroupCode>> = {
  respiratory: 'RESPIRATORY',
  gastrointestinal: 'ENTERIC',
}

const SYMPTOM_BY_GROUP: Readonly<Record<SymptomGroupCode, ReportSymptom>> = {
  RESPIRATORY: 'respiratory',
  ENTERIC: 'gastrointestinal',
}

/** `PUT` · `GET /api/v1/reports/current` 의 `dataBody` (backend `WeeklyReportResponse`) */
type WeeklyReportResponse = {
  /** 예: `2026-W40` (KST 달력 기준). 서버가 정한다 */
  isoWeek: string
  districtCode: string
  /** 선언 순서(호흡기 → 장관). 비었으면 증상 없음 */
  symptomGroups: { code: string; name?: string; description?: string }[]
  /** 이번 주 첫 보고 시각 (ISO-8601 UTC) */
  reportedAt: string
  /** 마지막 수정 시각 (ISO-8601 UTC) */
  updatedAt: string
}

/**
 * 응답을 화면 모델로 옮길 수 없다(모르는 증상군 코드 · 읽을 수 없는 시각). 계약이 어긋난 것이라 실패로 다룬다 —
 * 부른 쪽은 다른 실패와 같게 본다(저장소는 `failed`, 보내기는 "보내지 못했어요")
 */
export class ReportResponseError extends Error {
  override readonly name = 'ReportResponseError'
}

/** 증상 없음은 빈 배열이다. 같은 증상군을 두 번 보내면 서버가 거절해(`REPORT_105`) 한 번만 싣는다 */
export function toSymptomGroups(answer: ReportAnswer): SymptomGroupCode[] {
  if (answer.kind === 'none') return []
  return [...new Set(answer.symptoms.map((symptom) => GROUP_BY_SYMPTOM[symptom]))]
}

/** 브라우저 시간대와 무관하게 KST 달력 날짜로 읽는다 */
const KST_DATE = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  month: 'numeric',
  day: 'numeric',
})

/** UTC 시각(ISO-8601)을 KST 날짜 "10월 2일" 로. 읽을 수 없으면 `ReportResponseError` */
export function kstDateLabel(instant: string): string {
  const date = new Date(instant)
  if (Number.isNaN(date.getTime())) throw new ReportResponseError('unreadable report time')
  const parts = KST_DATE.formatToParts(date)
  const month = parts.find((part) => part.type === 'month')?.value
  const day = parts.find((part) => part.type === 'day')?.value
  if (!month || !day) throw new ReportResponseError('unreadable report time')
  return `${month}월 ${day}일`
}

/** 받은 이번 주 보고. 화면 모델과 함께 서버가 정한 주 · 처음 저장인지를 싣는다 */
export type ReceivedReport = Readonly<{
  report: SubmittedReport
  /** 서버가 정한 보고 주 (`2026-W40`, KST). 받아 둔 보고가 지금 주의 것인지 볼 때 쓴다(`current-report.ts`) */
  isoWeek: string
  /**
   * 이번 주 첫 저장인지 — `reportedAt === updatedAt`(고친 적이 없음). `PUT` 응답에서 되돌리기를 줄지 정한다(`report-flow.tsx`):
   * 이 탭이 몰랐던 보고(다른 기기 · 탭에서 보냄)를 고친 것이면 false 라, 되돌리기로 그 보고까지 지우지 않는다.
   * **한계: 서버 시각이 초 단위라** 첫 저장과 같은 초 안에 고친 보고도 true 로 보인다
   */
  firstSubmission: boolean
}>

/**
 * 응답을 받은 보고로. **모르는 증상군 코드는 버리지 않고 `ReportResponseError` 로 거부한다** — 버리면 증상 보고가 다른 증상 · 증상 없음으로
 * 보이고, 그대로 고쳐 보내면 서버의 증상이 지워진다. 실패로 두면 화면은 "불러오지 못했어요" 로 알리고 보고 버튼이 완료로 보이지 않는다.
 * `reportedLabel` 은 첫 보고 시각(`reportedAt`)의 KST 날짜다(고쳐도 바뀌지 않는다)
 */
function toReceivedReport(response: WeeklyReportResponse): ReceivedReport {
  const symptoms: ReportSymptom[] = []
  for (const group of response.symptomGroups) {
    const symptom = Object.hasOwn(SYMPTOM_BY_GROUP, group.code)
      ? SYMPTOM_BY_GROUP[group.code as SymptomGroupCode]
      : undefined
    if (!symptom) throw new ReportResponseError('unknown symptom group')
    if (!symptoms.includes(symptom)) symptoms.push(symptom)
  }
  const [first, ...rest] = symptoms
  const answer: ReportAnswer = first
    ? { kind: 'symptom', symptoms: [first, ...rest] }
    : { kind: 'none' }
  if (!parseIsoWeek(response.isoWeek)) throw new ReportResponseError('unreadable report week')
  const reportedAt = Date.parse(response.reportedAt)
  const updatedAt = Date.parse(response.updatedAt)
  if (Number.isNaN(updatedAt)) throw new ReportResponseError('unreadable report time')
  return Object.freeze({
    report: Object.freeze({ answer, reportedLabel: kstDateLabel(response.reportedAt) }),
    isoWeek: response.isoWeek,
    firstSubmission: reportedAt === updatedAt,
  })
}

/** 이번 주 보고를 보낸다(처음 · 고치기 모두). 보고 동네는 **코드만** 보낸다. 실패는 거부한다 */
export async function putCurrentReport(
  districtCode: string,
  answer: ReportAnswer,
): Promise<ReceivedReport> {
  return toReceivedReport(
    await apiRequest<WeeklyReportResponse>(CURRENT_REPORT_PATH, {
      method: 'PUT',
      body: { districtCode, symptomGroups: toSymptomGroups(answer) },
    }),
  )
}

/** 이번 주 보고. 아직 보내지 않았으면 null 이다(200 + `dataBody: null`). 실패는 거부한다 */
export async function fetchCurrentReport(): Promise<ReceivedReport | null> {
  const report = await apiRequest<WeeklyReportResponse | null>(CURRENT_REPORT_PATH)
  return report ? toReceivedReport(report) : null
}

/** 이번 주 보고를 지운다(멱등 — 없어도 성공). 실패는 거부한다 */
export async function deleteCurrentReport(): Promise<void> {
  await apiRequest<null>(CURRENT_REPORT_PATH, { method: 'DELETE' })
}
