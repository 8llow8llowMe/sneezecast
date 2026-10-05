import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { NicknameScreen } from '@/features/me/nickname-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: '닉네임 바꾸기' }

/**
 * S10 닉네임 바꾸기 (#192, 시안 없음). 회원만 본다 — 비회원이면 화면이 로그인(`?next=/me/nickname`)으로 보낸다.
 * 지금 닉네임은 화면이 프로필(목 세션 · 실데이터 회원 정보 저장소)에서 읽는다(서버는 세션을 모른다).
 * `?region=` · 목 덮어쓰기(`?mock-auth=` · `?mock-provider=`)는 내 정보(`app/me/page.tsx`)와 같다.
 */
export default async function MeNicknamePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const source = await readServerDataSource()
  const district = await districtFromParam(region, source)
  return (
    <NicknameScreen
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
