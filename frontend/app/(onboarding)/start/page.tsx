import type { Metadata } from 'next'

import { StartScreen } from '@/features/onboarding/start-screen'

export const metadata: Metadata = { title: '시작' }

/** S01 시작 */
export default function StartPage() {
  return <StartScreen />
}
