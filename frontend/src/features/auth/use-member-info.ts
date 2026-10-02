'use client'

import { useSyncExternalStore } from 'react'

import { useSession } from '@/lib/session/use-session'

import {
  getMemberInfoSnapshot,
  memberInfoOf,
  type MemberInfoSnapshot,
  subscribeMemberInfo,
} from './member-info'

/** 서버는 회원 정보를 모른다. 서버 그림과 하이드레이션 첫 그림은 늘 null 이다 */
const serverSnapshot = (): MemberInfoSnapshot | null => null

/**
 * 지금 세션의 회원 정보(실데이터 모드, `member-info.ts`). 비회원이거나 아직 이 회원 것을 읽기 시작하지 않았으면 null 이다.
 * 목데이터 모드에서는 쓰지 않는다 — 출처를 가리는 쪽(`useMockProfile` · `useMemberRegion` · `useMemberRequirements`)이 고른다
 */
export function useMemberInfo(): MemberInfoSnapshot | null {
  const session = useSession()
  const info = useSyncExternalStore(subscribeMemberInfo, getMemberInfoSnapshot, serverSnapshot)
  return memberInfoOf(session, info)
}
