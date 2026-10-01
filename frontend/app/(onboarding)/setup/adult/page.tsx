import type { Metadata } from 'next'

import { AdultScreen } from '@/features/onboarding/adult-screen'

export const metadata: Metadata = { title: '성인 확인' }

/** S02-2 성인 확인 */
export default function SetupAdultPage() {
  return <AdultScreen />
}
