import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { NotificationsScreen } from '@/features/me/notifications-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: '알림 설정' }

/**
 * S10 알림 설정 (#195, 시안 없음). 회원만 본다 — 비회원이면 화면이 로그인(`?next=/me/notifications`)으로 보낸다.
 * 알림 설정은 화면이 읽는다(서버는 세션을 모른다). 실데이터는 API 가 없어(BE 미정) 켜고 끌 수 없다고 알린다.
 * 푸시 구독 · 알림 권한 요청은 하지 않는다(2단계).
 * `?region=` · 목 덮어쓰기(`?mock-auth=` · `?mock-provider=`)는 내 정보(`app/me/page.tsx`)와 같고,
 * 알림 덮어쓰기 `?mock-push=` 와 목 재현 `?mock-notifications=on|fail` 을 더 받는다.
 */
export default async function MeNotificationsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const source = await readServerDataSource()
  const district = await districtFromParam(region, source)
  return (
    <NotificationsScreen
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
