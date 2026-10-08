import { getMockProfile, getMockSession, subscribeMockSession } from '@/features/auth/auth-client'
import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import type { DataSource } from '@/lib/data-source'

import {
  ABOLISHED_DISTRICT_EXAMPLE,
  INTEREST_REGION_EXAMPLES,
  UNKNOWN_DISTRICT_CODE_EXAMPLE,
} from './mock'
import type { District } from './types'

/* ── 관심 동네 (S10 `/me/interest-regions`, #198 · 연동 #237) ─────────────────────────────────────────────
 *
 * 내 동네(보고 · 알림 기준)와 따로 지켜볼 행정동. 계약은 백엔드 #217 의 `GET` · `POST { code }` · `DELETE /{code}`
 * `/api/v1/members/me/interest-regions` 다(docs/api-contract-draft.md "관심 동네", 정본 backend/docs/modules.md "관심 동네").
 * 출처(`DataSource`)는 부르는 화면이 `useDataSource()` 로 읽어 넘긴다.
 *
 * - 실데이터: 세 요청 모두 access 를 싣고 **바뀐 뒤의 목록**(`SliceResponse<MemberRegionResponse>`, 고른 순서, `hasNext` 는 늘 false 라
 *   보지 않는다)을 그대로 옮긴다. 화면이 더하고 뺀 결과를 다시 읽지 않는다
 *   - 거절(409 `REGION_005` 상한 · `006` 이미 있음 · `007` 내 동네와 같음)의 오류 봉투에는 목록이 없어 `GET` 을 다시 불러 지금 목록을
 *     함께 돌려준다. `REGION_003`(같은 계정의 추가가 겹침 — 다시 보내면 풀림)은 한 번 다시 보낸다(내 동네 저장 `saveRegion` 과 같다)
 *   - 고를 수 없는 동네(`REGION_001` 없는 코드 · `002` 폐지 · 검증 `101` · `102`)는 `invalid` 다
 *   - 추가는 커밋된 뒤 목록 이름을 읽다 `REGION_004`(503)일 수 있다. 그때는 `GET` 으로 맞춰 그 동네가 있으면 성공, 없으면 `failed`
 *     (다시 읽은 목록과 함께)다. 삭제는 멱등이라 다시 누르면 된다
 *   - 다시 읽은 목록이 거절과 맞지 않으면(이미 있음인데 목록에 없음 · 상한인데 상한 미만 — 그사이 다른 곳에서 지움) `failed` 다
 *   - 그 밖(`REGION_004` · 일시 장애 · 인증 오류 · 두 번째 경합)은 거부한다 — 화면은 "잠시 뒤 다시 시도" 로 알린다
 * - 목: 모듈 메모리의 목록(목 서버 흉내). 로그인한 기기 목록(`mockDeviceSessions`)과 같게 목 세션이 **비회원이 될 때만** 지운다 —
 *   다음 로그인은 예시 목록부터 다시 시작한다. 회원끼리 바뀌는 일(비회원을 거치지 않음)은 목에 없다
 *
 * 행정동은 사용자가 직접 고른다 — 위치 정보로 정하지 않는다.
 */

export const INTEREST_REGIONS_PATH = '/api/v1/members/me/interest-regions'

/**
 * 관심 동네 상한. 서버 설정 `region.interest.max-count`(기본 3, 환경마다 바꾸지 않음)와 같은 값이다(#198 · #217).
 * 서버 상한을 바꾸면 이 값과 화면 안내 문구도 함께 바꾼다
 */
export const INTEREST_REGION_LIMIT = 3

/** 목 재현 쿼리. 목데이터 모드에서만 쓴다 */
export const MOCK_INTEREST_REGIONS_PARAM = 'mock-interest-regions'

/** 목 재현: 빈 목록 · 상한까지 찬 목록 · 추가와 삭제가 실패함 · 폐지된 동네와 행정동 서비스가 모르는 동네가 있음 */
const MOCK_SCENARIOS = ['empty', 'full', 'fail', 'abolished'] as const
export type MockInterestScenario = (typeof MOCK_SCENARIOS)[number]

/** 쿼리 값을 목 재현으로 읽는다. 모르는 값이면 null(기본 예시 목록)이다 */
export function parseMockInterestScenario(value: string | null): MockInterestScenario | null {
  return MOCK_SCENARIOS.find((scenario) => scenario === value) ?? null
}

/** 목록 한 줄 (backend `MemberRegionResponse`, 내 동네와 같은 모양) */
type MemberRegionResponse = {
  code: string
  name: string | null
  sigungu: string | null
  abolished: boolean
}

/** 목록 응답 (backend `SliceResponse`). 상한만큼만 있어 `hasNext` 는 늘 false 다 */
type InterestRegionsResponse = { contents: MemberRegionResponse[]; hasNext: boolean }

/**
 * 관심 동네 하나. `name` · `sigungu` 는 행정동 서비스가 코드를 모르면 null 이다(그때 `abolished` 는 true).
 * `abolished` 가 true 면 고른 뒤 행정구역 개편으로 폐지된 동네다 — 서버는 지우지 않고 남겨 두고, 화면이 지우도록 안내한다
 */
export type InterestRegion = {
  code: string
  name: string | null
  sigungu: string | null
  abolished: boolean
}

/**
 * 추가 결과.
 * - `ok`: 더했다
 * - `duplicate` 이미 있음 · `limit` 상한 · `home` 내 동네와 같음: 거절이고, 서버의 지금 목록을 함께 준다 — 다른 곳에서 바뀌었으면
 *   화면이 맞춘다. 화면은 보내기 전에 같은 조건을 먼저 막는다
 * - `failed`: 더하지 못했고, 다시 읽은 목록이 거절과 맞지 않거나(그사이 다른 곳에서 지움) 장애 뒤 목록에 그 동네가 없다.
 *   화면은 목록을 맞추고 "더하지 못했어요 · 잠시 뒤 다시" 로 알린다(다시 보내면 된다)
 * - `invalid`: 고를 수 없는 동네다(없는 코드 · 폐지 · 형식). 서버는 저장하지 않았다
 */
export type AddInterestRegionResult =
  | {
      status: 'ok' | 'duplicate' | 'limit' | 'home' | 'failed'
      regions: readonly InterestRegion[]
    }
  | { status: 'invalid' }

/* ── 실데이터 ───────────────────────────────────────────────────────────────────── */

/** 409 거절 → 결과. 오류 봉투에 목록이 없어 다시 읽는다 */
const ADD_REJECTIONS: Readonly<Record<string, 'limit' | 'duplicate' | 'home'>> = {
  REGION_005: 'limit',
  REGION_006: 'duplicate',
  REGION_007: 'home',
}

/** 고를 수 없는 동네. 서버는 저장하지 않았다 */
const ADD_INVALID: ReadonlySet<string> = new Set([
  'REGION_001',
  'REGION_002',
  'REGION_101',
  'REGION_102',
])

/** 같은 계정의 추가가 겹쳐 같은 칸을 먼저 차지당했다(409). 다시 보내면 다음 칸으로 풀린다 */
const ADD_CONFLICT = 'REGION_003'

/** 행정동 확인 장애(503). 추가는 커밋된 뒤에도 올 수 있다 */
const DISTRICT_UNAVAILABLE = 'REGION_004'

function errorCodeOf(error: unknown): string | null {
  return error instanceof ApiError ? error.code : null
}

function toRegions(response: InterestRegionsResponse): InterestRegion[] {
  // 화면이 쓰는 네 값만 옮긴다
  return response.contents.map(({ code, name, sigungu, abolished }) => ({
    code,
    name,
    sigungu,
    abolished,
  }))
}

async function fetchRegions(): Promise<InterestRegion[]> {
  return toRegions(await apiRequest<InterestRegionsResponse>(INTEREST_REGIONS_PATH))
}

async function postRegion(code: string): Promise<InterestRegion[]> {
  return toRegions(
    await apiRequest<InterestRegionsResponse>(INTEREST_REGIONS_PATH, {
      method: 'POST',
      body: { code },
    }),
  )
}

/** 경합(`REGION_003`)이면 한 번만 다시 보낸다. 두 번째 결과는 그대로다 */
async function postRegionWithRetry(code: string): Promise<InterestRegion[]> {
  try {
    return await postRegion(code)
  } catch (error) {
    if (errorCodeOf(error) !== ADD_CONFLICT) throw error
    return postRegion(code)
  }
}

/** 다시 읽은 목록이 거절과 맞는지. 내 동네와 같음(`home`)은 목록으로 알 수 없어 맞는 것으로 본다 */
function agrees(
  status: 'limit' | 'duplicate' | 'home',
  code: string,
  regions: readonly InterestRegion[],
): boolean {
  if (status === 'duplicate') return regions.some((region) => region.code === code)
  if (status === 'limit') return regions.length >= INTEREST_REGION_LIMIT
  return true
}

async function addRegionApi(code: string): Promise<AddInterestRegionResult> {
  try {
    return { status: 'ok', regions: await postRegionWithRetry(code) }
  } catch (error) {
    const errorCode = errorCodeOf(error)
    if (errorCode !== null && Object.hasOwn(ADD_REJECTIONS, errorCode)) {
      const status = ADD_REJECTIONS[errorCode]
      // 다시 읽기가 실패하면 그 오류로 거부한다(화면은 "더하지 못했어요")
      if (status) {
        const regions = await fetchRegions()
        return { status: agrees(status, code, regions) ? status : 'failed', regions }
      }
    }
    if (errorCode !== null && ADD_INVALID.has(errorCode)) return { status: 'invalid' }
    if (errorCode === DISTRICT_UNAVAILABLE) {
      // 커밋된 뒤의 503 일 수 있다. 다시 읽어 그 동네가 있으면 더한 것이다. 다시 읽기도 실패하면 처음 오류로 거부한다
      const regions = await fetchRegions().catch(() => null)
      if (regions) {
        return { status: regions.some((region) => region.code === code) ? 'ok' : 'failed', regions }
      }
    }
    throw error
  }
}

/* ── 목 ─────────────────────────────────────────────────────────────────────────── */

type MockStore = { regions: InterestRegion[]; failWrites: boolean }

let mockStore: MockStore | null = null

// 목 세션이 비회원이 되면(로그아웃 · 탈퇴 · 동의 철회 · 로그인 만료) 지운다. 같은 기기에서 다음에 로그인한 사람에게 보이지 않게 한다.
// 기기 목록(`mockDeviceSessions`)과 같게 guest 에서만 지운다
subscribeMockSession(() => {
  if (getMockSession() === 'guest') mockStore = null
})

const current = (district: District): InterestRegion => ({ ...district, abolished: false })

function seedRegions(scenario: MockInterestScenario | null): InterestRegion[] {
  if (scenario === 'abolished') {
    // 폐지됐지만 이름이 남은 동네 · 행정동 서비스가 모르는 동네(이름 · 시군구 null)
    return [
      { ...ABOLISHED_DISTRICT_EXAMPLE, abolished: true },
      { code: UNKNOWN_DISTRICT_CODE_EXAMPLE, name: null, sigungu: null, abolished: true },
    ]
  }
  const count = scenario === 'empty' ? 0 : scenario === 'full' ? INTEREST_REGION_LIMIT : 2
  return INTEREST_REGION_EXAMPLES.slice(0, count).map(current)
}

function seed(scenario: MockInterestScenario | null): MockStore {
  return { regions: seedRegions(scenario), failWrites: scenario === 'fail' }
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

/* ── 공개 함수 ──────────────────────────────────────────────────────────────────── */

/**
 * 관심 동네 목록(고른 순서). 실데이터는 `GET` 이고 실패(`REGION_004` · 일시 장애 등)는 거부한다.
 * 목: `scenario` 는 목 재현이다 — 주어지면 목 목록을 그 상태로 다시 시작한다(화면을 열 때마다). 주어지지 않으면 목록은 그대로 두고
 * 실패 재현(`fail`)만 끈다 — 재현 쿼리 없이 다시 들어오면 더하기 · 삭제가 된다. 목은 실패하지 않는다
 */
export function listInterestRegions(
  source: DataSource,
  { scenario = null }: { scenario?: MockInterestScenario | null } = {},
): Promise<readonly InterestRegion[]> {
  if (source === 'api') return fetchRegions()
  return respond(() => {
    if (scenario) mockStore = seed(scenario)
    else store().failWrites = false
    return snapshot()
  })
}

/**
 * 관심 동네를 더한다(목록 끝). **코드만 보낸다.** 거절은 서버 순서대로 내 동네와 같음(`home`) → 이미 있음(`duplicate`) →
 * 상한(`limit`)이고 지금 목록을 함께 준다. 응답 전에 화면을 떠나도 서버(목 서버)에서는 끝난 일이다.
 * 실패하면 거부한다(실데이터 오류 처리는 위 머리 주석, 목 재현 `fail`)
 */
export function addInterestRegion(
  district: District,
  source: DataSource,
): Promise<AddInterestRegionResult> {
  if (source === 'api') return addRegionApi(district.code)
  return respond(() => {
    rejectIfFailing('add')
    const { regions } = store()
    if (getMockProfile()?.region?.code === district.code) {
      return { status: 'home', regions: snapshot() }
    }
    if (regions.some((region) => region.code === district.code)) {
      return { status: 'duplicate', regions: snapshot() }
    }
    if (regions.length >= INTEREST_REGION_LIMIT) return { status: 'limit', regions: snapshot() }
    const { code, name, sigungu } = district
    regions.push(current({ code, name, sigungu }))
    return { status: 'ok', regions: snapshot() }
  })
}

/**
 * 관심 동네를 뺀다. 이미 없으면 끝난 것으로 본다(서버도 성공 — 멱등). 폐지 · 이름 모름 동네도 같은 코드로 뺀다. 남은 목록을 준다.
 * 실패하면 거부한다(실데이터는 커밋된 채 `REGION_004` 일 수 있지만 다시 누르면 성공한다, 목 재현 `fail`)
 */
export function removeInterestRegion(
  code: string,
  source: DataSource,
): Promise<readonly InterestRegion[]> {
  if (source === 'api') {
    // 목록에서 받은 코드지만 경로에 그대로 붙이지 않는다(다른 경로로 새지 않게)
    return apiRequest<InterestRegionsResponse>(
      `${INTEREST_REGIONS_PATH}/${encodeURIComponent(code)}`,
      { method: 'DELETE' },
    ).then(toRegions)
  }
  return respond(() => {
    rejectIfFailing('remove')
    const mock = store()
    mock.regions = mock.regions.filter((region) => region.code !== code)
    return snapshot()
  })
}

/** 테스트에서 목 목록을 처음(예시 목록)으로 되돌린다. 화면 코드는 부르지 않는다 */
export function resetMockInterestRegions(): void {
  mockStore = null
}
