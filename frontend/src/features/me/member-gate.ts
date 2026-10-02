'use client'

import { useEffect } from 'react'
import { usePathname, useSearchParams } from 'next/navigation'

import type { MockAuthState } from '@/features/auth/auth-client'
import { loginHref } from '@/features/auth/login-return'
import { carriedParams, safeNextPath, stepTarget } from '@/features/auth/required-steps'
import { useMemberRequirements } from '@/features/auth/use-member-requirements'
import { useMockAuth } from '@/features/auth/use-mock-auth'
import { LOGIN_EXPIRED_PATH, LOGIN_PATH } from '@/features/onboarding/paths'
import { isSessionExpiring } from '@/lib/session-expiry'
import { useHydrated } from '@/lib/use-hydrated'
import { useNavTrail } from '@/lib/use-nav-trail'

/**
 * 회원만 쓰는 화면(내 정보 · 로그인한 기기 · 비밀번호)의 가드. 회원이면 회원 상태를, 아니면(또는 아직 모르면) null 을 돌려준다.
 * null 이면 화면은 본문을 그리지 않는다.
 *
 * **하이드레이션을 마친 뒤에만 판단한다.** 서버와 하이드레이션 첫 그림의 회원 상태는 늘 `guest` 다(서버는 목 세션을 모른다 —
 * `useMockAuth`). 그 값으로 바로 보내면 회원도 로그인으로 튕긴다. 하이드레이션 뒤 다시 그린 그림의 상태가 비회원일 때만
 * 로그인(`/login`)으로 기록을 바꿔 간다(뒤로 가기로 이 화면에 돌아와 다시 튕기지 않게). 이동은 Next 라우터로 한다 —
 * 원시 history 를 바꾸지 않으므로 `useSearchParams` 와 어긋나지 않고, 하이드레이션 첫 커밋 뒤라 Next 가 history 를 감싼 뒤다.
 * 앱 안 이동으로 처음 그리는 화면은 하이드레이션이 아니라 처음부터 목 세션으로 판단한다.
 *
 * - `next`: 로그인 뒤 돌아올 경로(허용 목록 `NEXT_PATHS`). 주면 `/login?next=<경로>` 로 보내고 둘러보기 동네(`?region=`)도 붙인다
 *   (`features/auth/login-return.ts`). QA 덮어쓰기(`?mock-auth=` 등)는 붙이지 않는다 — `?mock-auth=guest` 를 넘기면 돌아와 다시 튕긴다.
 *   생략하면 지금처럼 `/login` 이다(계정 화면, 후속 후보)
 * - `paused`: 이 화면이 스스로 비회원으로 바꾸는 중(로그아웃 · 탈퇴 성공 뒤 홈으로 가는 중)이면 true 로 둔다. 세션이 먼저 비회원이
 *   되어도 로그인으로 보내지 않는다 — 보내면 화면의 홈 이동과 겹쳐 로그인에 닿는다
 */
export function useMemberGate({
  next,
  paused = false,
}: { next?: string; paused?: boolean } = {}): Exclude<MockAuthState, 'guest'> | null {
  const { replace } = useNavTrail()
  const searchParams = useSearchParams()
  const hydrated = useHydrated()
  const auth = useMockAuth()
  const guest = hydrated && auth === 'guest' && !paused
  const loginTarget =
    next === undefined
      ? LOGIN_PATH
      : loginHref(LOGIN_PATH, { next: safeNextPath(next), region: searchParams.get('region') })

  useEffect(() => {
    // 로그인 만료로 비회원이 됐으면 만료 주소(토스트)로 보낸다. 만료 이동보다 나중에 불려도 같은 곳에 닿는다
    if (guest) replace(isSessionExpiring() ? LOGIN_EXPIRED_PATH : loginTarget)
  }, [guest, replace, loginTarget])

  return hydrated && auth !== 'guest' ? auth : null
}

/**
 * 다시 들어온 회원이 먼저 거칠 화면(약관 재동의 → 동네 다시 고르기)의 주소. 보낼 곳이 없거나 아직 모르면 null 이다.
 *
 * - 비회원은 null 이다. 조건은 `useMemberRequirements`(목 프로필 · `?mock-required=` 덮어쓰기)가 정한다
 * - `useMemberGate` 처럼 **하이드레이션을 마친 뒤에만** 정한다(첫 그림은 늘 비회원이다)
 * - 돌아올 곳은 `?next=<nextPath>`(허용 목록 밖이면 홈), 둘러보기 동네 · 목 덮어쓰기 쿼리는 남긴다(`carriedParams`)
 *
 * 이동은 하지 않는다. 가드(`useRequiredStepsGate`)와 같은 그림에서 주소 쿼리를 정리하는 화면이 이 값으로 정리를 건너뛴다 —
 * Next 는 router 내비게이션이 대기 중일 때 `history.replaceState` · `pushState` 가 불리면 그 내비게이션을 버린다(docs/conventions.md).
 */
export function useRequiredStepsTarget(nextPath: string): string | null {
  const searchParams = useSearchParams()
  const hydrated = useHydrated()
  const { steps } = useMemberRequirements()
  return hydrated && steps.length > 0
    ? stepTarget(steps, safeNextPath(nextPath), carriedParams(searchParams))
    : null
}

/**
 * 다시 들어온 회원을 먼저 거칠 화면으로 보내는 가드. 홈(`/`)과 내 정보(`/me` 와 그 아래)가 같이 쓴다.
 * 보낼 곳(`useRequiredStepsTarget`)이 있으면 Next 라우터로 기록을 바꿔 간다 — 뒤로 가기로 이 화면에 돌아와도 조건이 남아 있으면 다시 보낸다.
 *
 * 보낼 곳을 돌려준다. **보낼 곳이 있으면 같은 그림에서 원시 history 로 주소를 정리하지 않는다** — 정리가 이 이동을 버리게 한다.
 * 조건이 있는 동안에도 화면은 그대로 그린다(첫 그림은 늘 비회원이라 숨겨도 깜빡인다).
 */
export function useRequiredStepsGate(nextPath: string): string | null {
  const { replace } = useNavTrail()
  const target = useRequiredStepsTarget(nextPath)

  useEffect(() => {
    if (target) replace(target)
  }, [target, replace])

  return target
}

/**
 * 내 정보 레이아웃(`app/me/layout.tsx`)에 두는 가드. 지금 경로(`/me` · `/me/devices` · `/me/password`)로 돌아온다.
 * 화면 쪽 주소 정리(내 정보의 `?confirm=`)는 같은 판단(`useRequiredStepsTarget`)으로 보낼 곳이 있는지 본다
 */
export function MeRequiredStepsGate(): null {
  useRequiredStepsGate(usePathname())
  return null
}
