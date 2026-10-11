import { findDistrict } from '@/features/region/region-client'
import type { District } from '@/features/region/types'
import type { DataSource } from '@/lib/data-source'

import { fetchRegionNotice } from './advisory-api'
import { NOTICE_EXAMPLE_DISTRICTS, pickNoticeMock } from './mock'
import type { RegionNotice } from './types'

/** 질병관리청 공식 정보 화면 (S08, docs/design/SCREENS.md) */
const OFFICIAL_PATH = '/official'

/**
 * 동네 · 주의 안내. 출처(`DataSource`)를 인자로 받아 백엔드나 목 데이터(`mock.ts`)로 답한다 — 출처는 서버 페이지가
 * `readServerDataSource()` 로 읽어 넘긴다(docs/conventions.md "데이터 출처").
 *
 * - 실데이터: 동네 안내 API(`GET /api/v1/advisories?districtCode=&week=`)와 같은 동네 · 주의 행정동 주간 집계를 함께 읽어 옮긴다
 *   (`advisory-api.ts`, docs/api-contract-draft.md "동네 안내"). 동네 이름은 집계 응답에서 읽는다. 주소의 `?mock=` 은 듣지 않는다.
 * - 목데이터: 행정동 목(`findDistrict`)과 안내 예시 동네(`NOTICE_EXAMPLE_DISTRICTS` — 운영자 목의 ○○1동~○○5동 포함, 지어낸 코드라
 *   실데이터에서는 없는 동네다)만 알고, 상태는 `mock`(주소 `?mock=`)으로 고른다. 주는 모양만 본다(어느 주든 같은 목).
 *
 * 그 동네 · 주가 없으면(모르는 동네 · 조회할 수 없는 주, 목은 폐지된 동네도) null 이다 — 부르는 쪽은 없는 화면(404)으로 처리한다.
 * 요청 시간 제한 · 봉투 풀기는 API 계층이 맡고, 그 밖의 실패(일시 장애 · 계약이 어긋난 응답)하면 Promise 를 거부한다 —
 * 페이지의 오류 경계(`다시 시도`)가 받는다. 예시 값을 대신 보이지 않는다.
 */
export async function getRegionNotice(
  regionCode: string,
  isoWeek: string,
  source: DataSource,
  mock?: string | string[],
): Promise<RegionNotice | null> {
  if (source === 'api') {
    const found = await fetchRegionNotice(regionCode, isoWeek)
    if (!found) return null
    return {
      regionCode,
      regionName: found.regionName,
      isoWeek,
      officialHref: OFFICIAL_PATH,
      ...found.body,
    }
  }
  const district = await mockDistrict(regionCode)
  if (!district) return null
  return {
    regionCode: district.code,
    regionName: district.name,
    isoWeek,
    officialHref: OFFICIAL_PATH,
    ...pickNoticeMock(mock),
  }
}

async function mockDistrict(regionCode: string): Promise<District | null> {
  const example = NOTICE_EXAMPLE_DISTRICTS.find((district) => district.code === regionCode)
  if (example) return example
  const found = await findDistrict(regionCode, 'mock')
  return found?.active ? found : null
}
