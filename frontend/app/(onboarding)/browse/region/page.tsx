import type { Metadata } from 'next'

import { RegionScreen } from '@/features/onboarding/region-screen'

export const metadata: Metadata = { title: '둘러볼 동네 선택' }

/** S02-1 둘러보기용 동네 선택. 보고 없이 고른 동네의 홈으로 간다 */
export default function BrowseRegionPage() {
  return <RegionScreen browse />
}
