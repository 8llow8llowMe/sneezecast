import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { InterestRegionsScreen } from '@/features/me/interest-regions-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: '관심 동네' }

/**
 * S10 관심 동네 (#198, 시안 없음). 회원만 본다 — 비회원이면 화면이 로그인(`?next=/me/interest-regions`)으로 보낸다.
 * 관심 동네는 화면이 읽는다(서버는 세션을 모른다). 실데이터는 API 가 없어(BE 미정) 저장할 수 없다고 알린다.
 * `?region=` · 목 덮어쓰기(`?mock-auth=` · `?mock-provider=`)는 내 정보(`app/me/page.tsx`)와 같고,
 * 목 재현 `?mock-interest-regions=empty|full|fail` 을 더 받는다.
 */
export default async function MeInterestRegionsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const source = await readServerDataSource()
  const district = await districtFromParam(region, source)
  return (
    <InterestRegionsScreen
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
