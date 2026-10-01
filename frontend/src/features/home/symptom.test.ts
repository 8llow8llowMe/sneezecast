import { describe, expect, it } from 'vitest'

import { HOME_MOCKS, pickHomeMock } from './mock'
import { BAR_MAX_HEIGHT, barHeights, scaleMax } from './symptom'

describe('scaleMax', () => {
  it('세로축은 최소 24% 범위를 지킨다 — 낮은 비율의 작은 차이를 부풀리지 않는다', () => {
    expect(scaleMax([{ series: [3, 4, 5] }, { series: [1, 2] }])).toBe(24)
  })

  it('비율이 더 높으면 증상군 전체의 최댓값을 범위로 쓴다', () => {
    expect(scaleMax([{ series: [10, 30] }, { series: [40] }])).toBe(40)
  })
})

describe('barHeights', () => {
  it('시안처럼 범위 24% 에서 1% 가 1px 이다', () => {
    expect(barHeights([9, 11, 12, 15, 18, 21, 24], 24)).toEqual([9, 11, 12, 15, 18, 21, 24])
  })

  it('범위를 넘는 값은 최대 높이로 자르고, 아주 작거나 음수인 값도 최소 높이로 보인다', () => {
    expect(barHeights([48, 0, -3], 24)).toEqual([BAR_MAX_HEIGHT, 4, 4])
  })

  it('두 증상군은 같은 범위를 써서 비교할 수 있다', () => {
    const max = scaleMax([{ series: [40] }, { series: [10] }])
    expect(barHeights([40], max)).toEqual([24])
    expect(barHeights([10], max)).toEqual([6])
  })
})

describe('pickHomeMock', () => {
  it('?mock 값으로 상태를 고른다', () => {
    expect(pickHomeMock('high').status).toBe('high')
    expect(pickHomeMock(['slight', 'high']).status).toBe('slight')
  })

  it.each([undefined, '', 'danger', ['unknown']])(
    '모르는 값(%s)이면 자료 부족이다 — 수치를 지어내 보이지 않는다',
    (value) => {
      expect(pickHomeMock(value).status).toBe('insufficient')
    },
  )

  it('자료 부족 목 데이터에는 증상 비율이 없고, 참여자는 공개 기준보다 적다', () => {
    const week = HOME_MOCKS.insufficient
    expect('symptomRate' in week).toBe(false)
    expect(week.participants).toBeLessThan(week.publicThreshold)
  })

  it('수치가 있는 목 데이터는 참여자가 공개 기준 이상이다', () => {
    for (const status of ['normal', 'slight', 'high'] as const) {
      const week = HOME_MOCKS[status]
      expect(week.participants).toBeGreaterThanOrEqual(week.publicThreshold)
    }
  })
})
