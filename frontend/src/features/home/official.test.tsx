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
  it('목이면 공식 정보 목 예시를 고르고, 쿼리가 있으면 `&`, 없으면 `?` 로 둘러보기 동네를 잇는다', () => {
    expect(officialHref(undefined, true)).toBe('/official?mock=published')
    expect(officialHref('region=1111051500', true)).toBe(
      '/official?mock=published&region=1111051500',
    )
  })

  it('실데이터는 목 상태 없이 공식 정보 화면 주소다', () => {
    expect(officialHref()).toBe('/official')
    expect(officialHref('region=1111051500')).toBe('/official?region=1111051500')
  })
})

describe('OfficialRow', () => {
  it('공식 정보 화면으로 가고, 목이면 예시 응답과 같은 목 상태를 고른다', () => {
    render(<OfficialRow mock />)
    expect(screen.getByRole('link').getAttribute('href')).toBe('/official?mock=published')
  })

  it('둘러보기 동네를 주소 뒤에 남긴다', () => {
    render(<OfficialRow navSearch="region=1111051500" mock />)
    expect(screen.getByRole('link').getAttribute('href')).toBe(
      '/official?mock=published&region=1111051500',
    )
  })

  it('발표 단계 · 감염병 없이 질병관리청 공식 정보로 가는 행만 그린다', () => {
    render(<OfficialRow />)
    const link = screen.getByRole('link')
    expect(link.getAttribute('href')).toBe('/official')
    expect(link.textContent).toContain('질병관리청 공식 정보')
    expect(link.textContent).not.toMatch(/유행|주의보|인플루엔자/)
  })
})

describe('홈의 공식 정보 진입점', () => {
  it('목 홈의 공식 정보 행과 `질병관리청 공식 정보 보기` 가 같은 주소(목 상태 · 둘러보기 동네)로 간다', () => {
    render(<HomeScreen week={HOME_MOCKS.normal} regionCode="1111051500" />)

    const expected = '/official?mock=published&region=1111051500'
    const rows = screen
      .getAllByRole('link')
      .filter((link) => link.textContent?.includes('감염병 발생 현황'))
    expect(rows.map((link) => link.getAttribute('href'))).toEqual([expected, expected])
    expect(
      screen.getByRole('link', { name: '질병관리청 공식 정보 보기' }).getAttribute('href'),
    ).toBe(expected)
  })
})
