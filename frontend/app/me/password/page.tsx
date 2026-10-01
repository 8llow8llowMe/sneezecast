import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { PasswordScreen } from '@/features/me/password-screen'
import { districtFromParam } from '@/features/region/region-param'

export const metadata: Metadata = { title: '비밀번호' }

/**
 * S10 비밀번호 변경 · 설정 (Settings-password). 회원만 본다 — 비회원이면 화면이 로그인으로 보낸다.
 * 비밀번호가 없는 카카오 회원(목 프로필 `hasPassword` false)은 같은 주소에서 설정 화면을 본다.
 * `?region=` · 목 덮어쓰기(`?mock-auth=` · `?mock-provider=`)는 내 정보(`app/me/page.tsx`)와 같다.
 */
export default async function MePasswordPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const district = await districtFromParam(region)
  return (
    <PasswordScreen
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
