import { describe, expect, it } from 'vitest'

import { DISTRICT_MOCKS } from '@/features/region/mock'

import { pickMapMock } from './mock'

const SEOGYO = DISTRICT_MOCKS.find((district) => district.code === '11440660')
const YEOKSAM = DISTRICT_MOCKS.find((district) => district.code === '11680640')

describe('pickMapMock', () => {
  it('기본값은 모든 동네가 자료 부족이다 — 수치를 지어내지 않는다', () => {
    for (const value of [undefined, 'unknown', ['unknown', 'example']]) {
      const map = pickMapMock(value, null)
      expect(map.districts.length).toBeGreaterThan(1)
      expect(map.districts.every((district) => district.week.status === 'insufficient')).toBe(true)
    }
  })

  it('처음 고른 동네는 둘러보기 동네가 없으면 홈 목과 같은 ○○동이고 목록 맨 앞이다', () => {
    const map = pickMapMock('example', null)
    expect(map.mineName).toBe('○○동')
    expect(map.districts[0]?.code).toBe(map.mineCode)
    expect(map.districts[0]?.week.regionName).toBe('○○동')
  })

  it('example 은 시안처럼 네 단계가 모두 있고, 발행된 안내가 있는 동네는 그 동네 안내로 잇는다', () => {
    const map = pickMapMock('example', null)
    expect(new Set(map.districts.map((district) => district.week.status))).toEqual(
      new Set(['normal', 'slight', 'high', 'insufficient']),
    )
    const high = map.districts.find((district) => district.week.status === 'high')
    expect(high?.week.notice?.href).toBe(`/notice/${high?.code}/2025-W47?mock=published`)
  })

  it('둘러보기 동네가 주변 동네면 그 자리를 내 동네로 쓰고, 아니면 맨 앞에 더한다', () => {
    const neighbor = pickMapMock('example', SEOGYO ?? null)
    expect(neighbor.mineCode).toBe('11440660')
    expect(neighbor.districts.filter((district) => district.code === '11440660')).toHaveLength(1)

    const other = pickMapMock('example', YEOKSAM ?? null)
    expect(other.mineName).toBe('역삼1동')
    expect(other.districts[0]?.week.regionName).toBe('역삼1동')
    expect(other.districts).toHaveLength(neighbor.districts.length + 1)
  })

  it('empty 는 자료 없음 — 동네가 하나도 없다', () => {
    const map = pickMapMock('empty', YEOKSAM ?? null)
    expect(map.districts).toEqual([])
    expect(map.mineName).toBe('역삼1동')
  })
})
