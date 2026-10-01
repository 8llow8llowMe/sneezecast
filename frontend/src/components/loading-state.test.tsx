// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { LoadingState } from './loading-state'

describe('LoadingState', () => {
  it('불러오는 중을 status 영역으로 알리고 막대는 보조기술에 숨긴다', () => {
    const { container } = render(<LoadingState nav="home" />)
    const status = screen.getByRole('status', { name: '불러오는 중' })

    expect(status.textContent).toBe('불러오고 있어요')
    const bars = container.querySelectorAll('.bg-skeleton')
    expect(bars.length).toBeGreaterThan(0)
    bars.forEach((bar) => expect(bar.getAttribute('aria-hidden')).toBe('true'))
  })

  it('수치 · 상태 라벨을 미리 보이지 않는다', () => {
    const { container } = render(<LoadingState nav="home" />)
    const text = container.textContent ?? ''

    expect(text).not.toMatch(/\d+%|\d+명/)
    expect(text).not.toMatch(/평소 수준|조금 늘었어요|많이 늘었어요|자료 부족/)
  })

  it('주요 메뉴 화면이면 탭바 · 데스크톱 메뉴에 지금 메뉴를 표시한다', () => {
    render(<LoadingState nav="me" />)
    const menus = screen.getAllByRole('navigation', { name: '주요 메뉴' })

    // 데스크톱 머리줄 메뉴와 모바일 · 태블릿 탭바
    expect(menus).toHaveLength(2)
    menus.forEach((menu) => {
      const current = within(menu).getByRole('link', { current: 'page' })
      expect(current.textContent).toContain('내 정보')
    })
  })

  it('홈에만 모바일 하단 보고 버튼 자리를 둔다', () => {
    const home = render(<LoadingState nav="home" />)
    expect(home.container.querySelectorAll('.h-button')).toHaveLength(1)
    home.unmount()

    const me = render(<LoadingState nav="me" />)
    expect(me.container.querySelectorAll('.h-button')).toHaveLength(0)
  })
})
