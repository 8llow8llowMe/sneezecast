import type { Metadata } from 'next'

import { PasswordResetNewScreen } from '@/features/auth/password-reset-new-screen'

export const metadata: Metadata = { title: '새 비밀번호' }

/** S13-6 비밀번호 재설정 — 새 비밀번호 */
export default function PasswordResetNewPage() {
  return <PasswordResetNewScreen />
}
