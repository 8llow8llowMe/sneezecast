// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ProgressBar } from './progress-bar'

function fillWidth() {
  const bar = screen.getByRole('progressbar')
  return (bar.firstElementChild as HTMLElement).style.width
}

describe('ProgressBar', () => {
  it('이름과 현재 값을 보조기술에 알린다', () => {
    render(<ProgressBar value={64} max={100} label="우리 동네 참여 인원" valueText="64명 참여" />)
    const bar = screen.getByRole('progressbar', { name: '우리 동네 참여 인원' })

    expect(bar.getAttribute('aria-valuenow')).toBe('64')
    expect(bar.getAttribute('aria-valuemax')).toBe('100')
    expect(bar.getAttribute('aria-valuetext')).toBe('64명 참여')
    expect(fillWidth()).toBe('64%')
  })

  it('valueText 가 없으면 "값 / 최대" 로 읽힌다', () => {
    render(<ProgressBar value={30} max={100} label="참여" />)
    expect(screen.getByRole('progressbar').getAttribute('aria-valuetext')).toBe('30 / 100')
  })

  it.each([
    [150, 100, '100%'],
    [-5, 100, '0%'],
    [10, 0, '0%'],
  ])('값 %i / 최대 %i 는 채움 %s 로 자른다', (value, max, expected) => {
    render(<ProgressBar value={value} max={max} label="참여" />)
    expect(fillWidth()).toBe(expected)
  })
})
