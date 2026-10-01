import type { Metadata } from 'next'

import { PasswordResetCodeScreen } from '@/features/auth/password-reset-code-screen'

export const metadata: Metadata = { title: '인증 코드' }

/** S13-6 비밀번호 재설정 — 인증 코드 */
export default function PasswordResetCodePage() {
  return <PasswordResetCodeScreen />
}
