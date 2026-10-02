'use client'

import type { MemberRegion } from '@/features/auth/auth-client'
import { useMockProfile } from '@/features/auth/use-mock-auth'

/*
 * 둘러보기 동네와 내 동네 (#141).
 *
 * - **둘러보기 동네**: 주소 `?region=` 의 동네. 머리줄 동네 이름(동네 바꾸기)으로 바꾸고 회원 · 비회원 모두 쓴다. 서버 페이지가 확인해 넘긴다.
 * - **내 동네(보고 동네)**: 회원 프로필의 동네(목 `useMockProfile`, 연동 때 `GET /api/v1/members/me`). 보고 · 알림의 기준이고
 *   내 정보의 `/me/region` 에서만 바꾼다. 서버는 목 세션을 몰라 화면(클라이언트)이 읽는다 — 하이드레이션 첫 그림은 늘 비회원이라
 *   회원은 처음 연 그림에서 목 예시 이름이 보였다가 내 동네로 바뀐다(알림 종 · 보고 버튼 글자와 같다).
 */

/** 회원의 내 동네. 비회원이거나 목이 모르면(가입 없이 이메일 로그인 · `?mock-auth=` 덮어쓰기) null 이다 */
export function useMemberRegion(): MemberRegion | null {
  return useMockProfile()?.region ?? null
}

/**
 * 머리줄 · 홈에 보일 동네 이름. 둘러보기 동네(`regionCode`, 서버가 확인한 코드)가 있으면 그 이름(`regionName`) 그대로,
 * 없으면 회원의 내 동네, 그것도 없으면(비회원 · 내 동네를 모름) 서버가 준 이름(목 예시)이다.
 */
export function useShownRegionName(regionName: string, regionCode: string | null): string {
  const memberRegion = useMemberRegion()
  return regionCode ? regionName : (memberRegion?.name ?? regionName)
}
