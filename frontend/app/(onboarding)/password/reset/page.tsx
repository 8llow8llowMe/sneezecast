import type { Metadata } from 'next'

import { isVerificationExpired } from '@/features/auth/login-notice'
import { PasswordResetEmailScreen } from '@/features/auth/password-reset-email-screen'

export const metadata: Metadata = { title: '비밀번호 재설정' }

/** S13-6 비밀번호 재설정 — 이메일 입력. 재설정 요청이 인증 만료로 돌아오면 `?reason=verification-expired` 로 온다 */
export default async function PasswordResetPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { reason } = await searchParams
  return <PasswordResetEmailScreen verificationExpired={isVerificationExpired(reason)} />
}
