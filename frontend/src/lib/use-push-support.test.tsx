// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useDevicePlatform, usePushSupport } from './use-push-support'

let search = ''
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
}))

function Probe() {
  const support = usePushSupport()
  const platform = useDevicePlatform()
  return <p data-testid="probe">{`${support ?? 'null'}/${platform ?? 'null'}`}</p>
}

beforeEach(() => {
  search = ''
})

describe('usePushSupport', () => {
  it('jsdom 처럼 푸시 API 가 없는 브라우저는 unsupported 다', () => {
    render(<Probe />)
    expect(screen.getByTestId('probe').textContent).toBe('unsupported/other')
  })

  it('?mock-push= 덮어쓰기를 따르고 모르는 값은 무시한다', () => {
    search = 'mock-push=needs-install'
    const { rerender } = render(<Probe />)
    expect(screen.getByTestId('probe').textContent).toBe('needs-install/other')

    search = 'mock-push=granted'
    rerender(<Probe />)
    expect(screen.getByTestId('probe').textContent).toBe('unsupported/other')
  })

  it('서버 · 하이드레이션 첫 그림은 덮어쓰기가 있어도 null 이고, 하이드레이션 뒤 브라우저 값으로 바뀐다', async () => {
    search = 'mock-push=supported'
    const html = renderToString(<Probe />)
    expect(html).toContain('null/null')

    const container = document.createElement('div')
    container.innerHTML = html
    document.body.append(container)
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<Probe />, { container, hydrate: true })
    await act(async () => {})
    expect(screen.getByTestId('probe').textContent).toBe('supported/other')
    // 첫 그림이 서버와 같아 하이드레이션 불일치가 없다
    expect(errors).not.toHaveBeenCalled()
    errors.mockRestore()
  })
})
