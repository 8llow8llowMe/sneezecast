import { HOME_MOCKS } from '@/features/home/mock'
import type { HomeWeekly } from '@/features/home/types'
import { NOTICE_EXAMPLE_DISTRICT, NOTICE_EXAMPLE_WEEK } from '@/features/notice/mock'
import { noticePath } from '@/features/notice/paths'
import { DISTRICT_MOCKS } from '@/features/region/mock'
import type { District } from '@/features/region/types'

import type { MapDistrict, MapWeek } from './types'

/**
 * 지도 목 데이터. 동네 하나의 값은 홈 목(`HOME_MOCKS`, 시안 예시 값)을 이름만 바꿔 쓴다.
 *
 * **API 연동 전까지만 쓴다.** 연동 이슈에서 이 파일을 지우고 집계 API · 행정동 경계 매핑으로 바꾼다.
 *
 * - 처음 고른 동네(내 동네)는 둘러보기 동네(`?region=`)가 있으면 그 동네, 없으면 홈 목과 같은 `○○동`(동네 안내 예시 동네)이다.
 * - 주변 동네는 행정동 목(`DISTRICT_MOCKS`)의 마포구 네 동이다. 행정동 목에 있는 코드라 `이 동네 안내 보기`(S07)가 열린다.
 */

export const MAP_MOCK_KEYS = ['example', 'insufficient', 'empty'] as const
export type MapMockKey = (typeof MAP_MOCK_KEYS)[number]

const NEIGHBOR_CODES = ['11440660', '11440680', '11440690', '11440700'] as const

const NEIGHBORS: readonly District[] = NEIGHBOR_CODES.map((code) => {
  const district = DISTRICT_MOCKS.find((item) => item.code === code)
  if (!district) throw new Error(`행정동 목에 없는 코드: ${code}`)
  return district
})

/** 이름 · 코드를 바꾼 동네. 발행된 안내가 있으면 그 동네 · 시안 기준 주의 안내(발행 목 상태)로 잇는다 */
function districtWeek(district: District, week: HomeWeekly): MapDistrict {
  const notice = week.notice && {
    ...week.notice,
    href: noticePath(district.code, NOTICE_EXAMPLE_WEEK, 'mock=published'),
  }
  return { code: district.code, week: { ...week, regionName: district.name, notice } }
}

/** 참여 인원만 바꾼 자료 부족 주 */
function insufficient(participants: number): HomeWeekly {
  return { ...HOME_MOCKS.insufficient, participants }
}

/**
 * 동네 자리마다의 이번 주. 첫 값은 처음 고른 동네, 나머지는 `NEIGHBORS` 순서다.
 * - example: 시안 예시 — 내 동네 조금 늘었어요(Map-collapsed), 서교동 많이 늘었어요(Map-expanded), 망원1동 자료 부족(Map-nodata, 41명)
 * - insufficient: 모든 동네가 자료 부족이다(기본값 — 수치를 지어내지 않는다)
 */
const WEEKS: Record<Exclude<MapMockKey, 'empty'>, readonly HomeWeekly[]> = {
  example: [
    HOME_MOCKS.slight,
    HOME_MOCKS.high,
    HOME_MOCKS.normal,
    insufficient(41),
    HOME_MOCKS.normal,
  ],
  insufficient: [
    HOME_MOCKS.insufficient,
    insufficient(41),
    insufficient(72),
    insufficient(23),
    insufficient(58),
  ],
}

/**
 * `?mock=` 값과 둘러보기 동네로 지도 목 데이터를 고른다. 모르는 값이면 `insufficient`(모든 동네 자료 부족)다 —
 * 실제 자료가 없는 지금 수치를 지어내 보이지 않는 쪽이 기본값이어야 한다(홈과 같은 규칙). `empty` 는 자료 없음(동네가 하나도 없음)이다.
 *
 * 둘러보기 동네가 주변 동네 중 하나면 그 자리를 내 동네로 쓰고 따로 더하지 않는다.
 */
export function pickMapMock(
  value: string | string[] | undefined,
  browse: District | null,
): MapWeek {
  const key = Array.isArray(value) ? value[0] : value
  const mode = MAP_MOCK_KEYS.find((item) => item === key) ?? 'insufficient'
  const mine = browse ?? NOTICE_EXAMPLE_DISTRICT
  const base = {
    weekLabel: HOME_MOCKS.insufficient.weekLabel,
    mineCode: mine.code,
    mineName: mine.name,
  }
  if (mode === 'empty') return { ...base, districts: [] }

  const [mineWeek, ...neighborWeeks] = WEEKS[mode]
  const neighbors = NEIGHBORS.map((district, index) =>
    districtWeek(district, neighborWeeks[index] ?? HOME_MOCKS.insufficient),
  )
  if (neighbors.some((district) => district.code === mine.code)) {
    return { ...base, districts: neighbors }
  }
  return {
    ...base,
    districts: [districtWeek(mine, mineWeek ?? HOME_MOCKS.insufficient), ...neighbors],
  }
}
