import { describe, expect, it } from 'vitest'

import { meSearch, notificationsHref, reportHrefFor } from './me-paths'

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

describe('notificationsHref', () => {
  it('동네 · 회원 덮어쓰기 · 알림 덮어쓰기만 남기고 다른 쿼리는 뺀다', () => {
    const params = new URLSearchParams(
      'region=raw&mock-auth=member&mock-push=needs-install&confirm=logout&mock=high',
    )
    expect(notificationsHref('11440660', params)).toBe(
      '/me/notifications?region=11440660&mock-auth=member&mock-push=needs-install',
    )
  })

  it('남길 쿼리가 없으면 경로만이다', () => {
    expect(notificationsHref(null, new URLSearchParams('region=unknown'))).toBe('/me/notifications')
  })
})

describe('reportHrefFor', () => {
  it('비회원은 보고하려던 로그인(동네를 남김), 회원은 동네를 남긴 홈의 보고 진입이다', () => {
    expect(reportHrefFor('guest', '11440660')).toBe('/login?region=11440660&intent=report')
    expect(reportHrefFor('guest', null)).toBe('/login?intent=report')
    expect(reportHrefFor('member-no-consent', null)).toBe('/?report=health-consent')
    expect(reportHrefFor('member', '11440660')).toBe('/?region=11440660&report=start')
  })
})
