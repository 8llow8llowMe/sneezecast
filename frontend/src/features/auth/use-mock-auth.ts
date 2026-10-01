'use client'

import { useSyncExternalStore } from 'react'
import { useSearchParams } from 'next/navigation'

import {
  EXAMPLE_PROFILES,
  getMockProfile,
  getMockSession,
  MOCK_AUTH_STATES,
  type MockAuthState,
  type MockProfile,
  subscribeMockSession,
} from './auth-client'

/** QA 용 목 회원 상태 덮어쓰기 쿼리 (`?mock-auth=guest|member|member-no-consent`) */
export const MOCK_AUTH_PARAM = 'mock-auth'

/** 쿼리 값을 목 회원 상태로. 없거나 모르는 값이면 null 이다(무시한다) */
export function parseMockAuth(value: string | null): MockAuthState | null {
  return MOCK_AUTH_STATES.find((state) => state === value) ?? null
}

/** QA 용 목 로그인 방법 덮어쓰기 쿼리 (`?mock-provider=email|kakao`). 내 정보(S10)의 이메일 · 카카오 화면을 고른다 */
export const MOCK_PROVIDER_PARAM = 'mock-provider'

/** 쿼리 값을 로그인 방법으로. 없거나 모르는 값이면 null 이다(무시한다) */
export function parseMockProvider(value: string | null): MockProfile['provider'] | null {
  return value === 'email' || value === 'kakao' ? value : null
}

// 서버는 모듈 메모리의 세션을 모른다. 서버와 첫 그림(하이드레이션)은 비로그인으로 그린다
const serverSnapshot = (): MockAuthState => 'guest'
const serverProfileSnapshot = (): MockProfile | null => null

/**
 * 지금 회원 · 동의 상태 (목). 정하는 순서는 ① 주소 `?mock-auth=` (QA 덮어쓰기) ② `auth-client` 목 세션 ③ `guest` 다.
 *
 * 덮어쓰기는 서버도 같은 주소를 읽어 첫 그림부터 맞는다. 목 세션은 서버가 모르므로 페이지를 처음 열 때(하이드레이션)는
 * `guest` 로 그린 뒤 목 세션으로 다시 그린다 — 다만 목 세션은 새로고침하면 비므로 처음 열 때는 늘 `guest` 이고,
 * 앱 안에서 홈으로 옮겨 올 때(가입 · 로그인 뒤)는 하이드레이션이 아니라 바로 목 세션으로 그린다.
 * 연동 때 실제 세션 훅으로 바꾼다 (docs/design/SCREENS.md "목 회원 상태").
 */
export function useMockAuth(): MockAuthState {
  const override = parseMockAuth(useSearchParams().get(MOCK_AUTH_PARAM))
  const session = useSyncExternalStore(subscribeMockSession, getMockSession, serverSnapshot)
  return override ?? session
}

/**
 * 내 정보에 보일 프로필 (목). 비회원이면 null 이다.
 *
 * 정하는 순서는 ① 주소 `?mock-provider=` (QA 덮어쓰기) — 목 세션 프로필의 로그인 방법이 같으면 그 프로필, 다르면 그 방법의
 * 예시 프로필 ② 목 세션 프로필 ③ 이메일 예시 프로필(`?mock-auth=` 덮어쓰기만 있어 세션 프로필이 없을 때)이다.
 * 연동 때 `GET /api/v1/members/me` 를 읽는 훅으로 바꾸고 덮어쓰기를 지운다.
 */
export function useMockProfile(): MockProfile | null {
  const auth = useMockAuth()
  const override = parseMockProvider(useSearchParams().get(MOCK_PROVIDER_PARAM))
  const profile = useSyncExternalStore(subscribeMockSession, getMockProfile, serverProfileSnapshot)
  if (auth === 'guest') return null
  if (override) return profile?.provider === override ? profile : EXAMPLE_PROFILES[override]
  return profile ?? EXAMPLE_PROFILES.email
}
