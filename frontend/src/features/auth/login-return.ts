import { HOME_PATH } from '@/features/onboarding/paths'
import type { ReportStep } from '@/features/report/types'
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
 *
 * **보고하려던 로그인**(`?intent=report`, #136): 비회원이 보고 버튼(머리줄 · 홈의 로그인 안내 시트)을 눌러 로그인하면, 로그인 뒤
 * 같은 동네 홈의 보고 진입(`/?region=…&report=start`)으로 간다. 받는 값은 `report` 하나뿐이고, 돌아갈 곳이 홈일 때만 받는다 —
 * 보고 진입은 홈 위 시트라 다른 화면으로 돌아가며 들고 갈 곳이 없다. `next` 의 허용 목록(오픈 리다이렉트 방지)은 그대로다.
 * 회원 상태에 맞는 시트(미동의 → 동의 시트)는 고르지 않는다. 홈이 주소 값을 상태에 맞게 고친다(`features/home/report-gate.ts`
 * `guardReportEntry`) — 로그인 응답을 받은 순간의 상태로 고르면 홈의 판단과 두 곳에서 갈린다.
 */

const REGION_PARAM = 'region'
const INTENT_PARAM = 'intent'

/** 홈의 보고 진입 쿼리(`features/report/report-flow.tsx` 의 `REPORT_PARAM` 과 같다 — 테스트가 맞춰 본다). 첫 진입 화면이 보고 흐름을 끌어오지 않게 다시 적는다 */
export const REPORT_ENTRY_PARAM = 'report'
/** 로그인 뒤 열 보고 진입 값. 미동의 회원이면 홈이 동의 시트로 고친다 */
const REPORT_ENTRY_VALUE: ReportStep = 'start'

/** 로그인하려던 까닭. 지금은 보고뿐이다 */
export type LoginIntent = 'report'

export type LoginReturn = {
  /** 로그인 뒤 갈 경로. 허용 목록 안이거나 홈이다 */
  next: string
  /** 둘러보기 동네 코드. 없으면 null */
  region: string | null
  /** 보고하려던 로그인이면 `report`. `next` 가 홈일 때만 있다 */
  intent: LoginIntent | null
}

/** 돌아갈 곳이 없는 로그인(시작 화면에서 옴) — 로그인 뒤 홈으로 간다 */
export const NO_LOGIN_RETURN: LoginReturn = { next: HOME_PATH, region: null, intent: null }

const first = (value: string | string[] | null | undefined): string | null =>
  (Array.isArray(value) ? value[0] : value) ?? null

function readLoginReturn(read: (key: string) => string | null): LoginReturn {
  const next = safeNextPath(read(NEXT_PARAM))
  return {
    next,
    region: read(REGION_PARAM) || null,
    intent: next === HOME_PATH && read(INTENT_PARAM) === 'report' ? 'report' : null,
  }
}

/** 라우트 `searchParams` 에서 돌아갈 곳을 읽는다 */
export function loginReturnFrom(
  params: Record<string, string | string[] | undefined>,
): LoginReturn {
  return readLoginReturn((key) => first(params[key]))
}

/** 주소 쿼리(`useSearchParams`)에서 돌아갈 곳을 읽는다. 규칙은 `loginReturnFrom` 과 같다 */
export function loginReturnFromSearch(searchParams: Pick<URLSearchParams, 'get'>): LoginReturn {
  return readLoginReturn((key) => searchParams.get(key))
}

/** 돌아갈 곳을 이어 받는 로그인 화면 주소. 홈으로 돌아가면 `next` 를 붙이지 않는다 */
export function loginHref(path: string, { next, region, intent }: LoginReturn): string {
  const query = new URLSearchParams()
  if (next !== HOME_PATH) query.set(NEXT_PARAM, next)
  if (region) query.set(REGION_PARAM, region)
  if (intent) query.set(INTENT_PARAM, intent)
  return navHref(path, query.toString())
}

/**
 * 로그인 뒤 갈 주소의 쿼리. 둘러보기 동네를 남기고, 보고하려던 로그인이면 홈의 보고 진입(`report=start`)을 붙인다.
 * 첫 진입 가드(`guest-only-gate.tsx`)도 회원을 보낼 곳을 이 쿼리로 만들지만 `intent` 를 비워 보고 진입은 붙이지 않는다 — 회원이 로그인 화면에
 * 닿는 것은 사실상 로그인 성공 뒤 브라우저 뒤로이고, 그때 보고 시트를 다시 열면 뒤로 가려는 사람을 붙잡는다.
 */
export function afterLoginQuery({ region, intent }: LoginReturn): URLSearchParams {
  const query = new URLSearchParams()
  if (region) query.set(REGION_PARAM, region)
  if (intent === 'report') query.set(REPORT_ENTRY_PARAM, REPORT_ENTRY_VALUE)
  return query
}

/** 로그인에 성공한 뒤 갈 주소 (`afterLoginQuery`) */
export function afterLoginHref(loginReturn: LoginReturn): string {
  return navHref(loginReturn.next, afterLoginQuery(loginReturn).toString())
}

/**
 * 돌아갈 곳이 있는 로그인인지. 로그인 화면의 뒤로가 앞 화면을 따지지 않고 되돌린다.
 * 보고하려던 로그인도 그렇다 — 머리줄 보고 버튼은 여러 화면(지도 · 공식 정보 · 동네 안내 …)에 있어 앞 화면이 정해져 있지 않다
 */
export function isReturning({ next, intent }: LoginReturn): boolean {
  return next !== HOME_PATH || intent !== null
}
