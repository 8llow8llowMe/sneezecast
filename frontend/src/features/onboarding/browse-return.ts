import { carriedParams, NEXT_PARAM } from '@/features/auth/required-steps'
import { navHref } from '@/lib/nav'

import { BROWSE_REGION_PATH, HOME_PATH } from './paths'

/* ── 둘러볼 동네 고르기의 돌아갈 곳 (#141) ──────────────────────────────────────────────────────
 *
 * 머리줄의 동네 이름(동네 바꾸기)은 둘러보기 동네(`?region=`)만 바꾼다 — 회원 · 비회원 같다. 회원의 내 동네(보고 동네)는
 * 내 정보의 `/me/region` 에서 따로 바꾼다. 머리줄이 `/browse/region?next=<지금 화면>` 을 열고, 고르면 그 화면에
 * `?region=<새 코드>` 를 붙여 **기록을 바꿔** 돌아간다 — 뒤로 가기로 고르기 화면에 다시 오지 않는다.
 *
 * - `next` 는 이 허용 목록과 정확히 같은 경로만 받는다(오픈 리다이렉트 방지, 로그인 `safeNextPath` 와 같은 방식). 목록 밖이면
 *   돌아갈 곳이 없는 것으로 보고 시작 화면의 둘러보기(고르면 홈에 쌓아 감 · 뒤로 시작 화면)와 같다.
 * - 로그인용 `NEXT_PATHS` 와 섞지 않는다. 그 목록은 가드가 거는 화면이고, 이 목록은 머리줄에 동네 이름이 있는 화면이다.
 * - 동네 안내(`/notice/[region]/[week]`)는 넣지 않는다. 안내는 경로의 동네 것이라 둘러보기 동네를 바꿔 돌아갈 화면이 아니다 —
 *   머리줄은 홈(`/`)을 돌아갈 곳으로 연다.
 * - 함께 넘기는 쿼리는 QA 용 목 덮어쓰기(`mock-auth` · `mock-provider` · `mock-required`)뿐이다(`carriedParams`). 세션을 바꾸는
 *   이동이 아니라 돌아간 화면이 같은 회원으로 보여야 한다. 열린 시트(`report` · `explain` 등)와 목 자료(`mock`)는 버린다.
 */

/**
 * 고르기를 마치고 돌아갈 수 있는 화면. 주요 메뉴(홈 · 지도) · 공식 정보 · 내 정보와 그 아래 계정 화면이다.
 * 경로는 `MAIN_NAV` · `OFFICIAL_PATH` · `features/me/me-paths.ts` 와 같다(테스트가 맞춰 본다) — 서버 페이지가 읽으므로 클라이언트 모듈을 끌어오지 않게 다시 적는다.
 */
export const BROWSE_NEXT_PATHS: readonly string[] = [
  HOME_PATH,
  '/map',
  '/official',
  '/me',
  '/me/devices',
  '/me/password',
  '/me/region',
  '/me/nickname',
  '/me/reports',
]

const REGION_PARAM = 'region'

/** 둘러볼 동네 고르기에서 읽은 돌아갈 곳 */
export type BrowseReturn = {
  /** 고른 뒤 돌아갈 경로(허용 목록 안) */
  next: string
  /** 지금 둘러보던 동네 코드. 고르지 않고 뒤로 갈 때(주소로 바로 들어옴) 돌아갈 주소에 남긴다. 없으면 null */
  region: string | null
  /** 함께 넘길 QA 덮어쓰기 쿼리(앞 `?` 없이, `region` 은 빠져 있다) */
  carried: string
}

/** 돌아갈 곳이 없으면 null 이다(쿼리가 없거나 허용 목록 밖) */
export function browseReturnFrom(searchParams: Pick<URLSearchParams, 'get'>): BrowseReturn | null {
  const next = searchParams.get(NEXT_PARAM)
  if (next === null || !BROWSE_NEXT_PATHS.includes(next)) return null
  const carried = carriedParams(searchParams)
  carried.delete(REGION_PARAM)
  return { next, region: searchParams.get(REGION_PARAM) || null, carried: carried.toString() }
}

/**
 * 머리줄 동네 이름이 여는 둘러볼 동네 고르기 주소. 홈으로 돌아가도 `next` 를 붙인다 — 없으면 시작 화면의 둘러보기다.
 * `regionCode` 는 화면이 확인한 둘러보기 동네다(없으면 null).
 */
export function browseRegionHref(
  next: string,
  regionCode: string | null,
  searchParams: Pick<URLSearchParams, 'get'>,
): string {
  const query = new URLSearchParams({ [NEXT_PARAM]: next })
  if (regionCode) query.set(REGION_PARAM, regionCode)
  carriedParams(searchParams).forEach((value, key) => {
    if (key !== REGION_PARAM) query.set(key, value)
  })
  return navHref(BROWSE_REGION_PATH, query.toString())
}

/** 돌아갈 주소. `regionCode` 를 둘러보기 동네로 붙이고(없으면 빼고) 덮어쓰기를 남긴다 */
export function browseReturnHref({ next, carried }: BrowseReturn, regionCode: string | null) {
  const query = new URLSearchParams(regionCode ? { [REGION_PARAM]: regionCode } : {})
  new URLSearchParams(carried).forEach((value, key) => query.set(key, value))
  return navHref(next, query.toString())
}
