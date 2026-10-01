import { describe, expect, it } from 'vitest'

import { mainNavKeyFor, navHref } from './nav'

describe('mainNavKeyFor', () => {
  it('주요 메뉴 화면과 그 아래 경로는 그 메뉴다', () => {
    expect(mainNavKeyFor('/')).toBe('home')
    expect(mainNavKeyFor('/map')).toBe('map')
    expect(mainNavKeyFor('/me')).toBe('me')
    expect(mainNavKeyFor('/me/devices')).toBe('me')
    expect(mainNavKeyFor('/me/password')).toBe('me')
  })

  it('주요 메뉴 밖 화면은 null 이다', () => {
    expect(mainNavKeyFor('/login')).toBeNull()
    expect(mainNavKeyFor('/setup/region')).toBeNull()
    expect(mainNavKeyFor('/dev/components')).toBeNull()
  })

  it('이름이 같은 앞부분만 겹치는 경로는 메뉴로 보지 않는다', () => {
    expect(mainNavKeyFor('/meetup')).toBeNull()
    expect(mainNavKeyFor('/maps')).toBeNull()
  })

  it('경로를 모르면 null 이다', () => {
    expect(mainNavKeyFor(null)).toBeNull()
  })
})

describe('navHref', () => {
  it('쿼리가 있으면 붙이고 없으면 경로만 돌려준다', () => {
    expect(navHref('/me', 'region=1111051500')).toBe('/me?region=1111051500')
    expect(navHref('/me')).toBe('/me')
  })
})
