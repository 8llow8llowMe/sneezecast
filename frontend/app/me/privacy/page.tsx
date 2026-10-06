import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { INFO_PAGES } from '@/features/me/info-pages'
import { InfoScreen } from '@/features/me/info-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: INFO_PAGES.privacy.title }

/**
 * S10 모으는 정보와 보관 기간 (#193, 시안 없음). 정적 안내라 **비회원도 본다** — 회원 가드가 없다(`features/me/info-screen.tsx`).
 * 문구는 `features/me/info-pages.ts` 에 있다. `?region=` · 목 덮어쓰기(`?mock-auth=` · `?mock-provider=`)는 내 정보(`app/me/page.tsx`)와 같다.
 */
export default async function MePrivacyPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const source = await readServerDataSource()
  const district = await districtFromParam(region, source)
  return (
    <InfoScreen
      kind="privacy"
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
