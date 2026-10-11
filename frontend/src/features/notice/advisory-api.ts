import type { HomeWeekly, SymptomGroup } from '@/features/home/types'
import {
  DISTRICT_CODE_PATTERN,
  DISTRICTS_PATH,
  type DistrictWeeklyAggregateResponse,
  GROUP_KEY,
  LEVEL_STATUS,
  toHomeWeekly,
} from '@/features/home/weekly-aggregate'
import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import type {
  InsufficientNoticeStats,
  MeasuredNoticeStats,
  NoticeCorrection,
  PublishedRegionNotice,
  RegionNoticeBody,
} from './types'

/*
 * 동네 안내(docs/api-contract-draft.md "동네 안내", 백엔드 #215 · 연동 #256)를 S07 화면 모델(`RegionNoticeBody`)로 옮긴다.
 *
 * - 안내 API 는 집계를 싣지 않아 **같은 동네 · 주의 행정동 주간 집계를 함께 읽는다**(두 요청을 동시에 보낸다). 발행 중인 안내의 수치는
 *   인용값(`citation`)이고 지난 4주 평균 · 증상군 변화만 집계에서 읽는다. 안내 없음 · 철회의 수치는 그 주 집계다(자료 부족일 수 있다).
 * - 공개 API 라 둘 다 `auth: false` 로 보낸다(행정동 · 홈 집계와 같다).
 * - **응답에 없는 값은 지어내지 않는다**: 할 일 목록 · 근거 · 정정 사유 · 철회 사유는 없다. 본문은 받은 그대로 둔다.
 */

/** `{ code, name, description }` — 단계 · 증상군 */
type CodeName = { code: string; name: string; description: string }

/** 운영자가 검토할 때 고정한 인용 수치(advisory `cited_*`). `level` 에 `INSUFFICIENT` 는 없다 */
type AdvisoryCitationResponse = {
  level: CodeName
  participantCount: number
  symptomaticCount: number
  symptomRate: number
  groups: { group: CodeName; count: number; rate: number }[]
}

/** `GET /api/v1/advisories?districtCode=&week=` 의 `dataBody`. 안내가 없으면 `dataBody` 자체가 null 이다 */
export type RegionAdvisoryResponse = {
  districtCode: string
  isoWeek: string
  advisory: {
    title: string
    body: string
    publishedAt: string
    citation: AdvisoryCitationResponse
    /** 정정 시각. 최근 것부터 */
    corrections: { correctedAt: string }[]
  } | null
  retraction: { retractedAt: string } | null
}

export const ADVISORIES_PATH = '/api/v1/advisories'

/**
 * 이 동네 · 주를 보일 수 없는 안내 API 오류 — 없는 코드(`008`), 조회할 수 없는 주(`009` — 미래 · 51주보다 이전 · 그 해에 없는 주),
 * 파라미터 검증(`101`~`104`). 주소의 동네 · 주가 없는 것과 같아 없는 화면(404)이다
 */
const ADVISORY_UNREACHABLE: ReadonlySet<string> = new Set([
  'ADVISORY_008',
  'ADVISORY_009',
  'ADVISORY_101',
  'ADVISORY_102',
  'ADVISORY_103',
  'ADVISORY_104',
])

/** 같은 결의 집계 API 오류 — 없는 코드(`001`), 조회할 수 없는 주(`002`), 코드 · 주 형식(`101` · `102`) */
const AGGREGATE_UNREACHABLE: ReadonlySet<string> = new Set([
  'AGGREGATE_001',
  'AGGREGATE_002',
  'AGGREGATE_101',
  'AGGREGATE_102',
])

/** 안내 없음(`dataBody: null`)과 가르려고 따로 둔 "이 동네 · 주는 없다" 표시 */
const UNREACHABLE = Symbol('unreachable')

function malformed(what: string): Error {
  return new Error(`동네 안내 응답이 계약과 다르다: ${what}`)
}

const KST_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/** UTC 시각(ISO-8601)의 한국 날짜(`YYYY-MM-DD`). 읽을 수 없으면 빈 문자열 — 화면이 그 날짜 조각을 빼고 그린다 */
export function kstDateOf(instant: string): string {
  const date = new Date(instant)
  return Number.isNaN(date.getTime()) ? '' : KST_DATE.format(date)
}

async function requestOrUnreachable<T>(
  path: string,
  query: Record<string, string>,
  unreachable: ReadonlySet<string>,
): Promise<T | typeof UNREACHABLE> {
  try {
    return await apiRequest<T>(path, { auth: false, query })
  } catch (error) {
    if (error instanceof ApiError && unreachable.has(error.code)) return UNREACHABLE
    throw error
  }
}

/**
 * 동네 · 주의 안내와 그 주 집계. 형식이 틀린 코드(요청하지 않는다)이거나 어느 한쪽이 그 동네 · 주를 모르면(없는 코드 · 조회할 수 없는 주)
 * null 이다 — 부르는 쪽은 없는 화면(404)으로 처리한다. 그 밖의 실패(일시 장애) · 계약과 다른 응답은 거부한다 — 페이지의 오류 경계가 받는다.
 *
 * 동네 이름은 집계 응답(`district.name`)에서 읽는다. **폐지된 동네도 보인다** — 백엔드는 폐지된 코드를 오류로 보지 않고 그 주 안내 · 집계를
 * 준다(발행 당시의 안내를 지우지 않는다).
 */
export async function fetchRegionNotice(
  regionCode: string,
  isoWeek: string,
  now: () => Date = () => new Date(),
): Promise<{ regionName: string; body: RegionNoticeBody } | null> {
  if (!DISTRICT_CODE_PATTERN.test(regionCode)) return null
  const [advisory, aggregate] = await Promise.all([
    requestOrUnreachable<RegionAdvisoryResponse | null>(
      ADVISORIES_PATH,
      { districtCode: regionCode, week: isoWeek },
      ADVISORY_UNREACHABLE,
    ),
    requestOrUnreachable<DistrictWeeklyAggregateResponse>(
      `${DISTRICTS_PATH}/${regionCode}/weekly-aggregate`,
      { week: isoWeek },
      AGGREGATE_UNREACHABLE,
    ),
  ])
  if (advisory === UNREACHABLE || aggregate === UNREACHABLE) return null
  return {
    regionName: aggregate.district.name,
    body: toRegionNoticeBody(advisory, aggregate, isoWeek, now()),
  }
}

/**
 * 안내 응답(없으면 null)과 같은 주 집계를 화면 모델로. 두 응답의 주가 요청한 주와 다르거나, 안내 · 철회가 둘 다 없거나, 발행된 안내의
 * 인용 단계 · 그 주 집계가 수치가 없는 단계면 던진다(안내는 마감 · 자료 충분 집계로만 만들어진다 — 지어낸 값으로 채우지 않는다).
 */
export function toRegionNoticeBody(
  advisory: RegionAdvisoryResponse | null,
  aggregate: DistrictWeeklyAggregateResponse,
  isoWeek: string,
  now: Date,
): RegionNoticeBody {
  if (aggregate.isoWeek !== isoWeek) throw malformed(`집계 주 ${aggregate.isoWeek}`)
  const week = toHomeWeekly(aggregate, aggregate.district, now)
  if (advisory === null) return { notice: null, retraction: null, stats: weekStats(week) }

  if (advisory.isoWeek !== isoWeek) throw malformed(`안내 주 ${advisory.isoWeek}`)
  if (advisory.advisory) {
    const { title, body, publishedAt, citation, corrections } = advisory.advisory
    const notice: PublishedRegionNotice = {
      title,
      publishedOn: kstDateOf(publishedAt),
      body,
      // 서버가 최근 것부터 준다. 사유는 공개 응답에 없다
      corrections: corrections.map(({ correctedAt }): NoticeCorrection => ({
        correctedOn: kstDateOf(correctedAt),
      })),
    }
    return { notice, retraction: null, stats: citedStats(citation, week) }
  }
  if (advisory.retraction) {
    return {
      notice: null,
      retraction: { retractedOn: kstDateOf(advisory.retraction.retractedAt) },
      stats: weekStats(week),
    }
  }
  throw malformed('advisory · retraction 이 모두 없다')
}

/** 안내가 없는 주(안내 없음 · 철회)의 수치 — 그 주 집계 그대로다 */
function weekStats(week: HomeWeekly): MeasuredNoticeStats | InsufficientNoticeStats {
  if (week.status === 'insufficient') {
    return {
      status: 'insufficient',
      participants: week.participants,
      publicThreshold: week.publicThreshold,
    }
  }
  return {
    status: week.status,
    participants: week.participants,
    symptomRate: week.symptomRate,
    baselineRate: week.baselineRate,
    groups: week.groups,
  }
}

/**
 * 발행된 안내의 수치 — 단계 · 참여 · 증상 보고 비율 · 증상군 비율은 인용값, 지난 4주 평균 · 증상군 변화는 같은 주 집계다.
 * 모르는 증상군은 그 줄만 뺀다(집계와 같다). 집계에 그 증상군의 변화가 없으면(전주 자료 부족) 변화 없이 그린다
 */
function citedStats(citation: AdvisoryCitationResponse, week: HomeWeekly): MeasuredNoticeStats {
  const status = LEVEL_STATUS[citation.level.code]
  if (!status || status === 'insufficient') throw malformed(`인용 단계 ${citation.level.code}`)
  if (week.status === 'insufficient') throw malformed('발행된 안내의 주 집계가 자료 부족이다')
  return {
    status,
    participants: citation.participantCount,
    symptomRate: citation.symptomRate,
    baselineRate: week.baselineRate,
    groups: citation.groups.flatMap((item): SymptomGroup[] => {
      const key = GROUP_KEY[item.group.code]
      if (!key) return []
      const trend = week.groups.find((group) => group.key === key)?.trend
      return [trend ? { key, rate: item.rate, trend } : { key, rate: item.rate }]
    }),
  }
}
