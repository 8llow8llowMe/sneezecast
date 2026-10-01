import type { Metadata } from 'next'

import { isVerificationExpired } from '@/features/auth/login-notice'
import { SignupEmailScreen } from '@/features/auth/signup-email-screen'

export const metadata: Metadata = { title: '이메일 가입' }

/** S13-2 이메일 가입 — 이메일 입력. 가입 요청이 인증 만료로 돌아오면 `?reason=verification-expired` 로 온다 */
export default async function SignupEmailPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { reason } = await searchParams
  return <SignupEmailScreen verificationExpired={isVerificationExpired(reason)} />
}
