import type { Metadata } from 'next'

import { SignupCodeScreen } from '@/features/auth/signup-code-screen'

export const metadata: Metadata = { title: '인증 코드' }

/** S13-3 이메일 가입 — 인증 코드 */
export default function SignupCodePage() {
  return <SignupCodeScreen />
}
