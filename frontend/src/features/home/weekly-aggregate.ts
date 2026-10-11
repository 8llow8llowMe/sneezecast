import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import { formatMonthDayTime } from '@/lib/format'
import {
  formatIsoWeekOfMonth,
  formatIsoWeekRange,
  formatIsoWeekReportPeriod,
  kstIsoWeek,
} from '@/lib/iso-week'
import type { RegionStatus } from '@/lib/status'

import type {
  GroupTrend,
  HomeWeekly,
  PublishedNotice,
  SymptomGroup,
  SymptomGroupKey,
} from './types'

/*
 * 행정동 주간 집계(docs/api-contract-draft.md "행정동 주간 집계", 백엔드 #212)를 홈 화면 모델(`HomeWeekly`)로 옮긴다.
 * 지도(`GET /weekly-aggregates`)도 동네 집계 모양이 같아 `toHomeWeekly` 를 함께 쓸 수 있다.
 *
 * - **실데이터만 부른다.** 목데이터는 서버 페이지가 `pickHomeMock`(`?mock=`)으로 고른 값을 그대로 넘긴다(`app/(home)/page.tsx`).
 * - 공개 API 라 `auth: false` 로 보낸다(만료된 access 로 게이트웨이가 거절하지 않게 — 행정동 API 와 같다).
 * - **응답에 없는 값은 지어내지 않는다**: 증상군 주별 비율(`series`) · 안내 할 일 목록 · 근거 · 공식 정보 요약은 비운다(`types.ts`).
 * - 자료 부족(`INSUFFICIENT`)이면 서버가 수치 키를 빼고 보내고, 화면 모델도 수치 필드가 없는 갈래다. 이유(`insufficientReason`)는
 *   셋 모두 같은 `자료 부족` 으로 그린다(이유를 문구로 가르지 않는다).
 */

/** `{ code, name, description }` — 단계 · 자료 부족 이유 · 증상군 · 변화 */
type CodeName = { code: string; name: string; description: string }

/** 동네 집계 한 칸. `INSUFFICIENT` 면 `symptomaticCount` · `symptomRate` · `baselineRate` · `groups` 키가 없다 */
export type DistrictAggregateResponse = {
  code: string
  name: string
  sigungu: string
  active: boolean
  level: CodeName
  insufficientReason?: CodeName
  participantCount: number
  calculatedAt?: string
  symptomaticCount?: number
  symptomRate?: number
  baselineRate?: number
  groups?: { group: CodeName; rate: number; trend?: CodeName }[]
  publishedAdvisory?: { isoWeek: string; title: string; publishedAt: string }
}

/** 홈 · 지도 응답이 함께 갖는 주 정보와 판정 설정값 */
export type AggregateWeekResponse = {
  isoWeek: string
  finalized: boolean
  publicThreshold: number
  slightDeltaPp: number
  highDeltaPp: number
}

/** `GET /api/v1/districts/{code}/weekly-aggregate` */
type DistrictWeeklyAggregateResponse = AggregateWeekResponse & {
  district: DistrictAggregateResponse
}

const DISTRICTS_PATH = '/api/v1/districts'

/** 행정동 코드 형식 — SGIS 숫자 8자리. 아니면 서버가 400(`AGGREGATE_101`)이라 보내지 않는다 */
const DISTRICT_CODE_PATTERN = /^\d{8}$/

/** 없는 코드 (404) */
const AGGREGATE_NOT_FOUND = 'AGGREGATE_001'

const LEVEL_STATUS: Readonly<Record<string, RegionStatus>> = {
  NORMAL: 'normal',
  SLIGHT: 'slight',
  HIGH: 'high',
  INSUFFICIENT: 'insufficient',
}

const GROUP_KEY: Readonly<Record<string, SymptomGroupKey>> = {
  RESPIRATORY: 'respiratory',
  ENTERIC: 'gastrointestinal',
}

const TREND: Readonly<Record<string, GroupTrend>> = {
  DOWN: 'down',
  FLAT: 'flat',
  SLIGHT: 'slight',
  HIGH: 'high',
}

/**
 * 상태 카드 큰 글자 아래 한 문장. 단계는 지난 4주 기준선과 비교한 판정이라 그 말로 적는다. 증상군을 문장에 넣지 않는다 —
 * 어느 증상군이 판정을 끌었는지 응답이 알려 주지 않는다. 자료 부족은 이유(참여 부족 · 급변 · 기준선 부족)와 무관하게 맞는 말이다
 */
export const AGGREGATE_SUMMARY: Readonly<Record<RegionStatus, string>> = {
  normal: '지난 4주와 비슷한 수준이에요',
  slight: '증상 보고가 지난 4주보다 조금 늘었어요',
  high: '증상 보고가 지난 4주보다 많이 늘었어요',
  insufficient: '아직 변화를 판단할 자료가 부족해요',
}

/** 응답 모양이 계약과 다를 때. 화면은 불러오지 못함으로 다룬다(지어낸 값으로 채우지 않는다) */
function malformed(what: string): Error {
  return new Error(`행정동 주간 집계 응답이 계약과 다르다: ${what}`)
}

/**
 * 동네 집계 한 칸을 홈 화면 모델로. `now` 는 갱신 시각을 `오늘 09:00 갱신` 으로 적을지 정한다(한국 날짜 기준).
 * 모르는 단계 · 주 형식, 수치 단계인데 수치 키가 없으면 던진다. 모르는 증상군 · 변화 코드는 그 줄 · 그 글자만 뺀다(보이기만 하고 되돌려
 * 보내지 않는 값이라 버려도 서버 자료가 바뀌지 않는다).
 */
export function toHomeWeekly(
  week: AggregateWeekResponse,
  district: DistrictAggregateResponse,
  now: Date,
): HomeWeekly {
  const status = LEVEL_STATUS[district.level.code]
  if (!status) throw malformed(`level ${district.level.code}`)
  const labels = weekLabels(week.isoWeek)
  if (!labels) throw malformed(`isoWeek ${week.isoWeek}`)

  const common = {
    regionName: district.name,
    ...labels,
    updatedLabel: updatedLabel(district.calculatedAt, now),
    participants: district.participantCount,
    publicThreshold: week.publicThreshold,
    notice: publishedNotice(district),
    official: null,
  }
  if (status === 'insufficient') {
    return { ...common, status, summary: AGGREGATE_SUMMARY.insufficient }
  }

  const { symptomaticCount, symptomRate, baselineRate, groups } = district
  if (
    symptomaticCount === undefined ||
    symptomRate === undefined ||
    baselineRate === undefined ||
    groups === undefined
  ) {
    throw malformed(`${district.level.code} 인데 수치 키가 없다`)
  }
  return {
    ...common,
    status,
    summary: AGGREGATE_SUMMARY[status],
    symptomRate,
    baselineRate,
    symptomReports: symptomaticCount,
    groups: groups.flatMap(toSymptomGroup),
    thresholds: { slightDeltaPp: week.slightDeltaPp, highDeltaPp: week.highDeltaPp },
  }
}

function toSymptomGroup(
  item: NonNullable<DistrictAggregateResponse['groups']>[number],
): SymptomGroup[] {
  const key = GROUP_KEY[item.group.code]
  if (!key) return []
  const trend = item.trend ? TREND[item.trend.code] : undefined
  const group: SymptomGroup = trend ? { key, rate: item.rate, trend } : { key, rate: item.rate }
  return [group]
}

/** 주차 · 집계 기간 · 보고 기간 문구. 읽을 수 없는 주면 null */
export function weekLabels(
  isoWeek: string,
): { weekLabel: string; weekRangeLabel: string; reportPeriodLabel: string } | null {
  const weekLabel = formatIsoWeekOfMonth(isoWeek)
  const weekRangeLabel = formatIsoWeekRange(isoWeek)
  const reportPeriodLabel = formatIsoWeekReportPeriod(isoWeek)
  if (!weekLabel || !weekRangeLabel || !reportPeriodLabel) return null
  return { weekLabel, weekRangeLabel, reportPeriodLabel }
}

/**
 * 집계를 아직 받지 못했을 때(읽는 중 · 실패) 보고 흐름이 쓸 이번 주 문구. 보고 주를 정하는 서버와 같은 정의(KST ISO 주)로 지금 주를
 * 적는다 — 보고는 집계와 무관하게 할 수 있어야 하고, 저장되는 주는 여전히 서버가 정한다(일요일 자정 근처에는 어긋날 수 있다)
 */
export function currentReportLabels(now: Date): {
  weekRangeLabel: string
  reportPeriodLabel: string
} {
  const labels = weekLabels(kstIsoWeek(now))
  // kstIsoWeek 는 늘 읽을 수 있는 주를 준다
  return {
    weekRangeLabel: labels?.weekRangeLabel ?? '',
    reportPeriodLabel: labels?.reportPeriodLabel ?? '',
  }
}

/** `calculatedAt` → `오늘 09:00 갱신` · `11월 20일 09:00 갱신`(한국 시각). 집계 행이 없으면(보고 0) `아직 집계 전` */
function updatedLabel(calculatedAt: string | undefined, now: Date): string {
  const at = calculatedAt ? formatMonthDayTime(calculatedAt) : null
  if (!at) return '아직 집계 전'
  const day = at.slice(0, at.lastIndexOf(' '))
  const time = at.slice(at.lastIndexOf(' ') + 1)
  return formatMonthDayTime(now)?.startsWith(`${day} `) ? `오늘 ${time} 갱신` : `${at} 갱신`
}

/**
 * 발행 중인 안내 요약. 키가 없으면(발행 전 · 철회 · 오래된 주) null. 동네 안내 화면으로 가는 주소(`href`)는 두지 않는다 —
 * 그 화면의 안내 내용이 아직 목이다(본문 연동 #256 에서 근거 주 `isoWeek` 로 연다)
 */
function publishedNotice(district: DistrictAggregateResponse): PublishedNotice | null {
  const advisory = district.publishedAdvisory
  if (!advisory) return null
  const at = formatMonthDayTime(advisory.publishedAt)
  return {
    publishedLabel: at ? `${at.slice(0, at.lastIndexOf(' '))} 발행` : '발행',
    title: advisory.title,
  }
}

/**
 * 동네 하나의 이번 주(현재 주). 형식이 틀린 코드(요청하지 않는다) · 없는 코드(404 `AGGREGATE_001`) · 폐지된 코드(`active: false` —
 * 둘러보기 동네는 지금 있는 동네만 받는다)면 null 이다 — 화면은 동네를 고르라고 안내한다. 그 밖의 실패(일시 장애 · 응답 모양이 다름)는 거부한다 — 화면은 불러오지 못함 + 다시 시도다.
 */
export async function fetchHomeWeekly(
  code: string,
  now: () => Date = () => new Date(),
): Promise<HomeWeekly | null> {
  if (!DISTRICT_CODE_PATTERN.test(code)) return null
  let response: DistrictWeeklyAggregateResponse
  try {
    response = await apiRequest<DistrictWeeklyAggregateResponse>(
      `${DISTRICTS_PATH}/${code}/weekly-aggregate`,
      { auth: false },
    )
  } catch (error) {
    if (error instanceof ApiError && error.code === AGGREGATE_NOT_FOUND) return null
    throw error
  }
  if (!response.district.active) return null
  return toHomeWeekly(response, response.district, now())
}
