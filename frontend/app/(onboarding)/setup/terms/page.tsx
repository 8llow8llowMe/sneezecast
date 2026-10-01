import type { Metadata } from 'next'

import { TermsScreen } from '@/features/auth/terms-screen'

export const metadata: Metadata = { title: '가입 동의' }

/** S02-3 가입 동의 (3 / 4) */
export default function SetupTermsPage() {
  return <TermsScreen />
}
