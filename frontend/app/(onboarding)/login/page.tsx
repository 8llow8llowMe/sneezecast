import type { Metadata } from 'next'

import { loginNoticeFrom } from '@/features/auth/login-notice'
import { LoginScreen } from '@/features/auth/login-screen'

export const metadata: Metadata = { title: '로그인' }

/** S13-1 로그인 방법 고르기. 카카오 콜백 · 세션 만료가 `?error=` · `?reason=` 으로 상태를 넘긴다 */
export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  return <LoginScreen notice={loginNoticeFrom(await searchParams)} />
}
