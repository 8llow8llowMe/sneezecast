import { describe, expect, it } from 'vitest'

import { findDistrict, searchDistricts } from './region-client'

describe('searchDistricts', () => {
  it('동 이름에 검색어가 들어간 행정동을 돌려준다', async () => {
    const found = await searchDistricts('역삼')
    expect(found.map((district) => district.name)).toEqual(['역삼1동', '역삼2동'])
  })

  it('이름이 같은 동은 시군구가 다른 두 곳을 모두 돌려준다', async () => {
    const found = await searchDistricts('신사동')
    expect(found.map((district) => district.sigungu)).toEqual([
      '서울특별시 강남구',
      '서울특별시 관악구',
    ])
  })

  it('시군구로도 찾고 앞뒤 공백은 무시한다', async () => {
    const found = await searchDistricts('  마포구 ')
    expect(found.length).toBeGreaterThan(0)
    found.forEach((district) => expect(district.sigungu).toContain('마포구'))
  })

  it('빈 검색어 · 공백만 있는 검색어는 빈 목록이다', async () => {
    expect(await searchDistricts('')).toEqual([])
    expect(await searchDistricts('   ')).toEqual([])
  })

  it('맞는 동이 없으면 빈 목록이다', async () => {
    expect(await searchDistricts('없는동이름')).toEqual([])
  })
})

describe('findDistrict', () => {
  it('코드로 행정동을 찾는다', async () => {
    expect(await findDistrict('11680640')).toEqual({
      code: '11680640',
      name: '역삼1동',
      sigungu: '서울특별시 강남구',
    })
  })

  it('모르는 코드면 null 이다', async () => {
    expect(await findDistrict('00000000')).toBeNull()
  })
})
