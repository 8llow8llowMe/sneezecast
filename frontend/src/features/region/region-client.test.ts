import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import { ABOLISHED_DISTRICT_EXAMPLE } from './mock'
import {
  findDistrict,
  listSuccessorDistricts,
  SEARCH_QUERY_MAX_LENGTH,
  searchDistricts,
} from './region-client'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

beforeEach(() => {
  vi.mocked(apiRequest).mockReset()
})

describe('searchDistricts (목)', () => {
  it('동 이름에 검색어가 들어간 행정동을 돌려준다', async () => {
    const found = await searchDistricts('역삼', 'mock')
    expect(found.map((district) => district.name)).toEqual(['역삼1동', '역삼2동'])
  })

  it('이름이 같은 동은 시군구가 다른 두 곳을 모두 돌려준다', async () => {
    const found = await searchDistricts('신사동', 'mock')
    expect(found.map((district) => district.sigungu)).toEqual([
      '서울특별시 강남구',
      '서울특별시 관악구',
    ])
  })

  it('시군구로도 찾고 앞뒤 공백은 무시한다', async () => {
    const found = await searchDistricts('  마포구 ', 'mock')
    expect(found.length).toBeGreaterThan(0)
    found.forEach((district) => expect(district.sigungu).toContain('마포구'))
  })

  it('빈 검색어 · 공백만 있는 검색어는 빈 목록이다', async () => {
    expect(await searchDistricts('', 'mock')).toEqual([])
    expect(await searchDistricts('   ', 'mock')).toEqual([])
  })

  it('맞는 동이 없으면 빈 목록이다', async () => {
    expect(await searchDistricts('없는동이름', 'mock')).toEqual([])
  })

  it('API 를 부르지 않는다', async () => {
    await searchDistricts('역삼', 'mock')
    expect(apiRequest).not.toHaveBeenCalled()
  })
})

describe('searchDistricts (API)', () => {
  it('앞뒤 공백을 뺀 검색어로 인증 없이 부르고 화면 모델로 옮긴다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce([
      { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구', extra: 'x' },
    ])
    const controller = new AbortController()

    const found = await searchDistricts('  역삼 ', 'api', controller.signal)

    expect(apiRequest).toHaveBeenCalledWith('/api/v1/districts', {
      query: { query: '역삼' },
      auth: false,
      signal: controller.signal,
    })
    expect(found).toEqual([{ code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }])
  })

  it('빈 검색어는 요청 없이 빈 목록이다', async () => {
    expect(await searchDistricts('  ', 'api')).toEqual([])
    expect(apiRequest).not.toHaveBeenCalled()
  })

  it(`${SEARCH_QUERY_MAX_LENGTH}자를 넘는 검색어는 요청 없이 빈 목록이다 (서버 DISTRICT_102)`, async () => {
    vi.mocked(apiRequest).mockResolvedValue([])
    await searchDistricts('가'.repeat(SEARCH_QUERY_MAX_LENGTH), 'api')
    expect(apiRequest).toHaveBeenCalledTimes(1)

    expect(await searchDistricts(` ${'가'.repeat(SEARCH_QUERY_MAX_LENGTH + 1)} `, 'api')).toEqual(
      [],
    )
    expect(apiRequest).toHaveBeenCalledTimes(1)
  })

  it('실패하면 거부한다', async () => {
    const error = unavailableError('network', 0)
    vi.mocked(apiRequest).mockRejectedValueOnce(error)
    await expect(searchDistricts('역삼', 'api')).rejects.toBe(error)
  })
})

describe('findDistrict (목)', () => {
  it('코드로 행정동을 찾는다 (늘 현행)', async () => {
    expect(await findDistrict('11680640', 'mock')).toEqual({
      code: '11680640',
      name: '역삼1동',
      sigungu: '서울특별시 강남구',
      active: true,
    })
    expect(apiRequest).not.toHaveBeenCalled()
  })

  it('모르는 코드면 null 이다', async () => {
    expect(await findDistrict('00000000', 'mock')).toBeNull()
  })
})

describe('findDistrict (API)', () => {
  it('인증 없이 부르고 필요한 필드만 옮긴다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({
      code: '11680640',
      name: '역삼1동',
      sigungu: '서울특별시 강남구',
      active: true,
      extra: 'x',
    })
    expect(await findDistrict('11680640', 'api')).toEqual({
      code: '11680640',
      name: '역삼1동',
      sigungu: '서울특별시 강남구',
      active: true,
    })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/districts/11680640', { auth: false })
  })

  it('폐지된 코드는 active=false 로 돌려준다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({
      code: '11680999',
      name: '옛동',
      sigungu: '서울특별시 강남구',
      active: false,
    })
    expect((await findDistrict('11680999', 'api'))?.active).toBe(false)
  })

  it('없는 코드(404 DISTRICT_001)면 null 이다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(
      new ApiError({ status: 404, code: 'DISTRICT_001', message: '없는 행정동' }),
    )
    expect(await findDistrict('00000000', 'api')).toBeNull()
  })

  it.each(['', '1168064', '116806400', '1168064a', '../../me', '１１６８０６４０'])(
    '형식이 틀린 코드(%s)는 요청 없이 null 이다 (서버 DISTRICT_103)',
    async (code) => {
      expect(await findDistrict(code, 'api')).toBeNull()
      expect(apiRequest).not.toHaveBeenCalled()
    },
  )

  it('그 밖의 실패는 거부한다', async () => {
    const error = unavailableError('timeout', 0)
    vi.mocked(apiRequest).mockRejectedValueOnce(error)
    await expect(findDistrict('11680640', 'api')).rejects.toBe(error)
  })
})

describe('listSuccessorDistricts', () => {
  it('폐지된 동네의 후보를 옛 동네 일부인지와 함께 돌려준다 (시안 예시)', async () => {
    const found = await listSuccessorDistricts(ABOLISHED_DISTRICT_EXAMPLE.code)
    expect(found.map(({ name, partOfAbolished }) => [name, partOfAbolished])).toEqual([
      ['○○새1동', true],
      ['○○새2동', true],
      ['○○2동', false],
    ])
  })

  it('모르는 코드면 빈 목록이다', async () => {
    expect(await listSuccessorDistricts('11680640')).toEqual([])
  })

  it('폐지된 동네는 검색에 나오지 않는다', async () => {
    expect(await searchDistricts('○○', 'mock')).toEqual([])
  })
})
