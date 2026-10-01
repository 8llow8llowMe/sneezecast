// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { HomeScreen } from './home-screen'
import { HOME_MOCKS } from './mock'
import { officialHref, OfficialRow } from './official'

// 테스트에는 Next 라우터가 없다. 홈을 그릴 때만 쓴다
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(''),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}))

describe('officialHref', () => {
  it('목 주소에 쿼리가 있으면 `&`, 없으면 `?` 로 둘러보기 동네를 잇는다', () => {
    const official = HOME_MOCKS.normal.official
    expect(officialHref(official)).toBe('/official?mock=published')
    expect(officialHref(official, 'region=1111051500')).toBe(
      '/official?mock=published&region=1111051500',
    )
    expect(officialHref({ ...official, href: '/official' }, 'region=1111051500')).toBe(
      '/official?region=1111051500',
    )
  })
})

describe('OfficialRow', () => {
  it('공식 정보 화면으로 가고, 예시 발표와 같은 목 상태를 고른다', () => {
    render(<OfficialRow official={HOME_MOCKS.insufficient.official} />)
    expect(screen.getByRole('link').getAttribute('href')).toBe('/official?mock=published')
  })

  it('둘러보기 동네를 주소 뒤에 남긴다', () => {
    render(<OfficialRow official={HOME_MOCKS.normal.official} navSearch="region=1111051500" />)
    expect(screen.getByRole('link').getAttribute('href')).toBe(
      '/official?mock=published&region=1111051500',
    )
  })
})

describe('홈의 공식 정보 진입점', () => {
  it('공식 정보 행과 `공식 예방수칙 보기` 가 같은 주소(목 상태 · 둘러보기 동네)로 간다', () => {
    render(<HomeScreen week={HOME_MOCKS.normal} regionCode="1111051500" />)

    const expected = '/official?mock=published&region=1111051500'
    const rows = screen
      .getAllByRole('link')
      .filter((link) => link.textContent?.includes('전국 인플루엔자 유행주의보'))
    expect(rows.map((link) => link.getAttribute('href'))).toEqual([expected, expected])
    expect(screen.getByRole('link', { name: '공식 예방수칙 보기' }).getAttribute('href')).toBe(
      expected,
    )
  })
})
