import { beforeEach, describe, expect, it, vi } from 'vitest'

import { loginWithEmail, logout, resetMockSession, saveRegion } from '@/features/auth/auth-client'
import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import {
  addInterestRegion,
  type AddInterestRegionResult,
  INTEREST_REGION_LIMIT,
  INTEREST_REGIONS_PATH,
  type InterestRegion,
  listInterestRegions,
  parseMockInterestScenario,
  removeInterestRegion,
  resetMockInterestRegions,
} from './interest-region-client'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

const SEOGYO = { code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' }
const MANGWON = { code: '11440690', name: '망원1동', sigungu: '서울특별시 마포구' }
const SAMCHEONG = { code: '11110540', name: '삼청동', sigungu: '서울특별시 종로구' }
const UDONG = { code: '26350525', name: '우동', sigungu: '부산광역시 해운대구' }

/** 결과 · 목록의 동네 이름 */
function names(result: AddInterestRegionResult | readonly InterestRegion[]) {
  const regions = 'status' in result ? ('regions' in result ? result.regions : []) : result
  return regions.map((region) => region.name)
}

/** 백엔드 목록 응답(`SliceResponse<MemberRegionResponse>`) */
function slice(...contents: { code: string; name: string | null; sigungu: string | null }[]) {
  return {
    contents: contents.map((region) => ({ abolished: false, ...region })),
    hasNext: false,
  }
}

function apiError(code: string, status: number): ApiError {
  return new ApiError({ status, code, message: '거절' })
}

beforeEach(() => {
  resetMockSession()
  resetMockInterestRegions()
  vi.mocked(apiRequest).mockReset()
})

describe('관심 동네 클라이언트 (실데이터)', () => {
  it('목록은 GET 이고 고른 순서 그대로 네 값만 옮긴다 — 폐지 · 이름 모름(null)도 그대로다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({
      contents: [
        { ...MANGWON, abolished: false, extra: 'x' },
        { code: '99990110', name: '○○1동', sigungu: '○○시 ○○구', abolished: true },
        { code: '99990120', name: null, sigungu: null, abolished: true },
      ],
      hasNext: false,
    })

    expect(await listInterestRegions('api')).toEqual([
      { ...MANGWON, abolished: false },
      { code: '99990110', name: '○○1동', sigungu: '○○시 ○○구', abolished: true },
      { code: '99990120', name: null, sigungu: null, abolished: true },
    ])
    expect(apiRequest).toHaveBeenCalledWith(INTEREST_REGIONS_PATH)
    expect(INTEREST_REGIONS_PATH).toBe('/api/v1/members/me/interest-regions')
  })

  it('목록 실패(REGION_004 · 일시 장애)는 거부한다. 목 목록은 건드리지 않는다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('REGION_004', 503))
    await expect(listInterestRegions('api')).rejects.toMatchObject({ code: 'REGION_004' })
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(listInterestRegions('api')).rejects.toMatchObject({ code: 'UNAVAILABLE' })

    expect(names(await listInterestRegions('mock'))).toEqual(['망원1동', '삼청동'])
  })

  it('더하기는 코드만 POST 하고 바뀐 뒤의 목록을 그대로 준다 — 다시 읽지 않는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(slice(MANGWON, SEOGYO))

    const result = await addInterestRegion(SEOGYO, 'api')
    expect(result.status).toBe('ok')
    expect(names(result)).toEqual(['망원1동', '서교동'])
    expect(apiRequest).toHaveBeenCalledTimes(1)
    expect(apiRequest).toHaveBeenCalledWith(INTEREST_REGIONS_PATH, {
      method: 'POST',
      body: { code: '11440660' },
    })
  })

  it.each([
    ['REGION_005', 'limit', [MANGWON, SAMCHEONG, UDONG]],
    ['REGION_006', 'duplicate', [MANGWON, SEOGYO]],
    ['REGION_007', 'home', [MANGWON]],
  ] as const)(
    '409 %s 면 %s 이고, 오류 봉투에 목록이 없어 GET 으로 다시 읽은 목록을 함께 준다',
    async (code, status, listed) => {
      vi.mocked(apiRequest)
        .mockRejectedValueOnce(apiError(code, 409))
        .mockResolvedValueOnce(slice(...listed))

      const result = await addInterestRegion(SEOGYO, 'api')
      expect(result.status).toBe(status)
      expect(names(result)).toEqual(listed.map((region) => region.name))
      expect(
        vi.mocked(apiRequest).mock.calls.map(([path, options]) => [path, options?.method]),
      ).toEqual([
        [INTEREST_REGIONS_PATH, 'POST'],
        [INTEREST_REGIONS_PATH, undefined],
      ])
    },
  )

  it.each([
    ['REGION_005', [MANGWON]],
    ['REGION_006', [MANGWON]],
  ] as const)(
    '%s 인데 다시 읽은 목록이 거절과 맞지 않으면(그사이 다른 곳에서 지움) failed 와 그 목록이다',
    async (code, listed) => {
      vi.mocked(apiRequest)
        .mockRejectedValueOnce(apiError(code, 409))
        .mockResolvedValueOnce(slice(...listed))
      const result = await addInterestRegion(SEOGYO, 'api')
      expect(result.status).toBe('failed')
      expect(names(result)).toEqual(['망원1동'])
    },
  )

  it('거절 뒤 다시 읽기가 실패하면 그 오류로 거부한다', async () => {
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('REGION_005', 409))
      .mockRejectedValueOnce(apiError('REGION_004', 503))
    await expect(addInterestRegion(SEOGYO, 'api')).rejects.toMatchObject({ code: 'REGION_004' })
  })

  it('겹친 추가(REGION_003)는 한 번 다시 보낸다 — 두 번째 결과를 따른다', async () => {
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('REGION_003', 409))
      .mockResolvedValueOnce(slice(MANGWON, SEOGYO))
    const ok = await addInterestRegion(SEOGYO, 'api')
    expect(ok.status).toBe('ok')
    expect(names(ok)).toEqual(['망원1동', '서교동'])
    expect(apiRequest).toHaveBeenCalledTimes(2)

    // 다시 보낸 요청이 거절되면 그 거절대로 목록을 다시 읽는다
    vi.mocked(apiRequest).mockReset()
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('REGION_003', 409))
      .mockRejectedValueOnce(apiError('REGION_006', 409))
      .mockResolvedValueOnce(slice(SEOGYO))
    expect(await addInterestRegion(SEOGYO, 'api')).toMatchObject({ status: 'duplicate' })

    // 두 번째도 겹치면 거부한다(화면은 "잠시 뒤 다시")
    vi.mocked(apiRequest).mockReset()
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('REGION_003', 409))
      .mockRejectedValueOnce(apiError('REGION_003', 409))
    await expect(addInterestRegion(SEOGYO, 'api')).rejects.toMatchObject({ code: 'REGION_003' })
    expect(apiRequest).toHaveBeenCalledTimes(2)
  })

  it.each(['REGION_001', 'REGION_002', 'REGION_101', 'REGION_102'])(
    '고를 수 없는 동네(%s, 400)는 invalid 이고 목록을 다시 읽지 않는다',
    async (code) => {
      vi.mocked(apiRequest).mockRejectedValueOnce(apiError(code, 400))
      expect(await addInterestRegion(SEOGYO, 'api')).toEqual({ status: 'invalid' })
      expect(apiRequest).toHaveBeenCalledTimes(1)
    },
  )

  it('REGION_004 는 커밋됐을 수 있어 다시 읽고, 그 동네가 있으면 더한 것으로 본다', async () => {
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('REGION_004', 503))
      .mockResolvedValueOnce(slice(MANGWON, SEOGYO))
    const result = await addInterestRegion(SEOGYO, 'api')
    expect(result.status).toBe('ok')
    expect(names(result)).toEqual(['망원1동', '서교동'])
  })

  it('REGION_004 뒤 다시 읽은 목록에 없으면 failed 와 그 목록, 다시 읽기도 실패하면 처음 오류로 거부한다', async () => {
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('REGION_004', 503))
      .mockResolvedValueOnce(slice(MANGWON))
    const result = await addInterestRegion(SEOGYO, 'api')
    expect(result.status).toBe('failed')
    expect(names(result)).toEqual(['망원1동'])

    vi.mocked(apiRequest)
      .mockRejectedValueOnce(apiError('REGION_004', 503))
      .mockRejectedValueOnce(unavailableError('timeout', 0))
    await expect(addInterestRegion(SEOGYO, 'api')).rejects.toMatchObject({ code: 'REGION_004' })
  })

  it('일시 장애 · 인증 오류는 다시 읽지 않고 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(addInterestRegion(SEOGYO, 'api')).rejects.toMatchObject({ code: 'UNAVAILABLE' })
    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('SECURITY_001', 401))
    await expect(addInterestRegion(SEOGYO, 'api')).rejects.toMatchObject({ code: 'SECURITY_001' })
    expect(apiRequest).toHaveBeenCalledTimes(2)
  })

  it('빼기는 경로에 코드를 붙여 DELETE 하고 남은 목록을 준다. 실패는 거부한다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(slice(SEOGYO))
    expect(names(await removeInterestRegion(MANGWON.code, 'api'))).toEqual(['서교동'])
    expect(apiRequest).toHaveBeenCalledWith(`${INTEREST_REGIONS_PATH}/11440690`, {
      method: 'DELETE',
    })

    // 경로를 벗어나는 값은 그대로 붙이지 않는다
    vi.mocked(apiRequest).mockResolvedValueOnce(slice())
    await removeInterestRegion('../region', 'api')
    expect(apiRequest).toHaveBeenLastCalledWith(`${INTEREST_REGIONS_PATH}/..%2Fregion`, {
      method: 'DELETE',
    })

    vi.mocked(apiRequest).mockRejectedValueOnce(apiError('REGION_004', 503))
    await expect(removeInterestRegion(MANGWON.code, 'api')).rejects.toMatchObject({
      code: 'REGION_004',
    })
  })

  it('목 출처는 API 를 부르지 않는다', async () => {
    await listInterestRegions('mock')
    await addInterestRegion(SEOGYO, 'mock')
    await removeInterestRegion(SEOGYO.code, 'mock')
    expect(apiRequest).not.toHaveBeenCalled()
  })
})

describe('관심 동네 클라이언트 (목)', () => {
  it('처음은 시안과 같은 예시 2곳이다. 더하면 끝에 붙고 빼면 사라진다', async () => {
    expect(await listInterestRegions('mock')).toEqual([
      { ...MANGWON, abolished: false },
      { code: '11110540', name: '삼청동', sigungu: '서울특별시 종로구', abolished: false },
    ])

    const added = await addInterestRegion(SEOGYO, 'mock')
    expect(added.status).toBe('ok')
    expect(names(added)).toEqual(['망원1동', '삼청동', '서교동'])

    expect(names(await removeInterestRegion('11440690', 'mock'))).toEqual(['삼청동', '서교동'])
    // 이미 없는 동네를 빼도 끝난 것으로 본다
    expect(names(await removeInterestRegion('11440690', 'mock'))).toEqual(['삼청동', '서교동'])
  })

  it('이미 있는 동네 · 상한(3곳)이면 거절하고 목록은 그대로다', async () => {
    expect(INTEREST_REGION_LIMIT).toBe(3)
    const duplicate = await addInterestRegion(MANGWON, 'mock')
    expect(duplicate).toMatchObject({ status: 'duplicate' })
    expect(names(duplicate)).toEqual(['망원1동', '삼청동'])

    await addInterestRegion(SEOGYO, 'mock')
    const limit = await addInterestRegion(
      { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' },
      'mock',
    )
    expect(limit).toMatchObject({ status: 'limit' })
    expect(names(limit)).toHaveLength(3)
  })

  it('목 프로필의 내 동네와 같은 동네는 서버처럼 home 으로 거절한다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await saveRegion({ code: SEOGYO.code, name: SEOGYO.name }, 'mock')
    const home = await addInterestRegion(SEOGYO, 'mock')
    expect(home).toMatchObject({ status: 'home' })
    expect(names(home)).toEqual(['망원1동', '삼청동'])
  })

  it('받은 목록을 고쳐도 목 서버 목록은 바뀌지 않는다', async () => {
    const [first] = await listInterestRegions('mock')
    if (!first) throw new Error('a region expected')
    first.name = '바뀐동'
    expect(names(await listInterestRegions('mock'))).toEqual(['망원1동', '삼청동'])
  })

  it('목 재현은 목록을 그 상태로 다시 시작한다 — empty · full · fail(추가 · 삭제 실패) · abolished', async () => {
    await addInterestRegion(SEOGYO, 'mock')
    expect(names(await listInterestRegions('mock', { scenario: 'empty' }))).toEqual([])
    expect(names(await listInterestRegions('mock', { scenario: 'full' }))).toEqual([
      '망원1동',
      '삼청동',
      '우동',
    ])

    expect(names(await listInterestRegions('mock', { scenario: 'fail' }))).toEqual([
      '망원1동',
      '삼청동',
    ])
    await expect(addInterestRegion(SEOGYO, 'mock')).rejects.toThrow()
    await expect(removeInterestRegion('11440690', 'mock')).rejects.toThrow()

    // 재현 쿼리 없이 다시 읽으면 목록은 그대로 두고 실패만 푼다
    expect(names(await listInterestRegions('mock'))).toEqual(['망원1동', '삼청동'])
    expect((await addInterestRegion(SEOGYO, 'mock')).status).toBe('ok')

    // 폐지됐지만 이름이 남은 동네 · 행정동 서비스가 모르는 동네(이름 null)
    const abolished = await listInterestRegions('mock', { scenario: 'abolished' })
    expect(abolished.map(({ name, abolished }) => [name, abolished])).toEqual([
      ['○○1동', true],
      [null, true],
    ])
    expect(abolished[1]?.sigungu).toBeNull()
  })

  it('목 재현 쿼리는 아는 값만 읽는다', () => {
    expect(parseMockInterestScenario('full')).toBe('full')
    expect(parseMockInterestScenario('abolished')).toBe('abolished')
    expect(parseMockInterestScenario('FULL')).toBeNull()
    expect(parseMockInterestScenario(null)).toBeNull()
  })

  it('목 세션이 비회원이 되면 지운다 — 다음 로그인은 예시 목록부터다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await addInterestRegion(SEOGYO, 'mock')
    await removeInterestRegion('11440690', 'mock')
    expect(names(await listInterestRegions('mock'))).toEqual(['삼청동', '서교동'])

    await logout('mock')
    expect(names(await listInterestRegions('mock'))).toEqual(['망원1동', '삼청동'])
  })
})
