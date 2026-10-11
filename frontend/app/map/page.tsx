import type { Metadata } from 'next'

import { MapScreen } from '@/features/map/map-screen'
import { pickMapMock } from '@/features/map/mock'
import { districtCodeFromParam, districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: '지도' }

/**
 * S04 지도. 동네 자료의 출처는 데이터 출처(`readServerDataSource`)로 정하고(#257), **지도 그림은 임시**(동네 목록)다 — 행정동 경계 ·
 * 지도 라이브러리는 docs/design/SCREENS.md "지도" 의 연동 요구사항이다.
 *
 * - **실데이터**: 목 지도를 넘기지 않는다(`map={null}`). 화면이 처음 고른 동네(둘러보기 동네, 없으면 회원의 내 동네)의 시군구 집계
 *   (`GET /api/v1/districts/weekly-aggregates`)를 읽는다 — 내 동네는 서버가 모르는 회원 정보라 서버에서 읽을 수 없다(홈과 같다).
 *   `?region=` 은 모양(숫자 8자리)만 보고 넘긴다 — 이름 · 있는 동네인지는 집계 응답(`include` 동네 · 404 · 폐지면 동네 없음)이 정한다.
 *
 * 목데이터:
 * - `?mock=example|insufficient|empty` 로 상태를 고른다. 기본은 `insufficient`(모든 동네 자료 부족)다 — 실제 자료가 없는 지금
 *   수치를 지어내 보이지 않는다(홈과 같은 규칙). `empty` 는 이번 주 동네 자료가 하나도 없는 자료 없음이다.
 * - `?region=<행정동 코드>` 는 둘러보기 동네다(머리줄 동네 이름 · 지도 위 `행정동 이름으로 찾기`로 고른 동네, #141 · #204).
 *   아는 코드면 그 동네를 처음 고른 동네로 두고 메뉴 링크에 남긴다. 모르는 코드 · 폐지된 코드 · 조회 실패면(`districtFromParam`)
 *   버리고 목 예시 동네로 그린다 — 이름을 모르는 코드를 동네처럼 그리거나 메뉴 링크로 퍼뜨리지 않는다.
 * - 찾아서 고른 동네는 지도 목 자료의 동네가 아니어도 처음 고른 동네로 더한다(`pickMapMock`). 동네 고르기를 오가면 `?mock=` 가
 *   버려지므로(`browse-return.ts`) 돌아온 지도는 기본값이라 그 동네도 자료 부족(수치 · 상태색 없음)이다.
 */
export default async function MapPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { mock, region } = await searchParams
  const source = await readServerDataSource()
  if (source === 'api') {
    return (
      <MapScreen
        map={null}
        regionName={pickMapMock(undefined, null).mineName}
        regionCode={districtCodeFromParam(region)}
      />
    )
  }
  const district = await districtFromParam(region, source)
  return <MapScreen map={pickMapMock(mock, district)} regionCode={district?.code ?? null} />
}
