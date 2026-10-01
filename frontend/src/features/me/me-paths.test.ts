import { describe, expect, it } from 'vitest'

import { meSearch, reportHrefFor } from './me-paths'

describe('meSearch', () => {
  it('확인한 동네 코드와 목 덮어쓰기만 남기고 다른 쿼리(confirm · notice 등)는 뺀다', () => {
    const params = new URLSearchParams(
      'region=raw&mock-auth=member&mock-provider=kakao&confirm=logout&notice=password-set',
    )
    expect(meSearch('11440660', params)).toBe(
      'region=11440660&mock-auth=member&mock-provider=kakao',
    )
  })

  it('동네를 찾지 못했으면(null) region 을 남기지 않고, 덧붙일 쿼리를 끝에 붙인다', () => {
    expect(
      meSearch(null, new URLSearchParams('region=unknown'), { notice: 'password-changed' }),
    ).toBe('notice=password-changed')
  })
})

describe('reportHrefFor', () => {
  it('비회원은 로그인, 회원은 동네를 남긴 홈의 보고 진입이다', () => {
    expect(reportHrefFor('guest', '11440660')).toBe('/login')
    expect(reportHrefFor('member-no-consent', null)).toBe('/?report=health-consent')
    expect(reportHrefFor('member', '11440660')).toBe('/?region=11440660&report=start')
  })
})
