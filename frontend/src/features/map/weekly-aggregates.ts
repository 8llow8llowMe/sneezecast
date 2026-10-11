import {
  AGGREGATE_NOT_FOUND,
  type AggregateWeekResponse,
  DISTRICT_CODE_PATTERN,
  type DistrictAggregateResponse,
  DISTRICTS_PATH,
  toHomeWeekly,
  weekLabels,
} from '@/features/home/weekly-aggregate'
import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import type { MapWeek } from './types'

/*
 * 지도(S04)의 시군구 하나 동네별 이번 주(docs/api-contract-draft.md "행정동 주간 집계" 의 지도 요청, 백엔드 #212 · 연동 #257).
 * 동네 집계 한 칸은 홈과 같은 모양이라 `toHomeWeekly` 로 옮긴다 — 자료 부족 동네는 수치 필드가 없는 갈래, 발행 안내는 제목 · 발행일과
 * 그 안내의 근거 주 동네 안내 주소(#256)다.
 *
 * - **실데이터만 부른다.** 목데이터는 서버 페이지가 `pickMapMock`(`?mock=`)으로 고른 값을 그대로 넘긴다(`app/map/page.tsx`).
 * - 공개 API 라 `auth: false` 로 보낸다(홈 · 행정동 API 와 같다).
 */

/** `GET /api/v1/districts/weekly-aggregates` */
type DistrictsWeeklyAggregateResponse = AggregateWeekResponse & {
  districts: DistrictAggregateResponse[]
}

/** 시군구 코드 — 행정동 코드(SGIS 8자리) 앞 5자리 */
const SIGUNGU_CODE_LENGTH = 5

function malformed(what: string): Error {
  return new Error(`행정동 주간 집계(지도) 응답이 계약과 다르다: ${what}`)
}

/**
 * 처음 고른 동네(`code` — 둘러보기 동네 · 회원의 내 동네)가 속한 시군구의 이번 주(현재 주). 그 동네를 `include` 로 보내 시군구 밖 ·
 * 지도 범위와 무관하게 응답에 꼭 담게 한다(#204). 형식이 틀린 코드(요청하지 않는다) · 없는 코드(404 `AGGREGATE_001`) · 폐지된 코드
 * (`active: false` — 홈과 같다)면 null 이다 — 화면은 동네를 고르라고 안내한다. 그 밖의 실패 · 계약과 다른 응답은 거부한다 —
 * 화면은 불러오지 못함 + 다시 시도다.
 */
export async function fetchMapWeek(
  code: string,
  now: () => Date = () => new Date(),
): Promise<MapWeek | null> {
  if (!DISTRICT_CODE_PATTERN.test(code)) return null
  let response: DistrictsWeeklyAggregateResponse
  try {
    response = await apiRequest<DistrictsWeeklyAggregateResponse>(
      `${DISTRICTS_PATH}/weekly-aggregates`,
      { auth: false, query: { sigunguCode: code.slice(0, SIGUNGU_CODE_LENGTH), include: code } },
    )
  } catch (error) {
    if (error instanceof ApiError && error.code === AGGREGATE_NOT_FOUND) return null
    throw error
  }
  return toMapWeek(response, code, now())
}

/**
 * 응답을 지도 모델로. 동네 순서는 응답 그대로다(코드 오름차순, 시군구 밖 `include` 는 맨 앞).
 *
 * **모든 동네의 참여가 0 이면 이번 주 동네 자료가 없음**(`districts: []` — 지도의 "자료 없음")이다. 응답에는 따로 알리는 값이 없고
 * 집계 행이 없는 동도 자료 부족 · 참여 0 으로 담기므로, 그대로 그리면 회색 `자료 부족` 만 늘어선 목록이 된다(계약 문서). 처음 고른
 * 동네 이름은 머리줄에 쓰도록 남긴다.
 */
function toMapWeek(
  response: DistrictsWeeklyAggregateResponse,
  mineCode: string,
  now: Date,
): MapWeek | null {
  const mine = response.districts.find((district) => district.code === mineCode)
  if (!mine) throw malformed(`include ${mineCode} 가 목록에 없다`)
  if (!mine.active) return null
  const labels = weekLabels(response.isoWeek)
  if (!labels) throw malformed(`isoWeek ${response.isoWeek}`)

  const base = { weekLabel: labels.weekLabel, mineCode, mineName: mine.name }
  if (response.districts.every((district) => district.participantCount === 0)) {
    return { ...base, districts: [] }
  }
  return {
    ...base,
    districts: response.districts.map((district) => ({
      code: district.code,
      week: toHomeWeekly(response, district, now),
    })),
  }
}
