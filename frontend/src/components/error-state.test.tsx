// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { ErrorState } from './error-state'

describe('ErrorState', () => {
  it('제목 · 설명과 다시 시도를 보이고, 누르면 onRetry 를 부른다', async () => {
    const onRetry = vi.fn()
    render(<ErrorState onRetry={onRetry} nav="home" />)

    expect(screen.getByRole('heading', { level: 1, name: '정보를 불러오지 못했어요' })).toBeTruthy()
    expect(screen.getByText(/잠시 후 다시 시도해 주세요\./).textContent).toContain(
      '보고는 연결되면 다시 보낼 수 있어요.',
    )

    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('자료를 대신 보이지 않는다 — 수치 · 상태 라벨이 없다', () => {
    const { container } = render(<ErrorState onRetry={() => {}} nav="home" />)
    const text = container.textContent ?? ''

    expect(text).not.toMatch(/\d+%|\d+명/)
    expect(text).not.toMatch(/평소 수준|조금 늘었어요|많이 늘었어요|자료 부족/)
  })

  it('주요 메뉴 화면이면 탭바 · 데스크톱 메뉴로 다른 메뉴에 갈 수 있다', () => {
    render(<ErrorState onRetry={() => {}} nav="map" />)
    const menus = screen.getAllByRole('navigation', { name: '주요 메뉴' })

    expect(menus).toHaveLength(2)
    menus.forEach((menu) => {
      expect(within(menu).getByRole('link', { current: 'page' }).textContent).toContain('지도')
      expect(within(menu).getByRole('link', { name: /홈/ }).getAttribute('href')).toBe('/')
    })
  })

  it('데스크톱 머리줄은 서비스명(홈 링크) · 홈 · 지도 · 오른쪽 끝 내 정보다', () => {
    render(<ErrorState onRetry={() => {}} nav="home" />)
    const header = screen.getByRole('banner')

    expect(screen.getByRole('link', { name: '우리동네체온계' }).getAttribute('href')).toBe('/')
    const account = screen.getByRole('navigation', { name: '계정 메뉴' })
    expect(header.lastElementChild).toBe(account)
    expect(within(account).getByRole('link').getAttribute('href')).toBe('/me')
  })

  it('메뉴가 없는 화면이면 메뉴 없이 안내만 둔다', () => {
    render(<ErrorState onRetry={() => {}} />)

    expect(screen.queryByRole('navigation')).toBeNull()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy()
  })
})
