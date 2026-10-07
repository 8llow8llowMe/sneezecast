'use client'

import { useSyncExternalStore } from 'react'
import { useSearchParams } from 'next/navigation'

import type { SessionRole, SessionSnapshot } from '@/lib/session/session-store'
import { useSession } from '@/lib/session/use-session'
import { useDataSource } from '@/lib/use-data-source'
import { useHydrated } from '@/lib/use-hydrated'

import {
  getMockSession,
  MOCK_AUTH_STATES,
  type MockAuthState,
  subscribeMockSession,
} from './auth-client'
import { MOCK_AUTH_PARAM, MOCK_ROLE_PARAM } from './mock-params'

/* ── 회원 · 동의 상태 (실데이터 · 목데이터) ──────────────────────────────────────────────────────
 *
 * 화면은 출처를 몰라도 되게 이 훅 하나로 회원 상태를 읽는다. 실데이터(`api`)면 세션 저장소(`src/lib/session/`)의 회원 요약,
 * 목데이터(`mock`)면 지금처럼 `?mock-auth=` 덮어쓰기 → 목 세션이다. **QA 덮어쓰기는 목데이터 모드에서만 듣는다** — 실데이터에서
 * 들으면 주소만으로 회원 · 동의 확인을 건너뛴다.
 */

export type AuthState = MockAuthState

/** QA 용 목 회원 상태 덮어쓰기 쿼리. 서버에서도 읽혀 `'use client'` 가 없는 `mock-params.ts` 에 둔다(#206) */
export { MOCK_AUTH_PARAM }

/** 쿼리 값을 목 회원 상태로. 없거나 모르는 값이면 null 이다(무시한다) */
export function parseMockAuth(value: string | null): MockAuthState | null {
  return MOCK_AUTH_STATES.find((state) => state === value) ?? null
}

/** 세션 스냅숏을 회원 상태로. 회원이 아니거나(복원 중 포함) 아직 모르면 `guest` 다 */
export function authStateOf(session: SessionSnapshot): AuthState {
  if (session.status !== 'member') return 'guest'
  return session.summary.reportWritable ? 'member' : 'member-no-consent'
}

// 서버는 모듈 메모리의 목 세션을 모른다. 서버와 첫 그림(하이드레이션)은 비회원으로 그린다
const serverMockSnapshot = (): MockAuthState => 'guest'

/**
 * 지금 회원 · 동의 상태.
 *
 * - 실데이터: 세션 저장소의 회원 요약(`reportWritable` 이면 `member`, 아니면 `member-no-consent`). 복원 중 · 서버 그림은 `guest`
 * - 목데이터: ① 주소 `?mock-auth=` ② `auth-client` 목 세션 ③ `guest` (docs/design/SCREENS.md "목 회원 상태")
 *
 * 서버와 하이드레이션 첫 그림은 출처와 무관하게 세션을 몰라 `guest` 다(목 덮어쓰기는 서버도 주소를 읽어 맞는다).
 * 이 값으로 이동을 정하는 가드는 `useAuthSettled()` 가 true 일 때만 판단한다.
 */
export function useAuth(): AuthState {
  const source = useDataSource()
  const session = useSession()
  const override = parseMockAuth(useSearchParams().get(MOCK_AUTH_PARAM))
  const mockSession = useSyncExternalStore(subscribeMockSession, getMockSession, serverMockSnapshot)
  if (source === 'api') return authStateOf(session)
  return override ?? mockSession
}

/**
 * 회원 상태가 정해졌는지 — 가드가 이 값이 true 인 그림에서만 이동을 정한다.
 * 하이드레이션을 마쳤고, 목데이터이거나 실데이터 세션이 복원을 마쳤을 때(`member` · `guest`)다.
 * 실데이터의 `idle`(복원 전) · `restoring`(재발급 대기)에서 로그인으로 보내면 회원도 튕긴다.
 */
export function useAuthSettled(): boolean {
  const hydrated = useHydrated()
  const source = useDataSource()
  const { status } = useSession()
  return hydrated && (source === 'mock' || status === 'member' || status === 'guest')
}

const MOCK_ROLES: readonly SessionRole[] = ['USER', 'OPERATOR', 'ADMIN']

/** 쿼리 값(`user` · `operator` · `admin`)을 역할로. 없거나 모르는 값이면 null 이다(무시한다) */
export function parseMockRole(value: string | null): SessionRole | null {
  return MOCK_ROLES.find((role) => role.toLowerCase() === value) ?? null
}

/**
 * 지금 회원의 역할(#219, 운영자 화면 가드). 비회원(복원 중 포함)이면 null 이다.
 *
 * - 실데이터: 세션 저장소의 회원 요약(`role` — 로그인 · 재발급 응답). 서버가 운영자 API 마다 다시 확인하므로 화면 가드는 길 안내일 뿐이다
 * - 목데이터: ① 주소 `?mock-role=` ② 일반 회원 `USER`. 목 세션에는 역할이 없다(목 로그인은 모두 일반 회원)
 *
 * **`?mock-role=` 은 목데이터 모드에서만 듣는다** — 실데이터에서 들으면 주소만으로 운영자 화면이 열린다(`useAuth` 의 덮어쓰기와 같다).
 */
export function useSessionRole(): SessionRole | null {
  const source = useDataSource()
  const session = useSession()
  const auth = useAuth()
  const override = parseMockRole(useSearchParams().get(MOCK_ROLE_PARAM))
  if (source === 'api') return session.status === 'member' ? session.summary.role : null
  if (auth === 'guest') return null
  return override ?? 'USER'
}
