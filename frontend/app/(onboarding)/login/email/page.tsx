import type { Metadata } from 'next'

import { LoginEmailScreen } from '@/features/auth/login-email-screen'
import { isResetDone } from '@/features/auth/login-notice'
import { loginReturnFrom } from '@/features/auth/login-return'

export const metadata: Metadata = { title: '이메일 로그인' }

/**
 * S13-5 이메일 로그인. 비밀번호를 바꾼 뒤에는 `?reason=reset-done` 으로 온다.
 * 로그인 방법 고르기가 이어 넘긴 `?next=` · `?region=` 이 있으면 로그인 뒤 그곳으로 간다(`login-return.ts`)
 */
export default async function LoginEmailPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const params = await searchParams
  return (
    <LoginEmailScreen
      resetDone={isResetDone(params.reason)}
      loginReturn={loginReturnFrom(params)}
    />
  )
}
