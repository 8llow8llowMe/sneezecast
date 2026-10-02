import type { Metadata } from 'next'

import { MapScreen } from '@/features/map/map-screen'
import { pickMapMock } from '@/features/map/mock'
import { districtFromParam } from '@/features/region/region-param'

export const metadata: Metadata = { title: '지도' }

/**
 * S04 지도. API 연동 전이라 목 데이터로 그리고, **지도 그림은 임시**(동네 목록)다 — 행정동 경계 · 지도 라이브러리 · 집계 API 는
 * docs/design/SCREENS.md "지도" 의 연동 요구사항이다.
 *
 * - `?mock=example|insufficient|empty` 로 상태를 고른다. 기본은 `insufficient`(모든 동네 자료 부족)다 — 실제 자료가 없는 지금
 *   수치를 지어내 보이지 않는다(홈과 같은 규칙). `empty` 는 이번 주 동네 자료가 하나도 없는 자료 없음이다.
 * - `?region=<행정동 코드>` 는 둘러보기 동네다. 아는 코드면 그 동네를 처음 고른 동네(내 동네)로 두고 메뉴 링크에 남긴다.
 */
export default async function MapPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { mock, region } = await searchParams
  const district = await districtFromParam(region)
  return <MapScreen map={pickMapMock(mock, district)} regionCode={district?.code ?? null} />
}
