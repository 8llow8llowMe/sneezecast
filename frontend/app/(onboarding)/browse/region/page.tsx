import type { Metadata } from 'next'

import { browseReturnFrom } from '@/features/onboarding/browse-return'
import { RegionScreen } from '@/features/onboarding/region-screen'

export const metadata: Metadata = { title: '둘러볼 동네 선택' }

const first = (value: string | string[] | undefined): string | null =>
  (Array.isArray(value) ? value[0] : value) ?? null

/**
 * S02-1 둘러보기용 동네 선택. 보고 없이 고른 동네의 홈으로 간다.
 * 머리줄 동네 이름에서 오면 `?next=`(허용 목록) · `?region=`(지금 둘러보던 동네)를 받아 고른 뒤 그 화면으로 돌아간다
 * (`features/onboarding/browse-return.ts`, #141). 쿼리를 읽어 동적 라우트다.
 */
export default async function BrowseRegionPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const params = await searchParams
  const browseReturn = browseReturnFrom({ get: (key) => first(params[key]) })
  return <RegionScreen browse browseReturn={browseReturn} />
}
