'use client'

import { useEffect, useState, useSyncExternalStore } from 'react'
import { usePathname, useSearchParams } from 'next/navigation'

import { LOGIN_EXPIRED_PATH, LOGIN_PATH } from '@/features/onboarding/paths'
import { clearSessionExpiring, notifySessionExpired, onSessionExpired } from '@/lib/session-expiry'
import { useHydrated } from '@/lib/use-hydrated'
import { useNavTrail } from '@/lib/use-nav-trail'

import { expireMockSession, getMockSession, subscribeMockSession } from './auth-client'

/**
 * QA 용 로그인 만료 재현 쿼리 (`?mock-session=expired`). 어느 화면이든 열면 바로 만료 흐름을 탄다.
 * **연동 때 지운다** — `?mock-auth=` 와 같은 QA 덮어쓰기다. 실제 만료는 API 계층의 401 처리가 알린다
 */
export const MOCK_SESSION_PARAM = 'mock-session'
export const MOCK_SESSION_EXPIRED = 'expired'

/**
 * 로그인 만료(State-session-expired)를 받아 처리한다. 루트 레이아웃(`app/layout.tsx`)에 하나만 둔다.
 *
 * 만료 알림(`src/lib/session-expiry.ts` 의 `notifySessionExpired`)을 받으면 목 세션을 비우고(`guest`) 로그인 화면
 * `/login?reason=expired` 로 **기록을 바꿔** 간다 — 뒤로 가기로 만료된 화면에 돌아와 다시 튕기지 않게 한다(회원 가드와 같은 이유).
 * 바꿔 가는 일은 앱 안 이동 기록을 거친다(`useNavTrail().replace`) — 그래서 레이아웃에서 `NavTrailProvider` 안에 둔다.
 * 로그인 화면(S13-1)이 그 쿼리로 "다시 로그인해 주세요" 토스트를 띄운다. 이동은 Next 라우터로 한다.
 *
 * 목 재현 입력 `?mock-session=expired` 는 **하이드레이션을 마친 뒤에** 알린다. 하이드레이션 첫 커밋에서는 Next 가 아직 history 를
 * 감싸지 않았다(docs/conventions.md) — 회원 가드(`features/me/member-gate.ts`)와 같은 때 보낸다.
 * 이동한 주소에는 덮어쓰기 쿼리가 없어 다시 만료되지 않는다.
 *
 * 세션이 먼저 비회원이 되어 회원 화면의 가드가 같은 때 다른 곳으로 보내려 해도, 가드는 만료 진행 표시(`isSessionExpiring`)를 보고
 * 만료 주소로 보내거나 멈춘다. 표시는 로그인 화면(`/login`)에 닿으면 끈다 — 이미 로그인 화면에서 만료돼도 끈다.
 * 로그인 화면을 거치지 않고 다시 회원이 돼도(목 세션이 비회원이 아니게 되면) 끈다. `?mock-auth=` 덮어쓰기가 아니라 세션 값을 본다
 *
 * `useSearchParams` 를 읽으므로 레이아웃에서는 `<Suspense>` 로 감싼다(정적 생성이 멈추지 않게).
 */
export function SessionExpiryWatcher(): null {
  const { replace } = useNavTrail()
  const pathname = usePathname()
  const hydrated = useHydrated()
  // 만료를 받은 횟수. 이미 로그인 화면에서 만료돼 경로가 그대로여도 아래 표시 끄기를 다시 돌린다
  const [expiredCount, setExpiredCount] = useState(0)
  const mockExpired = useSearchParams().get(MOCK_SESSION_PARAM) === MOCK_SESSION_EXPIRED

  useEffect(
    () =>
      onSessionExpired(() => {
        expireMockSession()
        replace(LOGIN_EXPIRED_PATH)
        setExpiredCount((count) => count + 1)
      }),
    [replace],
  )

  useEffect(() => {
    if (pathname === LOGIN_PATH) clearSessionExpiring()
  }, [pathname, expiredCount])

  // 서버는 목 세션을 몰라 비회원으로 본다(서버에서는 표시도 꺼져 있다)
  const session = useSyncExternalStore(subscribeMockSession, getMockSession, () => 'guest')
  useEffect(() => {
    if (session !== 'guest') clearSessionExpiring()
  }, [session])

  useEffect(() => {
    if (hydrated && mockExpired) notifySessionExpired()
  }, [hydrated, mockExpired])

  return null
}
