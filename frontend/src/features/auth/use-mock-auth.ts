'use client'

import { useMemo, useSyncExternalStore } from 'react'
import { useSearchParams } from 'next/navigation'

import { useDataSource } from '@/lib/use-data-source'

import {
  EXAMPLE_PROFILES,
  getMockProfile,
  type MockProfile,
  subscribeMockSession,
} from './auth-client'
import type { LoadStatus, MemberInfoSnapshot } from './member-info'
import { useAuth } from './use-auth'
import { useMemberInfo } from './use-member-info'

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
 * 실데이터 회원 정보를 내 정보 화면의 프로필 모양으로 옮긴다. 내 정보를 아직 읽지 못했으면(읽는 중 · 실패) null 이다 —
 * **예시 프로필로 채우지 않는다.** 내 동네는 따로 읽으므로 아직 모르면 null(폐지 표시 없음)이다
 */
export function profileOfMemberInfo(memberInfo: MemberInfoSnapshot | null): MockProfile | null {
  if (memberInfo?.info.status !== 'ready') return null
  const info = memberInfo.info.value
  const region = memberInfo.region.status === 'ready' ? memberInfo.region.value : null
  return {
    provider: info.provider,
    email: info.email,
    nickname: info.nickname,
    hasPassword: info.hasPassword,
    // 이름을 모르는 동네(행정동 서비스에 코드가 없음)는 보일 이름이 없어 모르는 동네로 둔다. 폐지 표시는 남긴다
    region: region?.name ? { code: region.code, name: region.name } : null,
    regionAbolished: region?.abolished === true,
    termsReconsentRequired: info.pendingConsents.length > 0,
  }
}

/**
 * 내 정보에 보일 프로필. 비회원이면 null 이다.
 *
 * - 실데이터: 회원 정보 저장소의 내 정보(`GET /api/v1/members/me`)와 내 동네를 옮긴 값이다(`profileOfMemberInfo`). 아직 읽지 못했으면
 *   (읽는 중 · 실패) null 이다 — 둘은 `useMockProfileStatus()` 로 가린다. `?mock-provider=` 는 듣지 않는다
 * - 목데이터: ① 주소 `?mock-provider=` (QA 덮어쓰기) — 목 세션 프로필의 로그인 방법이 같으면 그 프로필, 다르면 그 방법의
 *   예시 프로필 ② 목 세션 프로필 ③ 이메일 예시 프로필(`?mock-auth=` 덮어쓰기만 있어 세션 프로필이 없을 때)이다.
 *
 * 이름(`Mock`)은 병렬 연동 이슈와 import 가 겹치지 않게 그대로 두었다 — 정리는 후속이다.
 */
export function useMockProfile(): MockProfile | null {
  const auth = useAuth()
  const source = useDataSource()
  const param = parseMockProvider(useSearchParams().get(MOCK_PROVIDER_PARAM))
  const profile = useSyncExternalStore(subscribeMockSession, getMockProfile, serverProfileSnapshot)
  const memberInfo = useMemberInfo()
  // 회원 정보가 바뀔 때만 새 객체다 — 다시 그릴 때마다 프로필이 바뀌어 보이지 않게 한다
  const apiProfile = useMemo(() => profileOfMemberInfo(memberInfo), [memberInfo])
  if (auth === 'guest') return null
  if (source === 'api') return apiProfile
  if (param) return profile?.provider === param ? profile : EXAMPLE_PROFILES[param]
  return profile ?? EXAMPLE_PROFILES.email
}

/**
 * 프로필을 읽었는지. 목데이터 · 비회원은 늘 `ready` 다(읽을 것이 없다). 실데이터 회원은 내 정보 요청의 상태다 —
 * `loading`(이 회원 것을 아직 받지 못함) · `failed`(받지 못함, `retryMemberInfo()` 로 다시 읽는다) · `ready`
 */
export function useMockProfileStatus(): LoadStatus {
  const auth = useAuth()
  const source = useDataSource()
  const memberInfo = useMemberInfo()
  if (source === 'mock' || auth === 'guest') return 'ready'
  return memberInfo?.info.status ?? 'loading'
}
