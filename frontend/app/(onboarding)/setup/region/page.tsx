import type { Metadata } from 'next'

import { FROM_KAKAO, FROM_PARAM } from '@/features/onboarding/paths'
import { RegionScreen } from '@/features/onboarding/region-screen'

export const metadata: Metadata = { title: '동네 선택' }

/**
 * S02-1 동네 선택 (1 / 4). 카카오 로그인에서 돌아오면 `?from=kakao` 가 붙는다 — 화면이 가입 종류를 카카오로 둔다.
 * 값이 여러 개거나 다른 값이면 무시한다.
 */
export default async function SetupRegionPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const from = (await searchParams)[FROM_PARAM]
  return <RegionScreen fromKakao={from === FROM_KAKAO} />
}
