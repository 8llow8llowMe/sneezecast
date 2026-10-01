// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { OfflineNotice } from './offline-notice'

describe('OfflineNotice', () => {
  it('온라인이면 비어 있지만 live 영역은 그려 둔다', () => {
    const { container } = render(
      <OfflineNotice offline={false} receivedAt="2026-11-19T00:00:00Z" />,
    )
    const region = container.firstElementChild

    expect(region?.getAttribute('aria-live')).toBe('polite')
    expect(region?.textContent).toBe('')
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('오프라인이면 받은 시각(한국 시각)과 함께 알린다', () => {
    // 2026-11-19 00:00 UTC = 한국 09:00
    render(<OfflineNotice offline receivedAt="2026-11-19T00:00:00Z" />)

    expect(screen.getByRole('status').textContent).toBe(
      '오프라인이에요. 11월 19일 09:00에 받은 정보예요',
    )
  })

  it('받은 시각을 모르거나 읽을 수 없으면 시각을 지어내지 않는다', () => {
    const { rerender } = render(<OfflineNotice offline />)
    expect(screen.getByRole('status').textContent).toBe('오프라인이에요')

    rerender(<OfflineNotice offline receivedAt="not-a-date" />)
    expect(screen.getByRole('status').textContent).toBe('오프라인이에요')
  })

  it('여백 className 은 안내 상자에 붙는다', () => {
    render(<OfflineNotice offline className="mx-5 mt-3" />)
    expect(screen.getByRole('status').classList).toContain('mt-3')
  })
})
