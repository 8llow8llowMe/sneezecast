'use client'

import { useRouter, useSearchParams } from 'next/navigation'

import { browseRegionHref } from './browse-return'

/**
 * 머리줄 동네 이름(동네 바꾸기)을 눌렀을 때 부를 함수. 둘러볼 동네 고르기(`/browse/region?next=…`)로 간다(#141).
 * 둘러보기 동네만 바꾸고 회원의 내 동네는 바꾸지 않는다 — 회원 · 비회원 같다. 이동은 머리줄 보고 버튼처럼 `router.push` 다.
 *
 * - `next`: 고른 뒤 돌아올 화면(`BROWSE_NEXT_PATHS`). 동네 안내는 홈을 넘긴다
 * - `regionCode`: 화면이 확인한 둘러보기 동네. 고르지 않고 뒤로 갈 때 돌아갈 주소에 남긴다
 */
export function useBrowseRegion(next: string, regionCode: string | null): () => void {
  const router = useRouter()
  const searchParams = useSearchParams()
  return () => router.push(browseRegionHref(next, regionCode, searchParams))
}
