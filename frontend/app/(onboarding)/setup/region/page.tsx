import type { Metadata } from 'next'

import { FROM_KAKAO, FROM_PARAM, RESELECT_PARAM, RESELECT_VALUE } from '@/features/onboarding/paths'
import { RegionReselectScreen } from '@/features/onboarding/region-reselect-screen'
import { RegionScreen } from '@/features/onboarding/region-screen'

type SearchParams = Promise<Record<string, string | string[] | undefined>>

async function isReselect(searchParams: SearchParams): Promise<boolean> {
  return (await searchParams)[RESELECT_PARAM] === RESELECT_VALUE
}

export async function generateMetadata({
  searchParams,
}: {
  searchParams: SearchParams
}): Promise<Metadata> {
  return { title: (await isReselect(searchParams)) ? '동네 다시 고르기' : '동네 선택' }
}

/**
 * S02-1 동네 선택 (1 / 4). 카카오 로그인에서 돌아오면 `?from=kakao` 가 붙는다 — 화면이 가입 종류를 카카오로 둔다.
 * 값이 여러 개거나 다른 값이면 무시한다.
 *
 * `?reselect=1` 이면 폐지된 동네 다시 고르기(Setup-1-reselect)다. 가입 흐름과 달리 회원의 동네를 바로 저장하고, `?next=` 로 돌아간다.
 * 둘이 함께 오면 다시 고르기가 먼저다.
 */
export default async function SetupRegionPage({ searchParams }: { searchParams: SearchParams }) {
  if (await isReselect(searchParams)) return <RegionReselectScreen />
  const from = (await searchParams)[FROM_PARAM]
  return <RegionScreen fromKakao={from === FROM_KAKAO} />
}
