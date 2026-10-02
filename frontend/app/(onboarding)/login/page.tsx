import type { Metadata } from 'next'

import { loginNoticeFrom } from '@/features/auth/login-notice'
import { loginReturnFrom } from '@/features/auth/login-return'
import { LoginScreen } from '@/features/auth/login-screen'

export const metadata: Metadata = { title: '로그인' }

/**
 * S13-1 로그인 방법 고르기. 카카오 콜백 · 세션 만료가 `?error=` · `?reason=` 으로 상태를 넘긴다.
 * 회원만 쓰는 화면의 가드는 `?next=` · `?region=` 으로 로그인 뒤 돌아갈 곳을 넘긴다(`login-return.ts`)
 */
export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const params = await searchParams
  return <LoginScreen notice={loginNoticeFrom(params)} loginReturn={loginReturnFrom(params)} />
}
