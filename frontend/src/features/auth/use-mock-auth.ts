'use client'

import { useSyncExternalStore } from 'react'
import { useSearchParams } from 'next/navigation'

import { useDataSource } from '@/lib/use-data-source'

import {
  EXAMPLE_PROFILES,
  getMockProfile,
  type MockProfile,
  subscribeMockSession,
} from './auth-client'
import { useAuth } from './use-auth'

/**
 * 지금 회원 · 동의 상태. 실데이터 · 목데이터를 함께 다루는 `useAuth`(`use-auth.ts`)와 같다 — 호출하는 화면을 고치지 않으려고
 * 옛 이름으로 다시 내보낸다. 이름 정리는 로그인 · 가입 연동 PR 에서 한다.
 */
export { MOCK_AUTH_PARAM, parseMockAuth, useAuth as useMockAuth } from './use-auth'

/** QA 용 목 로그인 방법 덮어쓰기 쿼리 (`?mock-provider=email|kakao`). 내 정보(S10)의 이메일 · 카카오 화면을 고른다. 목데이터 모드에서만 듣는다 */
export const MOCK_PROVIDER_PARAM = 'mock-provider'

/** 쿼리 값을 로그인 방법으로. 없거나 모르는 값이면 null 이다(무시한다) */
export function parseMockProvider(value: string | null): MockProfile['provider'] | null {
  return value === 'email' || value === 'kakao' ? value : null
}

// 서버는 모듈 메모리의 세션을 모른다. 서버와 첫 그림(하이드레이션)은 프로필 없이 그린다
const serverProfileSnapshot = (): MockProfile | null => null

/**
 * 내 정보에 보일 프로필 (목). 비회원이면 null 이다.
 *
 * 정하는 순서는 ① 주소 `?mock-provider=` (QA 덮어쓰기, 목데이터 모드만) — 목 세션 프로필의 로그인 방법이 같으면 그 프로필, 다르면 그 방법의
 * 예시 프로필 ② 목 세션 프로필 ③ 이메일 예시 프로필(`?mock-auth=` 덮어쓰기만 있어 세션 프로필이 없을 때)이다.
 * 연동 때 `GET /api/v1/members/me` 를 읽는 훅으로 바꾼다.
 */
export function useMockProfile(): MockProfile | null {
  const auth = useAuth()
  const source = useDataSource()
  const param = parseMockProvider(useSearchParams().get(MOCK_PROVIDER_PARAM))
  const override = source === 'mock' ? param : null
  const profile = useSyncExternalStore(subscribeMockSession, getMockProfile, serverProfileSnapshot)
  if (auth === 'guest') return null
  if (override) return profile?.provider === override ? profile : EXAMPLE_PROFILES[override]
  return profile ?? EXAMPLE_PROFILES.email
}
