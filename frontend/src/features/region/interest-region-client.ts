import { getMockSession, subscribeMockSession } from '@/features/auth/auth-client'
import type { DataSource } from '@/lib/data-source'

import { INTEREST_REGION_EXAMPLES } from './mock'
import type { District } from './types'

/* ── 관심 동네 (S10 `/me/interest-regions`, #198) ─────────────────────────────────────────────
 *
 * 내 동네(보고 · 알림 기준)와 따로 지켜볼 행정동. **BE 미정이다** — 화면이 가정한 모양은 docs/api-contract-draft.md
 * "BE 미정 — 프론트 초안" 의 `GET · POST · DELETE /api/v1/members/me/interest-regions` 다.
 *
 * - 실데이터: 요청하지 않고 `unavailable` 이다. BE 미정 API 는 보통 출처와 무관하게 목이지만(docs/conventions.md "데이터 출처"),
 *   실제 회원이 고른 동네를 서버에 저장한 것처럼 보이면 다음에 와서 사라진 것을 보게 된다. 그래서 저장하지 않고 화면이 아직
 *   저장할 수 없다고 알린다(지난 보고 내역 `listPastReports` 와 같은 결)
 * - 목: 모듈 메모리의 목록(목 서버 흉내). 로그인한 기기 목록(`mockDeviceSessions`)과 같게 목 세션이 **비회원이 될 때만** 지운다 —
 *   다음 로그인은 예시 목록부터 다시 시작한다. 회원끼리 바뀌는 일(비회원을 거치지 않음)은 목에 없다
 *
 * 연동 때(초안): `POST` · `DELETE` 의 성공 응답은 바뀐 뒤의 목록(`SliceResponse<MemberRegion>`)이라 그대로 옮긴다. 거절(409 상한 ·
 * 이미 있음)의 오류 봉투에는 목록이 없어 `GET` 을 다시 불러 지금 목록을 함께 돌려준다(아래 반환 모양은 그대로).
 * `MemberRegion` 의 `name` · `sigungu` 가 null(행정동 서비스가 코드를 모름 · 폐지)인 줄의 화면 표시는 초안에 적었다 — 연동 때 이 모델에 더한다
 *
 * 행정동은 사용자가 직접 고른다 — 위치 정보로 정하지 않는다.
 */

/**
 * 관심 동네 상한. **임시 결정이다**(#198) — 기획서 · 시안에 상한이 없어 시안 예시(`2곳`)보다 하나 많게 두었다.
 * 백엔드와 맞출 때 바꾸면 화면 안내 문구도 이 값을 따른다
 */
export const INTEREST_REGION_LIMIT = 3

/** 목 재현 쿼리. 목데이터 모드에서만 쓴다 */
export const MOCK_INTEREST_REGIONS_PARAM = 'mock-interest-regions'

/** 목 재현: 빈 목록 · 상한까지 찬 목록 · 추가와 삭제가 실패함 */
const MOCK_SCENARIOS = ['empty', 'full', 'fail'] as const
export type MockInterestScenario = (typeof MOCK_SCENARIOS)[number]

/** 쿼리 값을 목 재현으로 읽는다. 모르는 값이면 null(기본 예시 목록)이다 */
export function parseMockInterestScenario(value: string | null): MockInterestScenario | null {
  return MOCK_SCENARIOS.find((scenario) => scenario === value) ?? null
}

/** 관심 동네 하나. 응답 `MemberRegion` 에서 화면이 쓰는 값만 옮긴다 */
export type InterestRegion = District

export type InterestRegions =
  | { status: 'ready'; regions: readonly InterestRegion[] }
  /** 실데이터 — 관심 동네 API 가 아직 없다 */
  | { status: 'unavailable' }

/**
 * 추가 결과. 거절(`duplicate` 이미 있음 · `limit` 상한)이어도 서버의 지금 목록을 함께 준다 — 다른 곳에서 바뀌었으면 화면이 맞춘다.
 * 화면은 보내기 전에 같은 조건을 먼저 막는다
 */
export type AddInterestRegionResult =
  | { status: 'ok' | 'duplicate' | 'limit'; regions: readonly InterestRegion[] }
  | { status: 'unavailable' }

type MockStore = { regions: InterestRegion[]; failWrites: boolean }

let mockStore: MockStore | null = null

// 목 세션이 비회원이 되면(로그아웃 · 탈퇴 · 동의 철회 · 로그인 만료) 지운다. 같은 기기에서 다음에 로그인한 사람에게 보이지 않게 한다.
// 기기 목록(`mockDeviceSessions`)과 같게 guest 에서만 지운다
subscribeMockSession(() => {
  if (getMockSession() === 'guest') mockStore = null
})

function seed(scenario: MockInterestScenario | null): MockStore {
  const count = scenario === 'empty' ? 0 : scenario === 'full' ? INTEREST_REGION_LIMIT : 2
  return {
    regions: INTEREST_REGION_EXAMPLES.slice(0, count).map((region) => ({ ...region })),
    failWrites: scenario === 'fail',
  }
}

function store(): MockStore {
  mockStore ??= seed(null)
  return mockStore
}

/** 화면이 목록을 고쳐도 목 서버 목록이 바뀌지 않게 복사해 준다 */
function snapshot(): InterestRegion[] {
  return store().regions.map((region) => ({ ...region }))
}

/** 목 서버의 응답처럼 Promise 로 준다. `answer` 가 던지면 거부한다 */
function respond<T>(answer: () => T): Promise<T> {
  return new Promise((resolve) => resolve(answer()))
}

function rejectIfFailing(action: string): void {
  if (store().failWrites) throw new Error(`mock: ${action} interest region failed`)
}

/**
 * 관심 동네 목록(고른 순서). `scenario` 는 목 재현이다 — 주어지면 목 목록을 그 상태로 다시 시작한다(화면을 열 때마다).
 * 주어지지 않으면 목록은 그대로 두고 실패 재현(`fail`)만 끈다 — 재현 쿼리 없이 다시 들어오면 더하기 · 삭제가 된다.
 * 목은 실패하지 않는다
 */
export function listInterestRegions(
  source: DataSource,
  { scenario = null }: { scenario?: MockInterestScenario | null } = {},
): Promise<InterestRegions> {
  return respond(() => {
    if (source === 'api') return { status: 'unavailable' }
    if (scenario) mockStore = seed(scenario)
    else store().failWrites = false
    return { status: 'ready', regions: snapshot() }
  })
}

/**
 * 관심 동네를 더한다(목록 끝). 이미 있으면 `duplicate`, 상한이면 `limit` 이고 목록은 그대로다.
 * 응답 전에 화면을 떠나도 서버(목 서버)에서는 끝난 일이다. 실패하면 거부한다(목 재현 `fail`)
 */
export function addInterestRegion(
  district: District,
  source: DataSource,
): Promise<AddInterestRegionResult> {
  return respond(() => {
    if (source === 'api') return { status: 'unavailable' }
    rejectIfFailing('add')
    const { regions } = store()
    if (regions.some((region) => region.code === district.code)) {
      return { status: 'duplicate', regions: snapshot() }
    }
    if (regions.length >= INTEREST_REGION_LIMIT) return { status: 'limit', regions: snapshot() }
    const { code, name, sigungu } = district
    regions.push({ code, name, sigungu })
    return { status: 'ok', regions: snapshot() }
  })
}

/** 관심 동네를 뺀다. 이미 없으면 끝난 것으로 본다(서버도 성공). 남은 목록을 준다. 실패하면 거부한다(목 재현 `fail`) */
export function removeInterestRegion(code: string, source: DataSource): Promise<InterestRegions> {
  return respond(() => {
    if (source === 'api') return { status: 'unavailable' }
    rejectIfFailing('remove')
    const current = store()
    current.regions = current.regions.filter((region) => region.code !== code)
    return { status: 'ready', regions: snapshot() }
  })
}

/** 테스트에서 목 목록을 처음(예시 목록)으로 되돌린다. 화면 코드는 부르지 않는다 */
export function resetMockInterestRegions(): void {
  mockStore = null
}
