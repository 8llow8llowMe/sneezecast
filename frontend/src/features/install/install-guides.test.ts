import { describe, expect, it } from 'vitest'

import { installSearch } from './install-entry'
import { guideFor } from './install-guides'

describe('guideFor', () => {
  it.each([
    ['iphone', 'iphone'],
    ['android', 'android'],
    ['ipad', 'apple'],
    ['mac-safari', 'apple'],
    ['chromium', 'browser'],
  ] as const)('%s 는 %s 묶음 하나다', (platform, expected) => {
    expect(guideFor(platform)).toBe(expected)
  })

  it('모르는 기기 · 판별 전이면 null 이다 (폭에 맞는 두 묶음을 다 보인다)', () => {
    expect(guideFor('other')).toBeNull()
    expect(guideFor(null)).toBeNull()
  })
})

describe('installSearch', () => {
  it('동네와 회원 · 푸시 덮어쓰기만 남기고 다른 쿼리는 뺀다', () => {
    const params = new URLSearchParams(
      'mock-push=needs-install&confirm=logout&mock-auth=member&mock-provider=kakao',
    )
    expect(installSearch('11440660', params)).toBe(
      'region=11440660&mock-auth=member&mock-push=needs-install',
    )
  })

  it('남길 것이 없으면 빈 문자열이다', () => {
    expect(installSearch(null, new URLSearchParams('region=unknown'))).toBe('')
  })
})
