'use client'

import { useEffect, useState } from 'react'
import { usePathname, useSearchParams } from 'next/navigation'

import {
  LOGIN_EMAIL_PATH,
  LOGIN_PATH,
  SIGNUP_ACCOUNT_PATH,
  SIGNUP_CODE_PATH,
  SIGNUP_EMAIL_PATH,
  START_PATH,
} from '@/features/onboarding/paths'
import { navHref } from '@/lib/nav'
import { useNavTrail } from '@/lib/use-nav-trail'

import type { MockAuthState } from './auth-client'
import { isResetDone } from './login-notice'
import { afterLoginQuery, loginReturnFromSearch } from './login-return'
import { carriedParams } from './required-steps'
import { useAuth, useAuthSettled } from './use-auth'

/* ── 회원이 연 로그인 · 가입 화면은 홈으로 (#127) ─────────────────────────────────────────────────
 *
 * 내 정보 가드(`features/me/member-gate.ts`, 비회원 → 로그인)의 반대 방향이다. 회원(건강정보 동의 전 포함)이 시작 · 로그인 · 가입 화면을
 * 열면 `?next=`(허용 목록 안) 또는 홈으로 기록을 바꿔 간다. 로그인 → 홈 → 브라우저 뒤로 하면 로그인 화면이 다시 보이던 문제(점검 F3)도 이것으로 홈에 간다.
 *
 * 대상은 시작(S01) · 로그인 방법 고르기(S13-1) · 이메일 로그인(S13-5) · 이메일 가입(S13-2~4)이다. 빼는 것과 이유:
 * - 비밀번호 재설정(`/password/reset*`): 회원도 비밀번호 변경(`/me/password`)의 "비밀번호를 잊었어요" 로 들어온다.
 * - 재설정을 마친 이메일 로그인(`/login/email?reason=reset-done`): 재설정의 끝 화면이다. 회원을 홈으로 보내면 "비밀번호를 바꿨어요" 안내를
 *   잃는다. 재설정에 성공하면 서버가 그 계정의 모든 기기를 로그아웃하고, 재설정한 계정이 이 탭 계정이면 이 탭 세션도 비워 비회원으로
 *   닿는다(#166, `resetPassword`). 다른 계정을 재설정한 회원은 회원인 채 닿고 이 가드는 보내지 않는다 — 안내를 보고 그 계정으로 로그인할 수 있다.
 * - 가입 마무리(`/setup/*`) · 약관 재동의(`/terms/reconsent`) · 동네 다시 고르기(`/setup/region?reselect=1`) · 둘러보기(`/browse/region`):
 *   가입 중 S02-3 에서 회원이 되고, 재동의 · 다시 고르기는 회원이 거치는 화면이다.
 *
 * **화면에 닿을 때의 회원 상태로 한 번만 판단한다.** 그 화면에 있는 동안 회원이 되는 것(이메일 로그인 성공)은 화면이 스스로 이동하므로
 * 끼어들지 않는다 — 두 이동이 겹치지 않고, 가입 · 로그인 흐름이 바뀌어도 이 가드가 흐름을 끊지 않는다.
 * 로그인 만료(`/login?reason=expired`)는 만료 감시가 세션을 먼저 비우고 보내므로 비회원으로 닿아 그대로 보인다.
 *
 * **회원 상태가 정해진 뒤에만 판단한다**(`useAuthSettled`, `member-gate.ts` 와 같은 이유 — 서버 · 첫 그림 · 실데이터 복원 중은 늘 비회원).
 * 실데이터 회원이 로그인 화면을 새로고침하면 복원이 끝난 그림을 "닿음" 으로 본다. 레이아웃은 첫 진입 화면 사이를
 * 오가도 다시 그려지지 않으므로 경로가 바뀔 때마다 "닿음" 으로 본다(쿼리만 바뀌면 같은 화면).
 *
 * 보낼 곳에는 둘러보기 동네와 QA 덮어쓰기(`?mock-auth=` 등)를 남긴다(`carriedParams`). 세션을 바꾸는 이동이 아니라 같은 회원으로 보여야 하고,
 * `/login?mock-auth=member` 에서 덮어쓰기를 버리면 홈이 비회원으로 보인다.
 *
 * **보고하려던 로그인(`?intent=report`, #136)이어도 보고 진입(`report=start`)을 붙이지 않는다** — `next`(허용 목록) · 동네 · QA 덮어쓰기만 남기고
 * `intent` 는 버린다. 이 가드는 닿을 때의 상태로 한 번만 판단해 로그인 성공 이동(화면이 스스로 보고 진입으로 감)과 겹치지 않는다. 회원이
 * `/login?intent=report` 에 닿는 것은 사실상 로그인 성공 뒤 브라우저 뒤로뿐이고, 그때 보고 시트를 다시 열면 뒤로 가려는 사람을 붙잡는다.
 * 같은 까닭으로 `intent` 를 `carriedParams` 에도 넣지 않는다(그 목록은 재동의 · 동네 다시 고르기 화면을 오가며 남기는 쿼리다).
 */

/** 회원이 열면 홈(또는 `next`)으로 보내는 경로 */
export const GUEST_ONLY_PATHS: readonly string[] = [
  START_PATH,
  LOGIN_PATH,
  LOGIN_EMAIL_PATH,
  SIGNUP_EMAIL_PATH,
  SIGNUP_CODE_PATH,
  SIGNUP_ACCOUNT_PATH,
]

/** 회원이 열면 보낼 화면인지. 재설정을 마친 이메일 로그인(`?reason=reset-done`)은 빼 둔다 */
export function isGuestOnly(pathname: string, searchParams: Pick<URLSearchParams, 'get'>): boolean {
  if (!GUEST_ONLY_PATHS.includes(pathname)) return false
  return !(pathname === LOGIN_EMAIL_PATH && isResetDone(searchParams.get('reason') ?? undefined))
}

/**
 * 회원을 보낼 곳. `?next=` 가 허용 목록 안이면 그곳, 아니면 홈이다. 둘러보기 동네 · QA 덮어쓰기를 남기고,
 * 보고하려던 로그인이어도 보고 진입은 붙이지 않는다(위 — 브라우저 뒤로를 붙잡지 않게)
 */
export function memberTarget(searchParams: Pick<URLSearchParams, 'get'>): string {
  const loginReturn = loginReturnFromSearch(searchParams)
  const query = afterLoginQuery({ ...loginReturn, intent: null })
  carriedParams(searchParams).forEach((value, key) => {
    if (!query.has(key)) query.set(key, value)
  })
  return navHref(loginReturn.next, query.toString())
}

type Arrival = { path: string; auth: MockAuthState }

/**
 * 첫 진입 레이아웃(`app/(onboarding)/layout.tsx`)에 두는 가드. 보내는 동안에는 화면을 바탕색으로 덮어 로그인 화면이 비치지 않게 한다
 * (앱 안 이동 · 브라우저 뒤로로 닿으면 첫 그림부터 덮인다). 주소 쿼리를 읽으므로 레이아웃에서는 `<Suspense>` 로 감싼다.
 */
export function GuestOnlyGate() {
  const { replace } = useNavTrail()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const settled = useAuthSettled()
  const auth = useAuth()

  // 이 경로에 닿았을 때의 회원 상태. 이전 그림의 값을 이어 쓰는 상태라 그리는 중에 고친다(React "이전 렌더의 정보 저장" 방식)
  const [arrival, setArrival] = useState<Arrival | null>(null)
  if (settled && arrival?.path !== pathname) setArrival({ path: pathname, auth })

  const target =
    arrival?.path === pathname && arrival.auth !== 'guest' && isGuestOnly(pathname, searchParams)
      ? memberTarget(searchParams)
      : null

  useEffect(() => {
    if (target) replace(target)
  }, [target, replace])

  return target ? <div aria-hidden="true" className="fixed inset-0 z-20 bg-bg" /> : null
}
