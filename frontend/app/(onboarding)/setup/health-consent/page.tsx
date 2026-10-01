import type { Metadata } from 'next'

import { HealthConsentScreen } from '@/features/auth/health-consent-screen'

export const metadata: Metadata = { title: '증상 보고 동의' }

/** S02-4 증상 보고 동의 (4 / 4) */
export default function SetupHealthConsentPage() {
  return <HealthConsentScreen />
}
