import type { Metadata } from 'next'

import { LoginEmailScreen } from '@/features/auth/login-email-screen'
import { isResetDone } from '@/features/auth/login-notice'

export const metadata: Metadata = { title: '이메일 로그인' }

/** S13-5 이메일 로그인. 비밀번호를 바꾼 뒤에는 `?reason=reset-done` 으로 온다 */
export default async function LoginEmailPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { reason } = await searchParams
  return <LoginEmailScreen resetDone={isResetDone(reason)} />
}
