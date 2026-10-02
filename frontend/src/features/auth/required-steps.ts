import {
  HOME_PATH,
  RESELECT_PARAM,
  RESELECT_VALUE,
  SETUP_REGION_PATH,
  TERMS_RECONSENT_PATH,
} from '@/features/onboarding/paths'
import { ABOLISHED_DISTRICT_EXAMPLE } from '@/features/region/mock'
import type { DataSource } from '@/lib/data-source'
import { navHref } from '@/lib/nav'
import { getSessionSnapshot, type SessionSnapshot } from '@/lib/session/session-store'

import {
  getMockProfile,
  type MemberRegion,
  type MockAuthState,
  type MockProfile,
} from './auth-client'
import { getMemberInfoSnapshot, memberInfoOf, type MemberInfoSnapshot } from './member-info'
import { MOCK_AUTH_PARAM, MOCK_PROVIDER_PARAM } from './use-mock-auth'

/* ── 다시 들어온 회원이 먼저 거칠 화면 (Setup-3-reconsent · Setup-1-reselect) ─────────────────────────
 *
 * 홈 · 내 정보에 들어온 회원에게 조건이 있으면 그 화면부터 보낸다(`features/me/member-gate.ts`).
 * **순서는 약관 재동의 → 동네 다시 고르기다.** 법적 동의가 먼저다. 비회원은 보내지 않는다.
 * 마친 뒤에는 원래 가려던 곳(`?next=`)으로 돌아가고, 남은 조건이 있으면 그 화면으로 이어 간다.
 */

export type RequiredStep = 'terms' | 'region'

/** 거칠 순서. 법적 동의(재동의)가 동네보다 먼저다 */
export const REQUIRED_STEP_ORDER: readonly RequiredStep[] = ['terms', 'region']

/**
 * QA 용 조건 덮어쓰기 쿼리 (`?mock-required=terms|region|terms,region`). 있으면 목 프로필의 조건 대신 이 값을 쓴다.
 * 동네 조건의 옛 동네는 프로필에 폐지된 동네가 없으면 시안 예시(`○○1동`)다.
 * `?mock-auth=` 와 같은 QA 덮어쓰기라 **목데이터 모드에서만 듣는다**(`useMemberRequirements`).
 */
export const MOCK_REQUIRED_PARAM = 'mock-required'

/** 쿼리 값을 조건 목록으로(쉼표로 나눈다). 아는 값이 하나도 없으면 null 이다(덮어쓰지 않는다) */
export function parseMockRequired(value: string | null): RequiredStep[] | null {
  if (value === null) return null
  const given = value.split(',').map((part) => part.trim())
  const steps = REQUIRED_STEP_ORDER.filter((step) => given.includes(step))
  return steps.length > 0 ? steps : null
}

/** 폐지된 옛 동네. 실데이터는 행정동 서비스에 코드가 없으면 이름을 모른다(null) */
export type AbolishedRegion = { code: string; name: string | null }

export type MemberRequirements = {
  /** 거칠 화면. 순서대로다(`REQUIRED_STEP_ORDER`). 비었으면 바로 들어간다 */
  steps: RequiredStep[]
  /** 동네 조건이 있을 때 옛 동네(다시 고르기 안내에 이름을 쓴다). 없으면 null */
  abolishedRegion: AbolishedRegion | null
  /**
   * 동네 조건까지 판단했는지. 실데이터 회원의 내 동네를 아직 읽는 중이면 false 다 — 그동안 `steps` 에는 동네 조건이 없다.
   * 동네 화면은 이 값이 true 일 때만 내보낼 곳을 정한다(읽기 전의 "조건 없음" 으로 내보내지 않게). 읽지 못했으면(일시 장애)
   * 판단을 접고 true 다 — 다시 고르게 할지 모르는 채로 화면을 막지 않는다. 목데이터 · 비회원은 늘 true 다
   */
  settled: boolean
}

const NO_REQUIREMENTS: MemberRequirements = Object.freeze({
  steps: [],
  abolishedRegion: null,
  settled: true,
})

const EXAMPLE_ABOLISHED_REGION: MemberRegion = {
  code: ABOLISHED_DISTRICT_EXAMPLE.code,
  name: ABOLISHED_DISTRICT_EXAMPLE.name,
}

/**
 * 회원 상태 · 목 프로필 · 덮어쓰기로 거칠 화면을 정한다. 비회원은 늘 없다.
 * 덮어쓰기(`override`)가 있으면 프로필의 조건 대신 그 값을 쓴다.
 */
export function memberRequirements(
  auth: MockAuthState,
  profile: MockProfile | null,
  override: readonly RequiredStep[] | null,
): MemberRequirements {
  if (auth === 'guest') return NO_REQUIREMENTS
  const terms = override ? override.includes('terms') : profile?.termsReconsentRequired === true
  const region = override ? override.includes('region') : profile?.regionAbolished === true
  const steps = REQUIRED_STEP_ORDER.filter((step) => (step === 'terms' ? terms : region))
  const abolishedRegion = !region
    ? null
    : profile?.regionAbolished && profile.region
      ? profile.region
      : EXAMPLE_ABOLISHED_REGION
  return { steps, abolishedRegion, settled: true }
}

/**
 * 실데이터 세션 · 회원 정보 저장소로 거칠 화면을 정한다. 회원이 아니면(복원 중 포함) 없다.
 *
 * - 약관 재동의: 세션의 다시 동의할 항목(`pendingConsents` — 로그인 · 재발급 응답)이 있으면
 * - 동네 다시 고르기: 내 동네(`GET /api/v1/members/me/region`)가 폐지됐으면(`abolished`). 아직 고르지 않았으면(null) 다시 고르게
 *   하지 않는다. 읽는 중이면 판단하지 않고(`settled: false`), 읽지 못했으면 동네 조건 없이 정해진 것으로 본다
 */
export function sessionRequirements(
  session: SessionSnapshot,
  memberInfo: MemberInfoSnapshot | null,
): MemberRequirements {
  if (session.status !== 'member') return NO_REQUIREMENTS
  const load = memberInfoOf(session, memberInfo)?.region ?? { status: 'loading' }
  const region = load.status === 'ready' && load.value?.abolished ? load.value : null
  const terms = session.summary.pendingConsents.length > 0
  return {
    steps: REQUIRED_STEP_ORDER.filter((step) => (step === 'terms' ? terms : region !== null)),
    abolishedRegion: region ? { code: region.code, name: region.name } : null,
    settled: load.status !== 'loading',
  }
}

/* ── 돌아갈 곳 (`?next=`) ─────────────────────────────────────────────────────────────────── */

export const NEXT_PARAM = 'next'

/**
 * `?next=` 로 받는 경로. **이 목록 안의 경로만 받는다**(오픈 리다이렉트 방지) — 가드가 거는 화면(홈 · 내 정보와 그 아래 계정 화면)이다.
 * 쿼리 · 조각이 붙거나 다른 오리진(`//evil.example` · `https://…`)이면 홈으로 보낸다.
 * 내 정보 경로는 `features/me/me-paths.ts` 와 같다(테스트가 맞춰 본다). 첫 진입 화면이 내 정보 모듈을 끌어오지 않게 여기 다시 적는다.
 */
export const NEXT_PATHS: readonly string[] = [
  HOME_PATH,
  '/me',
  '/me/devices',
  '/me/password',
  '/me/region',
]

/** `?next=` 값을 돌아갈 경로로. 없거나 목록 밖이면 홈이다 */
export function safeNextPath(value: string | null | undefined): string {
  return value != null && NEXT_PATHS.includes(value) ? value : HOME_PATH
}

/** 이 화면들 사이를 오갈 때 남기는 쿼리. 둘러보기 동네와 QA 용 목 덮어쓰기만 남긴다 — 열린 시트(`report` 등)는 버린다 */
const CARRIED_PARAMS = ['region', MOCK_AUTH_PARAM, MOCK_PROVIDER_PARAM, MOCK_REQUIRED_PARAM]

/**
 * 지금 주소에서 남길 쿼리. `completed` 를 마쳤으면 `?mock-required=` 에서 그 조건을 뺀다(다 빠지면 쿼리를 지운다) —
 * 남기면 덮어쓰기 때문에 마친 화면으로 다시 돌아온다. 아는 값이 없는 `?mock-required=` 는 버린다.
 */
export function carriedParams(
  searchParams: Pick<URLSearchParams, 'get'>,
  completed?: RequiredStep,
): URLSearchParams {
  const params = new URLSearchParams()
  for (const key of CARRIED_PARAMS) {
    const value = searchParams.get(key)
    if (value === null) continue
    if (key !== MOCK_REQUIRED_PARAM) {
      params.set(key, value)
      continue
    }
    const left = (parseMockRequired(value) ?? []).filter((step) => step !== completed)
    if (left.length > 0) params.set(key, left.join(','))
  }
  return params
}

/** 조건 화면 주소. `next` 가 홈이면 붙이지 않는다(기본값) */
export function requiredStepHref(
  step: RequiredStep,
  next: string,
  params: URLSearchParams,
): string {
  const query = new URLSearchParams()
  if (step === 'region') query.set(RESELECT_PARAM, RESELECT_VALUE)
  if (next !== HOME_PATH) query.set(NEXT_PARAM, next)
  params.forEach((value, key) => query.set(key, value))
  const path = step === 'terms' ? TERMS_RECONSENT_PATH : SETUP_REGION_PATH
  return navHref(path, query.toString())
}

/** 거칠 화면이 남았으면 첫 화면, 없으면 돌아갈 곳(남길 쿼리를 붙인다) */
export function stepTarget(
  steps: readonly RequiredStep[],
  next: string,
  params: URLSearchParams,
): string {
  const [first] = steps
  return first ? requiredStepHref(first, next, params) : navHref(next, params.toString())
}

/**
 * `completed` 를 마친 뒤 갈 곳. 마친 결과를 바로 읽어 남은 조건이 있으면 그 화면으로, 없으면 `?next=` 로 간다.
 * 화면 상태(hook)는 응답을 기다리는 동안 낡을 수 있어 저장소를 직접 읽는다 — 목데이터는 목 세션 프로필, 실데이터는 세션 ·
 * 회원 정보 저장소(`sessionRequirements`)다. 마친 조건은 다시 넣지 않는다.
 */
export function targetAfter(
  completed: RequiredStep,
  auth: MockAuthState,
  searchParams: Pick<URLSearchParams, 'get'>,
  source: DataSource,
): string {
  const params = carriedParams(searchParams, completed)
  const { steps } =
    source === 'api'
      ? sessionRequirements(getSessionSnapshot(), getMemberInfoSnapshot())
      : memberRequirements(
          auth,
          getMockProfile(),
          parseMockRequired(params.get(MOCK_REQUIRED_PARAM)),
        )
  return stepTarget(
    steps.filter((step) => step !== completed),
    safeNextPath(searchParams.get(NEXT_PARAM)),
    params,
  )
}
