// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { TabBar } from './tab-bar'

describe('TabBar', () => {
  it('홈 · 지도 · 내 정보 링크를 순서대로 보인다', () => {
    render(<TabBar current="home" />)
    const nav = screen.getByRole('navigation', { name: '주요 메뉴' })
    const links = [...nav.querySelectorAll('a')]

    expect(links.map((link) => link.textContent)).toEqual(['홈', '지도', '내 정보'])
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/', '/map', '/me'])
  })

  it('navSearch 를 모든 링크 뒤에 붙인다 (둘러보기 동네 유지)', () => {
    render(<TabBar current="home" navSearch="region=1111051500" />)
    const links = [...screen.getByRole('navigation').querySelectorAll('a')]

    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/?region=1111051500',
      '/map?region=1111051500',
      '/me?region=1111051500',
    ])
  })

  it('현재 메뉴만 aria-current="page" 이고 네이비 굵은 글자다', () => {
    render(<TabBar current="map" />)
    const current = screen.getByRole('link', { name: '지도' })

    expect(current.getAttribute('aria-current')).toBe('page')
    expect(current.classList).toContain('text-brand')
    expect(screen.getByRole('link', { name: '홈' }).getAttribute('aria-current')).toBeNull()
  })

  it('데스크톱에서는 숨기고 홈 인디케이터 영역을 비운다', () => {
    render(<TabBar current="home" />)
    const { classList } = screen.getByRole('navigation')

    expect(classList).toContain('desktop:hidden')
    expect(classList).toContain('pb-safe')
  })
})
