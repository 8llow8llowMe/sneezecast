import { findDistrict } from '@/features/region/region-client'

import { NOTICE_EXAMPLE_DISTRICT, pickNoticeMock } from './mock'
import type { RegionNotice } from './types'

/** 질병관리청 공식 정보 화면 (S08, docs/design/SCREENS.md) */
const OFFICIAL_PATH = '/official'

/**
 * 동네 · 주의 안내. **API 연동 전 목 구현이다.**
 *
 * 연동 이슈에서 함수 안만 `src/lib/api/` 를 거친 백엔드 호출(`GET /api/notices/{admCd}?week=`)로 바꾼다.
 * 요청 시간 제한 · 실패 처리는 API 계층이 맡고, 실패하면 여기서 Promise 를 거부한다.
 *
 * 모르는 동네면 null 이다 — 부르는 쪽은 없는 화면(404)으로 처리한다. 목은 행정동 목(`findDistrict`)과
 * 안내 예시 동네(`NOTICE_EXAMPLE_DISTRICT`)만 알고, 상태는 `mock`(주소 `?mock=`)으로 고른다.
 */
export async function getRegionNotice(
  regionCode: string,
  isoWeek: string,
  mock?: string | string[],
): Promise<RegionNotice | null> {
  const district =
    regionCode === NOTICE_EXAMPLE_DISTRICT.code
      ? NOTICE_EXAMPLE_DISTRICT
      : await findDistrict(regionCode)
  if (!district) return null
  return {
    regionCode: district.code,
    regionName: district.name,
    isoWeek,
    officialHref: OFFICIAL_PATH,
    ...pickNoticeMock(mock),
  }
}
