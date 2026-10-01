import type { Metadata } from 'next'

import { SignupEmailScreen } from '@/features/auth/signup-email-screen'

export const metadata: Metadata = { title: '이메일 가입' }

/** S13-2 이메일 가입 — 이메일 입력 */
export default function SignupEmailPage() {
  return <SignupEmailScreen />
}
