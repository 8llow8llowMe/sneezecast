import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, unavailableError } from '@/lib/api/api-error'

import type * as regionClient from './region-client'
import { findDistrict } from './region-client'
import { districtFromParam } from './region-param'

vi.mock('./region-client', async (importOriginal) => {
  const actual = await importOriginal<typeof regionClient>()
  return { ...actual, findDistrict: vi.fn(actual.findDistrict) }
})

beforeEach(() => {
  vi.mocked(findDistrict).mockClear()
})

describe('districtFromParam', () => {
  it('아는 코드면 그 행정동이다', async () => {
    expect(await districtFromParam('11440660', 'mock')).toEqual({
      code: '11440660',
      name: '서교동',
      sigungu: '서울특별시 마포구',
    })
  })

  it('값이 여러 개면 첫 값을 쓴다', async () => {
    expect((await districtFromParam(['11680640', '11440660'], 'mock'))?.name).toBe('역삼1동')
  })

  it('출처를 그대로 넘긴다', async () => {
    vi.mocked(findDistrict).mockResolvedValueOnce({
      code: '11440660',
      name: '서교동',
      sigungu: '서울특별시 마포구',
      active: true,
    })
    await districtFromParam('11440660', 'api')
    expect(findDistrict).toHaveBeenCalledWith('11440660', 'api')
  })

  const EMPTY: [string, string | string[] | undefined][] = [
    ['없는 값', undefined],
    ['빈 값', ''],
    ['빈 배열', []],
    ['모르는 코드', '00000000'],
  ]
  it.each(EMPTY)('%s 이면 null 이다', async (_, value) => {
    expect(await districtFromParam(value, 'mock')).toBeNull()
  })

  it('폐지된 코드면 null 이다', async () => {
    vi.mocked(findDistrict).mockResolvedValueOnce({
      code: '11680999',
      name: '옛동',
      sigungu: '서울특별시 강남구',
      active: false,
    })
    expect(await districtFromParam('11680999', 'api')).toBeNull()
  })

  it.each([
    ['일시 장애', unavailableError('network', 0)],
    ['서버 오류', new ApiError({ status: 500, code: 'DISTRICT_999', message: '오류' })],
  ])('API 가 실패하면(%s) null 이다 — 원래 동네로 그린다', async (_, error) => {
    vi.mocked(findDistrict).mockRejectedValueOnce(error)
    expect(await districtFromParam('11440660', 'api')).toBeNull()
  })

  it('프로그램 오류는 숨기지 않는다', async () => {
    vi.mocked(findDistrict).mockRejectedValueOnce(new TypeError('bug'))
    await expect(districtFromParam('11440660', 'api')).rejects.toThrow(TypeError)
  })
})
