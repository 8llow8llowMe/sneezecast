import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { ReportsScreen } from '@/features/me/reports-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: '최근 보고 내역' }

/**
 * S10 최근 보고 내역 (#194, 시안 없음). 건강정보에 동의한 회원만 본다 — 비회원이면 화면이 로그인(`?next=/me/reports`)으로,
 * 동의하지 않은 회원이면 내 정보로 보낸다. 보고는 화면이 읽는다(서버는 세션을 모른다).
 * `?region=` · 목 덮어쓰기(`?mock-auth=` · `?mock-provider=`)는 내 정보(`app/me/page.tsx`)와 같고, 목 재현 `?mock-reports=empty` 를 더 받는다.
 */
export default async function MeReportsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const source = await readServerDataSource()
  const district = await districtFromParam(region, source)
  return (
    <ReportsScreen
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
