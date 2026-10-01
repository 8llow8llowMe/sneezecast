import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { pickOfficialMock } from '@/features/official/mock'
import { OfficialScreen } from '@/features/official/official-screen'
import { districtFromParam } from '@/features/region/region-param'

export const metadata: Metadata = { title: '질병관리청 발표' }

/**
 * S08 공식 정보. API 연동 전이라 목 데이터로 그린다.
 *
 * `?mock=published|empty` 로 상태를 고른다. 기본은 `empty`(받은 발표 없음)다 — 실제 자료가 없는 지금 발표를 지어내 보이지 않는다.
 * 홈의 공식 정보 행은 홈 목 데이터의 예시 발표와 맞게 `?mock=published` 로 온다(`features/home/mock.ts`).
 * `?region=<행정동 코드>` 는 홈과 같이 받아 머리줄 동네 이름 · 메뉴 링크에 쓴다(공식 자료는 동네와 무관하다).
 */
export default async function OfficialPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { mock, region } = await searchParams
  const district = await districtFromParam(region)
  return (
    <OfficialScreen
      official={pickOfficialMock(mock)}
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
