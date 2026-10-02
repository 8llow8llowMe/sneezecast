import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { DevicesScreen } from '@/features/me/devices-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: '로그인한 기기' }

/**
 * S10 로그인한 기기 (Settings-devices). 회원만 본다 — 비회원이면 화면이 로그인으로 보낸다.
 * `?region=` · 목 덮어쓰기(`?mock-auth=` · `?mock-provider=`)는 내 정보(`app/me/page.tsx`)와 같다.
 */
export default async function MeDevicesPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const source = await readServerDataSource()
  const district = await districtFromParam(region, source)
  return (
    <DevicesScreen
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
