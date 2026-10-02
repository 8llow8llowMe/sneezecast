import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { MyRegionScreen } from '@/features/me/my-region-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: '내 동네 바꾸기' }

/**
 * S10 내 동네 바꾸기 (#141, 시안 없음). 회원만 본다 — 비회원이면 화면이 로그인(`?next=/me/region`)으로 보낸다.
 * 지금 내 동네는 화면이 목 프로필에서 읽는다(서버는 목 세션을 모른다).
 * `?region=` · 목 덮어쓰기(`?mock-auth=` · `?mock-provider=`)는 내 정보(`app/me/page.tsx`)와 같다.
 */
export default async function MeRegionPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const source = await readServerDataSource()
  const district = await districtFromParam(region, source)
  return (
    <MyRegionScreen
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
