import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import type { DataSource } from '@/lib/data-source'

import { DISTRICT_MOCKS, RESELECT_CANDIDATE_MOCKS } from './mock'
import type { District, DistrictDetail, ReselectCandidate } from './types'

/**
 * 행정동 찾기. 출처(`DataSource`)를 인자로 받아 백엔드(`/api/v1/districts`, docs/api-contract-draft.md "행정동")나
 * 목 데이터(`mock.ts`)로 답한다. 출처는 부르는 쪽이 정한다 — 서버 컴포넌트는 `readServerDataSource()`,
 * 클라이언트는 `useDataSource()`(docs/conventions.md "API 계층").
 *
 * 행정동은 공개 API 라 `auth: false` 로 부른다 — 만료된 access 를 실어 게이트웨이가 거절하는 일이 없게 한다.
 * 요청 시간 제한 · 봉투 풀기는 API 계층이 맡고, 실패하면 Promise 를 거부한다 — 화면은 거부를 받아 다시 검색하라고 안내한다.
 * 응답은 화면 모델(`District`)에 필요한 필드만 골라 옮긴다.
 */

/** 검색 결과 최대 개수. 한 화면에서 고르기 어려울 만큼 늘어나지 않게 한다 (서버도 20건까지 준다) */
const SEARCH_LIMIT = 20

/** 검색어 최대 길이. 넘으면 서버가 400(`DISTRICT_102`)이라 보내지 않는다 */
export const SEARCH_QUERY_MAX_LENGTH = 20

/** 행정동 코드 형식 — SGIS 숫자 8자리. 아니면 서버가 400(`DISTRICT_103`)이라 보내지 않는다 */
const DISTRICT_CODE_PATTERN = /^\d{8}$/

/** 없는 코드 (404) */
const DISTRICT_NOT_FOUND = 'DISTRICT_001'

const DISTRICTS_PATH = '/api/v1/districts'

/** `GET /api/v1/districts?query=` 의 한 줄 */
type DistrictSearchItem = { code: string; name: string; sigungu: string }

/** `GET /api/v1/districts/{code}` */
type DistrictDetailResponse = DistrictSearchItem & { active: boolean }

function toDistrict({ code, name, sigungu }: DistrictSearchItem): District {
  return { code, name, sigungu }
}

/**
 * 동 이름 또는 시군구에 검색어가 들어간 행정동. 앞뒤 공백을 빼고 찾는다.
 * 공백만 있거나 `SEARCH_QUERY_MAX_LENGTH` 를 넘는 검색어는 요청 없이 빈 목록이다.
 * `signal` 로 취소하면(검색어가 바뀜) 그 사유로 거부한다.
 */
export async function searchDistricts(
  query: string,
  source: DataSource,
  signal?: AbortSignal,
): Promise<District[]> {
  const keyword = query.trim()
  if (!keyword || keyword.length > SEARCH_QUERY_MAX_LENGTH) return []
  if (source === 'api') {
    const found = await apiRequest<DistrictSearchItem[]>(DISTRICTS_PATH, {
      query: { query: keyword },
      auth: false,
      ...(signal ? { signal } : {}),
    })
    return found.map(toDistrict)
  }
  return DISTRICT_MOCKS.filter(
    (district) => district.name.includes(keyword) || district.sigungu.includes(keyword),
  ).slice(0, SEARCH_LIMIT)
}

/**
 * 코드로 행정동 하나. 모르는 코드(404 `DISTRICT_001`) · 형식이 틀린 코드(숫자 8자리가 아님 — 요청하지 않는다)면 null 이다.
 * 폐지된 코드는 `active: false` 로 돌려준다(목은 늘 true). 그 밖의 실패(일시 장애 등)는 거부한다.
 */
export async function findDistrict(
  code: string,
  source: DataSource,
): Promise<DistrictDetail | null> {
  if (!DISTRICT_CODE_PATTERN.test(code)) return null
  if (source === 'api') {
    try {
      const found = await apiRequest<DistrictDetailResponse>(`${DISTRICTS_PATH}/${code}`, {
        auth: false,
      })
      return { ...toDistrict(found), active: found.active }
    } catch (error) {
      if (error instanceof ApiError && error.code === DISTRICT_NOT_FOUND) return null
      throw error
    }
  }
  const found = DISTRICT_MOCKS.find((district) => district.code === code)
  return found ? { ...found, active: true } : null
}

/**
 * 폐지된 동네(옛 코드)를 다시 고를 후보 (Setup-1-reselect). 모르는 코드면 빈 목록이다 — 화면은 검색으로 고르게 한다.
 *
 * **출처와 무관하게 목이다** — 백엔드에 후속 후보 API 가 아직 없다(BE 미정). 목은 시안 예시 후보만 안다(`RESELECT_CANDIDATE_MOCKS`).
 * 연동 때 백엔드 #60 의 "폐지된 코드면 재선택 유도" 응답으로 바꾼다 — 그 응답이 후보를 함께 주는지, 따로 묻는 API 가 있는지는
 * 백엔드와 정한다(docs/design/SCREENS.md 연동 요구사항).
 */
export function listSuccessorDistricts(oldCode: string): Promise<ReselectCandidate[]> {
  const candidates = RESELECT_CANDIDATE_MOCKS[oldCode] ?? []
  // 화면이 목록을 고쳐도 목 값이 바뀌지 않게 복사해 준다
  return Promise.resolve(candidates.map((candidate) => ({ ...candidate })))
}
