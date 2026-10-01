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

  it('보고 버튼 글자를 바꿀 수 있고, 데스크톱 메뉴 링크에 navSearch 를 붙인다 (비회원 홈)', async () => {
    const { onReportClick } = renderHeader({
      reportLabel: '로그인하고 보고하기',
      navSearch: 'region=1111051500',
    })

    await userEvent.setup().click(screen.getByRole('button', { name: '로그인하고 보고하기' }))
    expect(onReportClick).toHaveBeenCalledTimes(1)
    const nav = screen.getByRole('navigation', { name: '주요 메뉴', hidden: true })
    expect([...nav.querySelectorAll('a')].map((link) => link.getAttribute('href'))).toEqual([
      '/?region=1111051500',
      '/map?region=1111051500',
      '/me?region=1111051500',
    ])
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

  it('제목이 있으면 모바일 · 태블릿은 동네 버튼 대신 제목을 보이고, 모바일에서는 알림을 숨긴다 (내 정보)', () => {
    renderHeader({ title: '내 정보', current: 'me' })

    // 제목은 화면 제목(h1)이고 데스크톱에서 숨는다
    expect(screen.getByRole('heading', { level: 1, name: '내 정보' }).classList).toContain(
      'desktop:hidden',
    )
    const region = screen.getByRole('button', { name: '동네 바꾸기, 현재 ○○동' })
    expect(region.classList).toContain('hidden')
    expect(region.classList).toContain('desktop:flex')
    const bellWrapper = screen.getByRole('button', { name: '알림 설정' }).parentElement
    expect(bellWrapper?.classList).toContain('hidden')
    expect(bellWrapper?.classList).toContain('tablet:contents')
  })

  it('제목이 없으면 동네 버튼 · 알림이 모바일에서도 보인다', () => {
    renderHeader()

    expect(screen.getByRole('button', { name: '동네 바꾸기, 현재 ○○동' }).classList).not.toContain(
      'hidden',
    )
    expect(screen.getByRole('button', { name: '알림 설정' }).parentElement?.tagName).toBe('HEADER')
  })
})
