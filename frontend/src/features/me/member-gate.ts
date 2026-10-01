'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'

import type { MockAuthState } from '@/features/auth/auth-client'
import { useMockAuth } from '@/features/auth/use-mock-auth'
import { LOGIN_PATH } from '@/features/onboarding/paths'
import { useHydrated } from '@/lib/use-hydrated'

/**
 * 회원만 쓰는 계정 화면(로그인한 기기 · 비밀번호)의 가드. 회원이면 회원 상태를, 아니면(또는 아직 모르면) null 을 돌려준다.
 * null 이면 화면은 아무 것도 그리지 않는다.
 *
 * **하이드레이션을 마친 뒤에만 판단한다.** 서버와 하이드레이션 첫 그림의 회원 상태는 늘 `guest` 다(서버는 목 세션을 모른다 —
 * `useMockAuth`). 그 값으로 바로 보내면 회원도 로그인으로 튕긴다. 하이드레이션 뒤 다시 그린 그림의 상태가 비회원일 때만
 * 로그인(`/login`)으로 기록을 바꿔 간다(뒤로 가기로 이 화면에 돌아와 다시 튕기지 않게). 이동은 Next 라우터로 한다 —
 * 원시 history 를 바꾸지 않으므로 `useSearchParams` 와 어긋나지 않고, 하이드레이션 첫 커밋 뒤라 Next 가 history 를 감싼 뒤다.
 * 앱 안 이동으로 처음 그리는 화면은 하이드레이션이 아니라 처음부터 목 세션으로 판단한다.
 */
export function useMemberGate(): Exclude<MockAuthState, 'guest'> | null {
  const router = useRouter()
  const hydrated = useHydrated()
  const auth = useMockAuth()
  const guest = hydrated && auth === 'guest'

  useEffect(() => {
    if (guest) router.replace(LOGIN_PATH)
  }, [guest, router])

  return hydrated && auth !== 'guest' ? auth : null
}
