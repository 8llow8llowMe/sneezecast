import { formatMonthDay, formatMonthDayTime } from '@/lib/format'

import type { OfficialProgramView, OfficialReport, OfficialRow } from './types'

/* ── 공식 정보 응답 → 화면 모델 ──────────────────────────────────────────────────────────────────────
 *
 * `GET /api/v1/official/latest` 의 `dataBody`(docs/api-contract-draft.md "공식 정보")를 화면 모델(`OfficialReport`)로 옮긴다.
 * 서버는 적재된 계열을 모두 주고 **무엇을 몇 개 보일지는 화면이 고른다.** 고르는 규칙(docs/design/SCREENS.md "공식 정보 (S08)"):
 *
 * - 프로그램: 표본감시 `INFLUENZA_ILI` · `ARI` · `ENTERIC` 만 응답 순서대로 묶음 하나씩. 전수신고(`NOTIFIABLE`)와 모르는 프로그램은
 *   보이지 않는다 — 전수신고는 감염병 67개 안팎이라 무엇을 보일지 기획 결정이 필요하다.
 * - 계열: 집계 단위 전국(`NATION`)만. 그중 **기준 주 하나(질병관리청 주차)** 의 계열만 한 묶음에 넣는다 — 한 묶음 안에서
 *   다른 주의 값을 나란히 놓거나 순위를 매기지 않는다. 기준 주는 급성호흡기 · 장관이면 합계(`TOTAL`)가 있는 가장 최근 주,
 *   그 밖(합계가 없으면 포함)은 가장 최근 주다. 그 주 계열의 지표(`metric`)와 다른 지표의 계열도 뺀다. 고른 주의 계열이 그 프로그램의
 *   전국 계열보다 적으면(일부만 집계된 주) "이 주는 일부만 집계됐어요." 를 붙인다.
 * - 줄: 인플루엔자 분율은 연령 전체(`ALL`) 값이 없어 연령대별 값 7개를 응답 순서대로. 급성호흡기 · 장관은 연령 전체 값으로
 *   합계(`TOTAL`, 원천 표기 "계")와 신고 수가 많은 병원체 `TOP_PATHOGEN_COUNT` 개(값이 큰 순, 빈 칸은 뒤, 같으면 응답 순서).
 * - 값은 원값 그대로다(공공누리 제4유형). 빈 칸(`null`)은 0 이 아니라 "값 없음" 이다. 전주 대비 같은 파생값을 만들지 않는다.
 * - 받은 시각은 고른 줄의 `syncedAt` 중 가장 늦은 것이다 — 발표일이 아니다.
 *
 * 보일 묶음이 하나도 없으면 `empty` 다 — 자료가 없으면(`{ programs: [] }`) `none`, 받았지만 보일 묶음이 없으면(전수신고만 등) `unshown`.
 */

/** 화면에 보이는 표본감시 프로그램 (응답 `program.code`) */
const SHOWN_PROGRAMS: ReadonlySet<string> = new Set(['INFLUENZA_ILI', 'ARI', 'ENTERIC'])

/** 연령대별 값으로 보이는 프로그램 — 연령 전체(`ALL`) 값이 없다 */
const AGE_PROGRAM = 'INFLUENZA_ILI'

/** 급성호흡기 · 장관에서 합계 밖에 보일 병원체 수 */
export const TOP_PATHOGEN_COUNT = 3

/** 합계 열의 감염병 키 (backend `SentinelPathogenCatalog.TOTAL_KEY`) */
const TOTAL_KEY = 'TOTAL'

/** 합계 줄의 이름. 원천 표기 "계" 는 홀로 읽기 어려워 바꿔 적는다(값은 그대로) */
const TOTAL_LABEL = '합계'

const ALL_AGES = 'ALL'
const NATION = 'NATION'
const CASE_COUNT = 'CASE_COUNT'

/** 원천이 빈 칸인 값. 0 과 다르다 */
export const MISSING_VALUE_LABEL = '값 없음'

type CodeName = { code: string; name: string; description?: string }

type OfficialValueItem = { ageGroup: CodeName; value: number | null }

type OfficialDiseaseItem = {
  diseaseKey: string
  diseaseName: string
  diseaseGroup: string | null
  metric: CodeName
  regionLevel: CodeName
  regionCode: string
  regionName: string
  periodYear: number
  periodWeek: number
  /** `YYYY-MM-DD` 일요일 */
  periodStart: string
  /** `YYYY-MM-DD` 토요일 */
  periodEnd: string
  /** 우리가 마지막으로 적재한 시각 (ISO-8601 UTC). 발표일이 아니다 */
  syncedAt: string
  values: OfficialValueItem[]
}

type OfficialProgramItem = {
  program: CodeName
  source: CodeName
  sourceUrl: string | null
  diseases: OfficialDiseaseItem[]
}

/** `GET /api/v1/official/latest` 의 `dataBody` (backend `OfficialLatestResponse`) */
export type OfficialLatestResponse = { programs: OfficialProgramItem[] }

/** 응답을 화면 모델로 옮길 수 없다(`programs` 가 배열이 아님). 계약이 어긋난 것이라 실패로 다룬다 — 페이지의 오류 경계가 받는다 */
export class OfficialResponseError extends Error {
  override readonly name = 'OfficialResponseError'
}

export function toOfficialReport(response: OfficialLatestResponse): OfficialReport {
  // 서버 응답의 모양은 API 계층이 검사하지 않는다 — 계약이 어긋났으면 빈 화면이 아니라 실패로 보인다
  const programs: unknown = (response as Partial<OfficialLatestResponse> | null)?.programs
  if (!Array.isArray(programs)) {
    throw new OfficialResponseError('공식 정보 응답에 programs 배열이 없다')
  }
  const views = (programs as OfficialProgramItem[]).flatMap((item) => {
    const view = toProgramView(item)
    return view ? [view] : []
  })
  if (views.length > 0) return { status: 'published', programs: views }
  // 자료가 아예 없는지(우리 적재 전), 받았지만 이 화면이 보이지 않는 자료(전수신고 등)뿐인지 나눈다 — 화면 문구가 다르다
  return { status: 'empty', reason: programs.length === 0 ? 'none' : 'unshown' }
}

function toProgramView(item: OfficialProgramItem): OfficialProgramView | null {
  if (!SHOWN_PROGRAMS.has(item.program.code)) return null
  const isAge = item.program.code === AGE_PROGRAM
  const nation = (item.diseases ?? []).filter((disease) => disease.regionLevel.code === NATION)
  // 급성호흡기 · 장관은 합계가 있는 가장 최근 주를 기준 주로 고른다 — `집계 중` 칸을 batch 가 건너뛰어 최신 주가 일부만 오면
  // 합계 없이 몇 병원체만 남는다. 합계가 없으면 가장 최근 주다
  const totals = isAge ? [] : nation.filter((disease) => disease.diseaseKey === TOTAL_KEY)
  const anchor = latestWeekDisease(totals) ?? latestWeekDisease(nation)
  if (!anchor) return null
  const lines = nation.filter(
    (disease) =>
      disease.periodYear === anchor.periodYear &&
      disease.periodWeek === anchor.periodWeek &&
      disease.metric.code === anchor.metric.code,
  )
  const picked = isAge ? pickAgeRows(lines) : pickPathogenRows(lines)
  if (picked.rows.length === 0) return null
  // 고른 주의 계열(연령대별 값 하나가 한 계열)이 이 프로그램의 전국 계열보다 적으면 일부만 집계된 주다
  const partial = seriesCount(lines) < seriesCount(nation)
  const notes = [...(partial ? ['이 주는 일부만 집계됐어요.'] : []), ...picked.selection]
  if (notes.length > 0 && item.sourceUrl) notes.push('나머지는 원문에서 볼 수 있어요.')
  return {
    code: item.program.code,
    name: item.program.name,
    sourceName: item.source.name,
    regionLabel: anchor.regionName,
    weekLabel: `${anchor.periodYear}년 ${anchor.periodWeek}주`,
    periodLabel: formatPeriod(anchor.periodStart, anchor.periodEnd),
    syncedLabel: latestSynced(picked.used),
    metricLabel: anchor.metric.name,
    rows: picked.rows,
    note: notes.length > 0 ? notes.join(' ') : null,
    sourceUrl: item.sourceUrl ?? null,
  }
}

/** 가장 최근 기준 주의 첫 계열. 같은 주면 응답 순서(감염병 키 순)의 앞 계열이다 */
function latestWeekDisease(diseases: OfficialDiseaseItem[]): OfficialDiseaseItem | null {
  let latest: OfficialDiseaseItem | null = null
  for (const disease of diseases) {
    if (
      !latest ||
      disease.periodYear > latest.periodYear ||
      (disease.periodYear === latest.periodYear && disease.periodWeek > latest.periodWeek)
    ) {
      latest = disease
    }
  }
  return latest
}

function seriesCount(diseases: OfficialDiseaseItem[]): number {
  return diseases.reduce((count, disease) => count + (disease.values ?? []).length, 0)
}

type Picked = { rows: OfficialRow[]; used: OfficialDiseaseItem[]; selection: string[] }

/** 인플루엔자 분율: 첫 계열의 연령대별 값을 응답 순서대로 */
function pickAgeRows(lines: OfficialDiseaseItem[]): Picked {
  const [disease] = lines
  if (!disease) return { rows: [], used: [], selection: [] }
  const rows = (disease.values ?? []).map(({ ageGroup, value }) => ({
    label: ageGroup.name,
    value: formatValue(value, disease.metric.code),
  }))
  return { rows, used: [disease], selection: [] }
}

/** 급성호흡기 · 장관: 연령 전체 값으로 합계 + 값이 큰 병원체 `TOP_PATHOGEN_COUNT` 개 */
function pickPathogenRows(lines: OfficialDiseaseItem[]): Picked {
  const withAll = lines.flatMap((disease) => {
    const all = (disease.values ?? []).find((value) => value.ageGroup.code === ALL_AGES)
    return all ? [{ disease, value: all.value ?? null }] : []
  })
  const total = withAll.find(({ disease }) => disease.diseaseKey === TOTAL_KEY)
  const pathogens = withAll.filter(({ disease }) => disease.diseaseKey !== TOTAL_KEY)
  // 정렬은 안정적이라 값이 같으면 응답 순서(감염병 키 순)다. 빈 칸은 0 이 아니라 뒤로 보낸다
  const top = [...pathogens]
    .sort((a, b) => (b.value ?? Number.NEGATIVE_INFINITY) - (a.value ?? Number.NEGATIVE_INFINITY))
    .slice(0, TOP_PATHOGEN_COUNT)
  const chosen = [...(total ? [total] : []), ...top]
  const rows = chosen.map(({ disease, value }) => ({
    label: disease.diseaseKey === TOTAL_KEY ? TOTAL_LABEL : disease.diseaseName,
    value: formatValue(value, disease.metric.code),
  }))
  const shown = `신고 수가 많은 병원체 ${TOP_PATHOGEN_COUNT}개`
  const selection =
    pathogens.length <= TOP_PATHOGEN_COUNT
      ? []
      : [total ? `합계와 ${shown}만 보여요.` : `${shown}만 보여요.`]
  return { rows, used: chosen.map(({ disease }) => disease), selection }
}

/** 원값 그대로 (소수 둘째 자리까지, 천 단위 쉼표). 발생 수는 "건" 을 붙인다. 빈 칸은 "값 없음" */
export function formatValue(value: number | null | undefined, metricCode: string): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return MISSING_VALUE_LABEL
  const number = value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
  return metricCode === CASE_COUNT ? `${number}건` : number
}

/** 기준 주 기간. 같은 달이면 "9월 13일~19일", 달이 바뀌면 "9월 27일~10월 3일". 날짜를 읽을 수 없으면 null */
export function formatPeriod(start: string, end: string): string | null {
  const from = formatMonthDay(start)
  const to = formatMonthDay(end)
  if (!from || !to) return null
  if (start.slice(0, 7) === end.slice(0, 7)) return `${from}~${Number(end.slice(8))}일`
  return `${from}~${to}`
}

/** 고른 줄 중 가장 늦게 받은 시각 (한국 시각 "9월 25일 06:00"). 읽을 수 없으면 null */
function latestSynced(diseases: OfficialDiseaseItem[]): string | null {
  const times = diseases
    .map(({ syncedAt }) => Date.parse(syncedAt))
    .filter((time) => !Number.isNaN(time))
  return times.length > 0 ? formatMonthDayTime(new Date(Math.max(...times))) : null
}
