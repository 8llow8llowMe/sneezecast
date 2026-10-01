import { DISTRICT_MOCKS } from './mock'
import type { District } from './types'

/**
 * 행정동 찾기. **API 연동 전 목 구현이다.**
 *
 * 연동 이슈에서 함수 안만 `src/lib/api/` 를 거친 백엔드 호출(`GET /api/v1/districts`, #60)로 바꾼다.
 * 함수 모양(Promise)을 지금 맞춰 두어 화면 코드는 그대로 둔다. 요청 시간 제한 · 실패 처리는 API 계층이 맡고,
 * 실패하면 여기서 Promise 를 거부한다 — 화면은 거부를 받아 다시 검색하라고 안내한다.
 */

/** 검색 결과 최대 개수. 한 화면에서 고르기 어려울 만큼 늘어나지 않게 한다 */
const SEARCH_LIMIT = 20

/** 동 이름 또는 시군구에 검색어가 들어간 행정동. 공백만 있는 검색어는 빈 목록이다 */
export function searchDistricts(query: string): Promise<District[]> {
  const keyword = query.trim()
  if (!keyword) return Promise.resolve([])
  const found = DISTRICT_MOCKS.filter(
    (district) => district.name.includes(keyword) || district.sigungu.includes(keyword),
  )
  return Promise.resolve(found.slice(0, SEARCH_LIMIT))
}

/** 코드로 행정동 하나. 모르는 코드면 null 이다 */
export function findDistrict(code: string): Promise<District | null> {
  return Promise.resolve(DISTRICT_MOCKS.find((district) => district.code === code) ?? null)
}
