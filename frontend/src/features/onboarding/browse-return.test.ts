import { describe, expect, it } from 'vitest'

import {
  ME_DEVICES_PATH,
  ME_NICKNAME_PATH,
  ME_PASSWORD_PATH,
  ME_PATH,
  ME_REGION_PATH,
  ME_REPORTS_PATH,
} from '@/features/me/me-paths'
import { OFFICIAL_PATH } from '@/features/official/official-screen'
import { MAIN_NAV } from '@/lib/nav'

import {
  BROWSE_NEXT_PATHS,
  browseRegionHref,
  browseReturnFrom,
  browseReturnHref,
} from './browse-return'

const params = (query: string) => new URLSearchParams(query)

describe('BROWSE_NEXT_PATHS', () => {
  it('머리줄에 동네 이름이 있는 화면이고 각 모듈의 경로와 같다 (동네 안내는 없다)', () => {
    expect(BROWSE_NEXT_PATHS).toEqual([
      MAIN_NAV[0].href,
      MAIN_NAV[1].href,
      OFFICIAL_PATH,
      ME_PATH,
      ME_DEVICES_PATH,
      ME_PASSWORD_PATH,
      ME_REGION_PATH,
      ME_NICKNAME_PATH,
      ME_REPORTS_PATH,
    ])
  })
})

describe('browseReturnFrom (오픈 리다이렉트 방지)', () => {
  it('허용 목록의 경로면 돌아갈 곳 · 둘러보던 동네 · QA 덮어쓰기를 읽는다 (열린 시트 · 목 자료는 뺀다)', () => {
    expect(
      browseReturnFrom(
        params(
          'next=/map&region=11440660&mock-auth=member&mock-provider=kakao&report=start&mock=example',
        ),
      ),
    ).toEqual({ next: '/map', region: '11440660', carried: 'mock-auth=member&mock-provider=kakao' })
  })

  it.each([
    null,
    '',
    '/notice/11440660/2025-W47',
    '/map?x=1',
    '//evil.example',
    'https://evil.example',
    '/login',
  ])('next=%s 면 돌아갈 곳이 없다', (next) => {
    expect(
      browseReturnFrom(params(next === null ? '' : `next=${encodeURIComponent(next)}`)),
    ).toBeNull()
  })

  it('둘러보던 동네가 없거나 비었으면 null 이다', () => {
    expect(browseReturnFrom(params('next=/&region='))?.region).toBeNull()
  })
})

describe('browseRegionHref', () => {
  it('홈으로 돌아가도 next 를 붙이고, 확인한 동네와 덮어쓰기만 남긴다', () => {
    expect(browseRegionHref('/', null, params('region=raw&mock-auth=member&explain=1'))).toBe(
      '/browse/region?next=%2F&mock-auth=member',
    )
    expect(browseRegionHref('/me', '11440660', params('mock-required=region'))).toBe(
      '/browse/region?next=%2Fme&region=11440660&mock-required=region',
    )
  })
})

describe('browseReturnHref', () => {
  const back = { next: '/me', region: '11440660', carried: 'mock-auth=member' }

  it('고른 동네를 둘러보기 동네로 붙이고 덮어쓰기를 남긴다', () => {
    expect(browseReturnHref(back, '11680640')).toBe('/me?region=11680640&mock-auth=member')
  })

  it('동네가 없으면 region 을 뺀다', () => {
    expect(browseReturnHref({ ...back, carried: '' }, null)).toBe('/me')
  })
})
