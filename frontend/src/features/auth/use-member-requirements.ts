'use client'

import { useSyncExternalStore } from 'react'
import { useSearchParams } from 'next/navigation'

import { useSession } from '@/lib/session/use-session'
import { useDataSource } from '@/lib/use-data-source'

import { getMockProfile, type MockProfile, subscribeMockSession } from './auth-client'
import {
  type MemberRequirements,
  memberRequirements,
  MOCK_REQUIRED_PARAM,
  parseMockRequired,
  sessionRequirements,
} from './required-steps'
import { useAuth } from './use-auth'
import { useMemberInfo } from './use-member-info'

const serverProfileSnapshot = (): MockProfile | null => null

/**
 * 지금 회원이 먼저 거칠 화면.
 *
 * - 실데이터: 세션 요약 · 회원 정보 저장소로 정한다(`sessionRequirements` — 재동의할 항목이 있으면 약관 재동의, 내 동네가
 *   폐지됐으면 동네 다시 고르기). 내 동네를 읽는 중이면 동네 조건을 판단하지 않는다(`settled: false`). `?mock-required=` 는 듣지 않는다
 * - 목데이터: 회원 상태(`useAuth`) · 목 세션 프로필의 조건 · QA 덮어쓰기(`?mock-required=`)로 정한다. 프로필은 `?mock-provider=`
 *   덮어쓰기와 무관하게 목 세션 것을 읽는다. 응답 뒤 갈 곳(`targetAfter`)도 같은 값을 읽는다
 *
 * 서버와 하이드레이션 첫 그림에서는 세션을 몰라 비회원(조건 없음)으로 본다 — 이동은 `useAuthSettled` 가 true 인 그림에서만 정한다.
 */
export function useMemberRequirements(): MemberRequirements {
  const source = useDataSource()
  const session = useSession()
  const auth = useAuth()
  const override = parseMockRequired(useSearchParams().get(MOCK_REQUIRED_PARAM))
  const profile = useSyncExternalStore(subscribeMockSession, getMockProfile, serverProfileSnapshot)
  const memberInfo = useMemberInfo()
  if (source === 'api') return sessionRequirements(session, memberInfo)
  return memberRequirements(auth, profile, override)
}
