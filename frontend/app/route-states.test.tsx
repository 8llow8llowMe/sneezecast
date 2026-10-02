// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import HomeLoading from './(home)/loading'
import RouteError from './error'
import MapLoading from './map/loading'
import MeLoading from './me/loading'
import NotFound from './not-found'

const pathname = vi.hoisted(() => ({ value: '/' }))
vi.mock('next/navigation', () => ({ usePathname: () => pathname.value }))

describe('라우트 공통 상태 (loading · error)', () => {
  beforeEach(() => {
    pathname.value = '/'
  })

  it('불러오는 중은 홈 · 지도 · 내 정보 경계마다 그 메뉴를 지금 메뉴로 그린다', () => {
    const home = render(<HomeLoading />)
    const homeTabBar = screen.getAllByRole('navigation', { name: '주요 메뉴' })[1]
    expect(
      homeTabBar && within(homeTabBar).getByRole('link', { current: 'page' }).textContent,
    ).toContain('홈')
    home.unmount()

    const map = render(<MapLoading />)
    const mapTabBar = screen.getAllByRole('navigation', { name: '주요 메뉴' })[1]
    expect(
      mapTabBar && within(mapTabBar).getByRole('link', { current: 'page' }).textContent,
    ).toContain('지도')
    map.unmount()

    render(<MeLoading />)
    const meTabBar = screen.getAllByRole('navigation', { name: '주요 메뉴' })[1]
    expect(
      meTabBar && within(meTabBar).getByRole('link', { current: 'page' }).textContent,
    ).toContain('내 정보')
  })

  it('오류 화면은 메뉴 밖 화면(로그인)이면 메뉴 없이 그린다', () => {
    pathname.value = '/login'
    render(<RouteError error={new Error('boom')} retry={() => {}} />)
    expect(screen.queryByRole('navigation')).toBeNull()
  })

  it('오류 화면은 내 정보 아래 경로면 내 정보 메뉴를 표시한다', () => {
    pathname.value = '/me/devices'
    render(<RouteError error={new Error('boom')} retry={() => {}} />)
    const tabBar = screen.getAllByRole('navigation', { name: '주요 메뉴' })[1]
    expect(tabBar && within(tabBar).getByRole('link', { current: 'page' }).textContent).toContain(
      '내 정보',
    )
  })

  it('오류 화면의 다시 시도는 Next 의 retry 를 부른다', async () => {
    const retry = vi.fn()
    render(<RouteError error={new Error('boom')} retry={retry} />)

    expect(screen.queryByText('boom')).toBeNull()
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    expect(retry).toHaveBeenCalledTimes(1)
  })

  it('없는 화면(404)은 한국어 안내와 홈 링크를 보이고, 지금 메뉴 표시 없이 메뉴로 다른 화면에 갈 수 있다', () => {
    render(<NotFound />)

    expect(screen.getByRole('heading', { level: 1, name: '화면을 찾을 수 없어요' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '홈으로 가기' }).getAttribute('href')).toBe('/')
    expect(screen.getByRole('link', { name: '우리동네체온계' }).getAttribute('href')).toBe('/')

    const menus = screen.getAllByRole('navigation', { name: '주요 메뉴' })
    expect(menus).toHaveLength(2)
    menus.forEach((menu) => {
      expect(within(menu).queryByRole('link', { current: 'page' })).toBeNull()
      expect(within(menu).getByRole('link', { name: /지도/ }).getAttribute('href')).toBe('/map')
    })
  })
})
