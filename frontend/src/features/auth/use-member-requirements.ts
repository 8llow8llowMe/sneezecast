'use client'

import { useSyncExternalStore } from 'react'
import { useSearchParams } from 'next/navigation'

import { getMockProfile, type MockProfile, subscribeMockSession } from './auth-client'
import {
  type MemberRequirements,
  memberRequirements,
  MOCK_REQUIRED_PARAM,
  parseMockRequired,
} from './required-steps'
import { useMockAuth } from './use-mock-auth'

const serverProfileSnapshot = (): MockProfile | null => null

/**
 * 지금 회원이 먼저 거칠 화면 (목). 회원 상태(`useMockAuth`) · 목 세션 프로필의 조건 · QA 덮어쓰기(`?mock-required=`)로 정한다.
 *
 * 서버와 하이드레이션 첫 그림에서는 목 세션을 몰라 비회원(조건 없음)으로 본다 — 이동은 `useHydrated` 가 true 인 그림에서만 정한다.
 * 프로필은 `?mock-provider=` 덮어쓰기와 무관하게 목 세션 것을 읽는다. 응답 뒤 갈 곳(`targetAfter`)도 같은 값을 읽는다.
 * 연동 때 `GET /api/v1/members/me` 의 조건 값을 읽는 훅으로 바꾸고 덮어쓰기를 지운다.
 */
export function useMemberRequirements(): MemberRequirements {
  const auth = useMockAuth()
  const override = parseMockRequired(useSearchParams().get(MOCK_REQUIRED_PARAM))
  const profile = useSyncExternalStore(subscribeMockSession, getMockProfile, serverProfileSnapshot)
  return memberRequirements(auth, profile, override)
}
