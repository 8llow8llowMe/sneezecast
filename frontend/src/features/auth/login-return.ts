import { HOME_PATH } from '@/features/onboarding/paths'
import { navHref } from '@/lib/nav'

import { NEXT_PARAM, safeNextPath } from './required-steps'

/* ── 로그인 뒤 돌아올 곳 (#123) ────────────────────────────────────────────────────────────────
 *
 * 회원만 쓰는 화면(지금은 내 정보 `/me`)의 가드가 비회원을 로그인으로 보낼 때 `?next=` 와 둘러보기 동네(`?region=`)를 붙인다.
 * 로그인 방법 고르기(S13-1) → 이메일 로그인(S13-5)이 이 둘을 이어 받고, 로그인에 성공하면 그곳으로 기록을 바꿔 간다.
 *
 * - `next` 는 허용 목록(`NEXT_PATHS`)과 정확히 같은 경로만 따른다(`safeNextPath`, 오픈 리다이렉트 방지). 그 밖이면 홈이다.
 * - QA 용 목 덮어쓰기(`mock-auth` · `mock-provider` · `mock-required`)는 넘기지 않는다. 로그인은 세션을 바꾸는 동작이라
 *   로그아웃 뒤 이동처럼 결과(목 세션)가 그대로 보여야 하고, `?mock-auth=guest` 를 넘기면 돌아온 화면이 다시 로그인으로 보낸다.
 * - 동네 코드는 그대로 옮기기만 한다. 돌아간 화면이 아는 코드인지 확인한다(`districtFromParam`).
 */

const REGION_PARAM = 'region'

export type LoginReturn = {
  /** 로그인 뒤 갈 경로. 허용 목록 안이거나 홈이다 */
  next: string
  /** 둘러보기 동네 코드. 없으면 null */
  region: string | null
}

/** 돌아갈 곳이 없는 로그인(시작 화면 · 홈의 로그인 안내 시트에서 옴) — 로그인 뒤 홈으로 간다 */
export const NO_LOGIN_RETURN: LoginReturn = { next: HOME_PATH, region: null }

const first = (value: string | string[] | null | undefined): string | null =>
  (Array.isArray(value) ? value[0] : value) ?? null

/** 라우트 `searchParams` 에서 돌아갈 곳을 읽는다 */
export function loginReturnFrom(
  params: Record<string, string | string[] | undefined>,
): LoginReturn {
  return {
    next: safeNextPath(first(params[NEXT_PARAM])),
    region: first(params[REGION_PARAM]) || null,
  }
}

/** 돌아갈 곳을 이어 받는 로그인 화면 주소. 홈으로 돌아가면 `next` 를 붙이지 않는다 */
export function loginHref(path: string, { next, region }: LoginReturn): string {
  const query = new URLSearchParams()
  if (next !== HOME_PATH) query.set(NEXT_PARAM, next)
  if (region) query.set(REGION_PARAM, region)
  return navHref(path, query.toString())
}

/** 로그인에 성공한 뒤 갈 주소. 둘러보기 동네를 남긴다 */
export function afterLoginHref({ next, region }: LoginReturn): string {
  return navHref(next, region ? new URLSearchParams({ [REGION_PARAM]: region }).toString() : '')
}

/** 돌아갈 곳이 있는 로그인인지. 로그인 화면의 뒤로가 앞 화면을 따지지 않고 되돌린다 */
export function isReturning({ next }: LoginReturn): boolean {
  return next !== HOME_PATH
}
