import { describe, expect, it } from 'vitest'

import { districtFromParam } from './region-param'

describe('districtFromParam', () => {
  it('아는 코드면 그 행정동이다', async () => {
    expect((await districtFromParam('11440660'))?.name).toBe('서교동')
  })

  it('값이 여러 개면 첫 값을 쓴다', async () => {
    expect((await districtFromParam(['11680640', '11440660']))?.name).toBe('역삼1동')
  })

  const EMPTY: [string, string | string[] | undefined][] = [
    ['없는 값', undefined],
    ['빈 값', ''],
    ['빈 배열', []],
    ['모르는 코드', '00000000'],
  ]
  it.each(EMPTY)('%s 이면 null 이다', async (_, value) => {
    expect(await districtFromParam(value)).toBeNull()
  })
})
