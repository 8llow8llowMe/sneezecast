// @vitest-environment jsdom
import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { type MeasuredStatus } from '@/lib/status'

import { StatusGauge } from './status-gauge'

function renderGauge(status: Parameters<typeof StatusGauge>[0]['status']) {
  const { container } = render(<StatusGauge status={status} />)
  const svg = container.querySelector('svg')
  if (!svg) throw new Error('svg 가 없다')
  const segments = [...svg.querySelectorAll('path')]
  return { svg, segments, dot: svg.querySelector('circle') }
}

describe('StatusGauge', () => {
  it('장식이라 보조기술에 읽히지 않는다', () => {
    expect(renderGauge('normal').svg.getAttribute('aria-hidden')).toBe('true')
  })

  it.each([
    ['normal', 0],
    ['slight', 1],
    ['high', 2],
  ] satisfies [MeasuredStatus, number][])(
    '%s 는 %i 번 구간만 진하고 점이 있다',
    (status, active) => {
      const { segments, dot } = renderGauge(status)

      expect(segments).toHaveLength(3)
      segments.forEach((segment, index) => {
        expect(segment.classList.contains('stroke-opacity-inactive')).toBe(index !== active)
      })
      expect(dot).not.toBeNull()
    },
  )

  it('구간 색은 왼쪽부터 평소 수준 · 조금 · 많이 순서다', () => {
    const { segments } = renderGauge('slight')
    expect(segments.map((segment) => segment.getAttribute('class'))).toEqual([
      expect.stringContaining('stroke-status-normal'),
      expect.stringContaining('stroke-status-slight'),
      expect.stringContaining('stroke-status-high'),
    ])
  })

  it('자료 부족이면 세 구간 모두 회색이고 점이 없다', () => {
    const { segments, dot } = renderGauge('insufficient')

    segments.forEach((segment) => {
      expect(segment.classList).toContain('stroke-inactive-bar')
      expect(segment.getAttribute('class')).not.toMatch(/stroke-status-/)
    })
    expect(dot).toBeNull()
  })
})
