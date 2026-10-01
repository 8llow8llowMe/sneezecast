// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Callout } from './callout'
import { InfoIcon } from './icons'

describe('Callout', () => {
  it('기본은 정보 안내(연한 파랑 · 네이비)이고 role="status" 로 읽힌다', () => {
    render(<Callout>11월 19일에 보고했어요. 수정하면 집계에는 마지막 보고만 반영돼요.</Callout>)
    const callout = screen.getByRole('status')

    expect(callout.textContent).toBe(
      '11월 19일에 보고했어요. 수정하면 집계에는 마지막 보고만 반영돼요.',
    )
    expect(callout.classList).toContain('bg-info-bg')
    expect(callout.classList).toContain('text-brand')
  })

  it('중립 안내는 회색 바탕이고 앞에 아이콘을 둘 수 있다', () => {
    render(
      <Callout tone="neutral" icon={<InfoIcon />}>
        안내
      </Callout>,
    )
    const callout = screen.getByRole('status')

    expect(callout.classList).toContain('bg-section')
    expect(callout.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
  })
})
