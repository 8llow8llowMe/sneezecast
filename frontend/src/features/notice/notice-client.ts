import { findDistrict } from '@/features/region/region-client'
import type { District } from '@/features/region/types'
import type { DataSource } from '@/lib/data-source'

import { NOTICE_EXAMPLE_DISTRICT, pickNoticeMock } from './mock'
import type { RegionNotice } from './types'

/** 질병관리청 공식 정보 화면 (S08, docs/design/SCREENS.md) */
const OFFICIAL_PATH = '/official'

/**
 * 동네 · 주의 안내. **안내 내용은 API 연동 전 목 구현이다**(백엔드 BE 미정) — 동네만 출처(`source`)에 따라 행정동 API 로 확인한다.
 *
 * 연동 이슈에서 안내 내용을 `src/lib/api/` 를 거친 백엔드 호출(`GET /api/notices/{admCd}?week=`)로 바꾼다.
 * 요청 시간 제한 · 실패 처리는 API 계층이 맡고, 실패하면 여기서 Promise 를 거부한다 — 페이지의 오류 경계가 받는다.
 *
 * 모르는 동네 · 폐지된 동네면 null 이다 — 부르는 쪽은 없는 화면(404)으로 처리한다. 목 출처는 행정동 목(`findDistrict`)과
 * 안내 예시 동네(`NOTICE_EXAMPLE_DISTRICT`, 지어낸 코드라 API 출처에서는 없는 동네다)만 알고, 상태는 `mock`(주소 `?mock=`)으로 고른다.
 */
export async function getRegionNotice(
  regionCode: string,
  isoWeek: string,
  source: DataSource,
  mock?: string | string[],
): Promise<RegionNotice | null> {
  const district = await noticeDistrict(regionCode, source)
  if (!district) return null
  return {
    regionCode: district.code,
    regionName: district.name,
    isoWeek,
    officialHref: OFFICIAL_PATH,
    ...pickNoticeMock(mock),
  }
}

async function noticeDistrict(regionCode: string, source: DataSource): Promise<District | null> {
  if (source === 'mock' && regionCode === NOTICE_EXAMPLE_DISTRICT.code) {
    return NOTICE_EXAMPLE_DISTRICT
  }
  const found = await findDistrict(regionCode, source)
  return found?.active ? found : null
}
