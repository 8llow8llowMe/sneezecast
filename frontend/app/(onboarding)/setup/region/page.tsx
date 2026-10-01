import type { Metadata } from 'next'

import { RegionScreen } from '@/features/onboarding/region-screen'

export const metadata: Metadata = { title: '동네 선택' }

/** S02-1 동네 선택 (1 / 4) */
export default function SetupRegionPage() {
  return <RegionScreen />
}
