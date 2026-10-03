// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  DATA_SOURCE_COOKIE,
  readBrowserDataSource,
  writeBrowserDataSource,
} from '@/lib/data-source'

import { DataSourceToggle } from './data-source-toggle'

const refresh = vi.fn()

vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh }) }))

afterEach(() => {
  refresh.mockClear()
  document.cookie = `${DATA_SOURCE_COOKIE}=; Path=/; Max-Age=0`
})

describe('DataSourceToggle', () => {
  it('데이터 출처 묶음 안에 두 버튼을 보이고 기본(목데이터)을 누른 상태로 둔다', () => {
    render(<DataSourceToggle />)
    const group = screen.getByRole('group', { name: '데이터 출처' })
    expect(within(group).getAllByRole('button')).toHaveLength(2)
    const api = screen.getByRole('button', { name: '실데이터' })
    const mock = screen.getByRole('button', { name: '목데이터' })
    expect(api.getAttribute('aria-pressed')).toBe('false')
    expect(mock.getAttribute('aria-pressed')).toBe('true')
    expect(mock.classList).toContain('font-bold')
    expect(api.classList).not.toContain('font-bold')
  })

  it('쿠키에 고른 출처를 따른다', () => {
    writeBrowserDataSource('api')
    render(<DataSourceToggle />)
    expect(screen.getByRole('button', { name: '실데이터' }).getAttribute('aria-pressed')).toBe(
      'true',
    )
    expect(screen.getByRole('button', { name: '목데이터' }).getAttribute('aria-pressed')).toBe(
      'false',
    )
  })

  it('다른 쪽을 누르면 출처를 바꿔 쿠키에 쓰고 서버 화면을 다시 그린다', async () => {
    const user = userEvent.setup()
    render(<DataSourceToggle />)

    await user.click(screen.getByRole('button', { name: '실데이터' }))
    expect(readBrowserDataSource()).toBe('api')
    expect(screen.getByRole('button', { name: '실데이터' }).getAttribute('aria-pressed')).toBe(
      'true',
    )
    expect(refresh).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '목데이터' }))
    expect(readBrowserDataSource()).toBe('mock')
    expect(screen.getByRole('button', { name: '목데이터' }).getAttribute('aria-pressed')).toBe(
      'true',
    )
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it('고른 버튼을 다시 눌러도 아무 일 없다', async () => {
    const user = userEvent.setup()
    render(<DataSourceToggle />)
    await user.click(screen.getByRole('button', { name: '목데이터' }))
    expect(refresh).not.toHaveBeenCalled()
    expect(document.cookie).not.toContain(DATA_SOURCE_COOKIE)
  })

  it('키보드 Tab 으로 두 버튼에 닿고 Enter · Space 로 바꾼다', async () => {
    const user = userEvent.setup()
    render(<DataSourceToggle />)
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '실데이터' }))
    await user.keyboard('{Enter}')
    expect(readBrowserDataSource()).toBe('api')
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '목데이터' }))
    await user.keyboard(' ')
    expect(readBrowserDataSource()).toBe('mock')
    expect(refresh).toHaveBeenCalledTimes(2)
  })
})
