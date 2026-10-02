'use client'

import { useMemo } from 'react'

import type { MemberRegion } from '@/features/auth/auth-client'
import type { LoadStatus } from '@/features/auth/member-info'
import { useAuth } from '@/features/auth/use-auth'
import { useMemberInfo } from '@/features/auth/use-member-info'
import { useMockProfile } from '@/features/auth/use-mock-auth'
import { useDataSource } from '@/lib/use-data-source'

/*
 * 둘러보기 동네와 내 동네 (#141).
 *
 * - **둘러보기 동네**: 주소 `?region=` 의 동네. 머리줄 동네 이름(동네 바꾸기)으로 바꾸고 회원 · 비회원 모두 쓴다. 서버 페이지가 확인해 넘긴다.
 * - **내 동네(보고 동네)**: 회원이 고른 동네. 보고 · 알림의 기준이고 내 정보의 `/me/region` 에서만 바꾼다. 실데이터는 회원 정보 저장소의
 *   `GET /api/v1/members/me/region`(#164), 목데이터는 목 세션 프로필이다. 서버는 세션을 몰라 화면(클라이언트)이 읽는다 — 하이드레이션
 *   첫 그림은 늘 비회원이라 회원은 처음 연 그림에서 목 예시 이름이 보였다가 내 동네로 바뀐다(알림 종 · 보고 버튼 글자와 같다).
 */

/**
 * 회원의 내 동네. 비회원이거나 모르면 null 이다 — 목: 가입 없이 이메일 로그인 · `?mock-auth=` 덮어쓰기,
 * 실데이터: 아직 고르지 않음 · 읽는 중 · 읽지 못함(`useMemberRegionStatus` 로 가린다) · 행정동 서비스에 코드가 없어 이름을 모름.
 * 폐지된 동네도 이름을 알면 돌려준다(다시 고르기는 `useMemberRequirements` 가 정한다).
 */
export function useMemberRegion(): MemberRegion | null {
  const source = useDataSource()
  const auth = useAuth()
  const profile = useMockProfile()
  const memberInfo = useMemberInfo()
  const load = memberInfo?.region
  // 저장소 값이 바뀔 때만 새 객체다 — 내 동네를 effect 의존값에 넣는 화면이 다시 돌지 않게 한다
  const apiRegion = useMemo<MemberRegion | null>(() => {
    const region = load?.status === 'ready' ? load.value : null
    return region?.name ? { code: region.code, name: region.name } : null
  }, [load])
  if (source === 'api') return auth === 'guest' ? null : apiRegion
  return profile?.region ?? null
}

/** 내 동네를 읽었는지. 목데이터 · 비회원은 늘 `ready` 다. 실데이터 회원은 내 동네 요청의 상태다(`retryMemberInfo()` 로 다시 읽는다) */
export function useMemberRegionStatus(): LoadStatus {
  const source = useDataSource()
  const auth = useAuth()
  const memberInfo = useMemberInfo()
  if (source === 'mock' || auth === 'guest') return 'ready'
  return memberInfo?.region.status ?? 'loading'
}

/**
 * 머리줄 · 홈에 보일 동네 이름. 둘러보기 동네(`regionCode`, 서버가 확인한 코드)가 있으면 그 이름(`regionName`) 그대로,
 * 없으면 회원의 내 동네, 그것도 없으면(비회원 · 내 동네를 모름) 서버가 준 이름(목 예시)이다.
 */
export function useShownRegionName(regionName: string, regionCode: string | null): string {
  const memberRegion = useMemberRegion()
  return regionCode ? regionName : (memberRegion?.name ?? regionName)
}
