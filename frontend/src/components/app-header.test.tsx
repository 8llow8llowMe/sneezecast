// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { AppHeader, type AppHeaderProps } from './app-header'

function renderHeader(props: Partial<AppHeaderProps> = {}) {
  const handlers = {
    onRegionClick: vi.fn(),
    onNotificationClick: vi.fn(),
    onReportClick: vi.fn(),
  }
  render(<AppHeader regionName="○○동" current="home" {...handlers} {...props} />)
  return handlers
}

describe('AppHeader', () => {
  it('동네 버튼은 현재 동네를 이름에 담고, 누르면 onRegionClick 을 부른다', async () => {
    const { onRegionClick } = renderHeader()
    const button = screen.getByRole('button', { name: '동네 바꾸기, 현재 ○○동' })

    expect(button.textContent).toBe('○○동')
    await userEvent.setup().click(button)
    expect(onRegionClick).toHaveBeenCalledTimes(1)
  })

  it('알림 설정 · 보고 버튼이 각 동작을 부른다', async () => {
    const user = userEvent.setup()
    const { onNotificationClick, onReportClick } = renderHeader()

    await user.click(screen.getByRole('button', { name: '알림 설정' }))
    await user.click(screen.getByRole('button', { name: '이번 주 건강 보고하기' }))
    expect(onNotificationClick).toHaveBeenCalledTimes(1)
    expect(onReportClick).toHaveBeenCalledTimes(1)
  })

  it('데스크톱 메뉴는 현재 메뉴에 aria-current 를 단다', () => {
    renderHeader({ current: 'me' })
    const nav = screen.getByRole('navigation', { name: '주요 메뉴', hidden: true })
    const current = [...nav.querySelectorAll('a')].find((link) => link.textContent === '내 정보')

    expect(current?.getAttribute('aria-current')).toBe('page')
    expect(nav.classList).toContain('hidden')
    expect(nav.classList).toContain('desktop:flex')
  })

  it('서비스명은 데스크톱에서만, 보고 버튼은 태블릿부터 보인다', () => {
    renderHeader()

    expect(screen.getByText('우리동네체온계').classList).toContain('desktop:inline')
    const reportWrapper = screen.getByRole('button', {
      name: '이번 주 건강 보고하기',
    }).parentElement
    expect(reportWrapper?.classList).toContain('hidden')
    expect(reportWrapper?.classList).toContain('tablet:contents')
  })
})
