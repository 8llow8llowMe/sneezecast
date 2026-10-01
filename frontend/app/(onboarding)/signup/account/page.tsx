import type { Metadata } from 'next'

import { SignupAccountScreen } from '@/features/auth/signup-account-screen'

export const metadata: Metadata = { title: '비밀번호 · 닉네임' }

/** S13-4 이메일 가입 — 비밀번호 · 닉네임 */
export default function SignupAccountPage() {
  return <SignupAccountScreen />
}
