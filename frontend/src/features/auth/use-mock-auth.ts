'use client'

import { useSyncExternalStore } from 'react'
import { useSearchParams } from 'next/navigation'

import {
  getMockSession,
  MOCK_AUTH_STATES,
  type MockAuthState,
  subscribeMockSession,
} from './auth-client'

/** QA 용 목 회원 상태 덮어쓰기 쿼리 (`?mock-auth=guest|member|member-no-consent`) */
export const MOCK_AUTH_PARAM = 'mock-auth'

/** 쿼리 값을 목 회원 상태로. 없거나 모르는 값이면 null 이다(무시한다) */
export function parseMockAuth(value: string | null): MockAuthState | null {
  return MOCK_AUTH_STATES.find((state) => state === value) ?? null
}

// 서버는 모듈 메모리의 세션을 모른다. 서버와 첫 그림(하이드레이션)은 비로그인으로 그린다
const serverSnapshot = (): MockAuthState => 'guest'

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
