// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { PushUnavailable } from './push-unavailable'

let search = ''
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
}))

const IPAD_UA =
  'Mozilla/5.0 (iPad; CPU OS 15_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.6 Mobile/15E148 Safari/604.1'

beforeEach(() => {
  search = ''
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('PushUnavailable', () => {
  it('지원되거나 아직 모르면 그리지 않는다', () => {
    const { container, rerender } = render(
      <PushUnavailable support="supported" regionCode={null} />,
    )
    expect(container.textContent).toBe('')
    rerender(<PushUnavailable support={null} regionCode={null} />)
    expect(container.textContent).toBe('')
  })

  it('홈 화면에 추가해야 하는 iPhone 은 공유 버튼 안내와 설치 안내 링크를 보인다 (Settings-nopush)', () => {
    search = 'region=11440660&mock-auth=member&mock-push=needs-install&confirm=logout'
    render(<PushUnavailable support="needs-install" regionCode="11440660" />)
    const box = screen.getByRole('status')
    expect(box.querySelector('strong')?.textContent).toBe('이 기기에서는 알림을 받을 수 없어요')
    expect(box.textContent).toContain(
      '같은 내용은 홈 상단에서 확인할 수 있어요. iPhone은 Safari 공유 버튼 → 홈 화면에 추가 후 알림을 켤 수 있어요.',
    )
    const link = screen.getByRole('link', { name: '홈 화면에 추가하는 방법 보기' })
    expect(link.getAttribute('href')).toBe(
      '/install?region=11440660&mock-auth=member&mock-push=needs-install',
    )
    expect(link.classList).toContain('min-h-touch')
  })

  it('iPad 는 브라우저 문구와 앱으로 설치 안내다 (Settings-nopush-T)', () => {
    vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue(IPAD_UA)
    render(<PushUnavailable support="needs-install" regionCode={null} />)
    const box = screen.getByRole('status')
    expect(box.querySelector('strong')?.textContent).toBe('이 브라우저에서는 알림을 받을 수 없어요')
    expect(box.textContent).toContain('앱으로 설치하면 알림을 켤 수 있어요.')
    expect(screen.getByRole('link', { name: '홈 화면에 추가하는 방법 보기' })).toBeDefined()
  })

  it('푸시 API 가 없는 브라우저는 설치해도 받지 못해 설치 안내 · 링크 없이 홈 상단 안내만 보인다', () => {
    render(<PushUnavailable support="unsupported" regionCode={null} />)
    const box = screen.getByRole('status')
    expect(box.querySelector('strong')?.textContent).toBe('이 브라우저에서는 알림을 받을 수 없어요')
    expect(box.textContent).toMatch(/같은 내용은 홈 상단에서 확인할 수 있어요\.$/)
    expect(screen.queryByRole('link')).toBeNull()
  })
})
