import type { Metadata } from 'next'

import {
  FROM_KAKAO,
  FROM_PARAM,
  PRESET_REGION_PARAM,
  RESELECT_PARAM,
  RESELECT_VALUE,
} from '@/features/onboarding/paths'
import { RegionReselectScreen } from '@/features/onboarding/region-reselect-screen'
import { RegionScreen } from '@/features/onboarding/region-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

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
 * `?region=<행정동 코드>` 는 가입 전에 둘러보던 동네다(#227, `PRESET_REGION_PARAM`). 행정동으로 확인해(`districtFromParam`) 지금 있는
 * 동네일 때만 처음 선택으로 넘긴다 — 형식이 틀리거나 모르는 · 폐지된 코드 · 조회 실패면 넘기지 않아 지금처럼 빈 선택으로 시작한다.
 * 서버에서 확인해 넘기므로 서버 · 하이드레이션 첫 그림부터 고른 채로 보인다(빈 칸이 채워지며 깜박이지 않는다).
 *
 * `?reselect=1` 이면 폐지된 동네 다시 고르기(Setup-1-reselect)다. 가입 흐름과 달리 회원의 동네를 바로 저장하고, `?next=` 로 돌아간다.
 * 둘이 함께 오면 다시 고르기가 먼저다(조건 화면이 함께 싣는 둘러보기 동네 `?region=` 은 확인하지 않는다).
 */
export default async function SetupRegionPage({ searchParams }: { searchParams: SearchParams }) {
  if (await isReselect(searchParams)) return <RegionReselectScreen />
  const params = await searchParams
  const preset = await districtFromParam(params[PRESET_REGION_PARAM], await readServerDataSource())
  return <RegionScreen fromKakao={params[FROM_PARAM] === FROM_KAKAO} preset={preset} />
}
