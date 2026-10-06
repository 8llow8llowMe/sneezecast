'use client'

import { useEffect } from 'react'
import { usePathname, useSearchParams } from 'next/navigation'

import type { MockAuthState } from '@/features/auth/auth-client'
import { expiredLoginHref, loginHref, loginReturnTo } from '@/features/auth/login-return'
import {
  carriedParams,
  REPORT_ENTRY_PARAM,
  safeNextPath,
  stepTarget,
} from '@/features/auth/required-steps'
import { useAuth, useAuthSettled } from '@/features/auth/use-auth'
import { useMemberRequirements } from '@/features/auth/use-member-requirements'
import { HOME_PATH, LOGIN_PATH } from '@/features/onboarding/paths'
import { isSessionExpiring } from '@/lib/session-expiry'
import { useNavTrail } from '@/lib/use-nav-trail'

import { isInfoPath } from './info-pages'

/**
 * 회원만 쓰는 화면(내 정보 · 로그인한 기기 · 비밀번호 · 내 동네 · 닉네임 · 최근 보고 내역 · 관심 동네 · 알림 설정)의 가드. 서비스 안내 화면(`info-screen.tsx`)은 비회원도 봐서 걸지 않는다. 회원이면 회원 상태를, 아니면(또는 아직 모르면) null 을 돌려준다.
 * null 이면 화면은 본문을 그리지 않는다.
 *
 * **회원 상태가 정해진 뒤에만 판단한다**(`useAuthSettled` — 하이드레이션을 마쳤고, 실데이터면 세션 복원도 마침). 서버와 하이드레이션
 * 첫 그림의 회원 상태는 늘 `guest` 이고(서버는 세션을 모른다 — `useAuth`), 실데이터는 새로고침 뒤 재발급을 기다리는 동안에도 `guest` 다.
 * 그 값으로 바로 보내면 회원도 로그인으로 튕긴다. 정해진 그림의 상태가 비회원일 때만
 * 로그인(`/login`)으로 기록을 바꿔 간다(뒤로 가기로 이 화면에 돌아와 다시 튕기지 않게). 이동은 Next 라우터로 한다 —
 * 원시 history 를 바꾸지 않으므로 `useSearchParams` 와 어긋나지 않고, 하이드레이션 첫 커밋 뒤라 Next 가 history 를 감싼 뒤다.
 * 앱 안 이동으로 처음 그리는 화면은 하이드레이션이 아니라 처음부터 목 세션으로 판단한다.
 *
 * - `next`: 로그인 뒤 돌아올 경로(그 화면의 경로, 허용 목록 `NEXT_PATHS`). `/login?next=<경로>` 로 보내고 둘러보기 동네(`?region=`)도 붙인다
 *   (`features/auth/login-return.ts`). 목록 밖이면 `next` 를 싣지 않는다(홈). QA 덮어쓰기(`?mock-auth=` 등)는 붙이지 않는다 —
 *   `?mock-auth=guest` 를 넘기면 돌아와 다시 튕긴다. 로그인 만료면 만료 주소(`/login?reason=expired`)에 같은 돌아갈 곳을 붙인다 —
 *   만료 감시(`session-expiry-watcher.tsx`)가 지금 경로로 만드는 주소와 같아 어느 쪽이 나중에 불려도 같은 곳에 닿는다.
 *   그래서 생략할 수 없다(내 정보 · 로그인한 기기 · 비밀번호 · 내 동네 · 닉네임 · 최근 보고 내역 · 관심 동네 · 알림 설정 모두 넘긴다, #140)
 * - `paused`: 이 화면이 스스로 비회원으로 바꾸는 중(로그아웃 · 탈퇴 성공 뒤 홈으로 가는 중)이면 true 로 둔다. 세션이 먼저 비회원이
 *   되어도 로그인으로 보내지 않는다 — 보내면 화면의 홈 이동과 겹쳐 로그인에 닿는다
 */
export function useMemberGate({
  next,
  paused = false,
}: {
  next: string
  paused?: boolean
}): Exclude<MockAuthState, 'guest'> | null {
  const { replace } = useNavTrail()
  const searchParams = useSearchParams()
  const settled = useAuthSettled()
  const auth = useAuth()
  const guest = settled && auth === 'guest' && !paused
  const loginReturn = loginReturnTo(next, searchParams)
  const loginTarget = loginHref(LOGIN_PATH, loginReturn)
  const expiredTarget = expiredLoginHref(loginReturn)

  useEffect(() => {
    // 로그인 만료로 비회원이 됐으면 만료 주소(토스트)로 보낸다. 만료 이동보다 나중에 불려도 같은 곳에 닿는다
    if (guest) replace(isSessionExpiring() ? expiredTarget : loginTarget)
  }, [guest, replace, loginTarget, expiredTarget])

  return settled && auth !== 'guest' ? auth : null
}

/**
 * 다시 들어온 회원이 먼저 거칠 화면(약관 재동의 → 동네 다시 고르기)의 주소. 보낼 곳이 없거나 아직 모르면 null 이다.
 *
 * - 비회원은 null 이다. 조건은 `useMemberRequirements`(목 프로필 · `?mock-required=` 덮어쓰기)가 정한다
 * - `useMemberGate` 처럼 **회원 상태가 정해진 뒤에만**(`useAuthSettled`) 정한다(첫 그림 · 복원 중은 늘 비회원이다)
 * - 돌아올 곳은 `?next=<nextPath>`(허용 목록 밖이면 홈), 둘러보기 동네 · 목 덮어쓰기 쿼리는 남긴다(`carriedParams`)
 * - 홈에 보고 진입(`?report=` — 보고하려던 로그인 뒤 `report=start` 로 옴)이 있으면 조건 화면에 보고하려던 표시(`?intent=report`)를 붙인다.
 *   조건을 마치면 같은 동네 홈의 보고 진입으로 이어진다(#140, `targetAfter`). 내 정보로 돌아가면 붙이지 않는다
 *
 * 이동은 하지 않는다. 가드(`useRequiredStepsGate`)와 같은 그림에서 주소 쿼리를 정리하는 화면이 이 값으로 정리를 건너뛴다 —
 * Next 는 router 내비게이션이 대기 중일 때 `history.replaceState` · `pushState` 가 불리면 그 내비게이션을 버린다(docs/conventions.md).
 */
export function useRequiredStepsTarget(nextPath: string): string | null {
  const searchParams = useSearchParams()
  const settled = useAuthSettled()
  const { steps } = useMemberRequirements()
  if (!settled || steps.length === 0) return null
  const next = safeNextPath(nextPath)
  const reportIntent = next === HOME_PATH && Boolean(searchParams.get(REPORT_ENTRY_PARAM))
  return stepTarget(steps, next, carriedParams(searchParams), reportIntent)
}

/**
 * 다시 들어온 회원을 먼저 거칠 화면으로 보내는 가드. 홈(`/`)과 내 정보(`/me` 와 그 아래)가 같이 쓴다.
 * 보낼 곳(`useRequiredStepsTarget`)이 있으면 Next 라우터로 기록을 바꿔 간다 — 뒤로 가기로 이 화면에 돌아와도 조건이 남아 있으면 다시 보낸다.
 *
 * 보낼 곳을 돌려준다. **보낼 곳이 있으면 같은 그림에서 원시 history 로 주소를 정리하지 않는다** — 정리가 이 이동을 버리게 한다.
 * 조건이 있는 동안에도 화면은 그대로 그린다(첫 그림은 늘 비회원이라 숨겨도 깜빡인다).
 * `enabled` 가 false 면 보내지 않고 null 이다(내 정보 아래 서비스 안내 화면 — `MeRequiredStepsGate`).
 */
export function useRequiredStepsGate(nextPath: string, enabled = true): string | null {
  const { replace } = useNavTrail()
  const found = useRequiredStepsTarget(nextPath)
  const target = enabled ? found : null

  useEffect(() => {
    if (target) replace(target)
  }, [target, replace])

  return target
}

/**
 * 내 정보 레이아웃(`app/me/layout.tsx`)에 두는 가드. 내 정보와 계정 화면(`/me` · `/me/devices` · `/me/password` · `/me/region` · `/me/nickname` · `/me/reports` · `/me/interest-regions` · `/me/notifications`)에서
 * 조건 화면으로 보내고, 마치면 지금 경로로 돌아온다(허용 목록 `NEXT_PATHS` 밖이면 홈).
 * 화면 쪽 주소 정리(내 정보의 `?confirm=`)는 같은 판단(`useRequiredStepsTarget`)으로 보낼 곳이 있는지 본다.
 *
 * **서비스 안내 화면(`/me/privacy` · `/me/data-sources` · `/me/ai`, `isInfoPath`)에서는 보내지 않는다**(#193). 계정을 쓰는 화면이 아니라
 * 비회원도 보는 정적 안내다. 약관 재동의를 앞둔 회원이 모으는 정보 · 보관 기간을 읽고 판단할 수 있어야 한다
 */
export function MeRequiredStepsGate(): null {
  const pathname = usePathname()
  useRequiredStepsGate(pathname, !isInfoPath(pathname))
  return null
}
