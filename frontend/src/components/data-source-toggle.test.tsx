// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
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
  it('지금 출처(기본 목데이터)를 스위치 상태로 보인다', () => {
    render(<DataSourceToggle />)
    const toggle = screen.getByRole('switch', { name: /실데이터 사용/ })
    expect(toggle.getAttribute('aria-checked')).toBe('false')
    expect(screen.getByText('목데이터').classList).toContain('bg-fg')
    expect(screen.getByText('실데이터').classList).not.toContain('bg-fg')
  })

  it('쿠키에 고른 출처를 따른다', () => {
    writeBrowserDataSource('api')
    render(<DataSourceToggle />)
    expect(screen.getByRole('switch').getAttribute('aria-checked')).toBe('true')
  })

  it('누르면 출처를 바꿔 쿠키에 쓰고 서버 화면을 다시 그린다', async () => {
    const user = userEvent.setup()
    render(<DataSourceToggle />)
    const toggle = screen.getByRole('switch')

    await user.click(toggle)
    expect(readBrowserDataSource()).toBe('api')
    expect(toggle.getAttribute('aria-checked')).toBe('true')
    expect(refresh).toHaveBeenCalledTimes(1)

    await user.click(toggle)
    expect(readBrowserDataSource()).toBe('mock')
    expect(toggle.getAttribute('aria-checked')).toBe('false')
    expect(refresh).toHaveBeenCalledTimes(2)
  })
})
