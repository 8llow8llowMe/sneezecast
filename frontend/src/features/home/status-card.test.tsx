// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { HOME_MOCKS } from './mock'
import { StatusCard } from './status-card'

describe('StatusCard', () => {
  it('수치가 있는 주는 상태 · 요약 · 참여 수와 증상 보고 비율을 보인다', () => {
    render(<StatusCard week={HOME_MOCKS.high} />)
    const card = screen.getByRole('region', { name: '우리 동네 이번 주 상태' })

    expect(card.textContent).toContain('많이 늘었어요')
    expect(card.textContent).toContain('발열·기침 보고가 지난 4주보다 많이 늘었어요')
    expect(card.textContent).toContain('참여 128명 · 증상 보고 18%')
    expect(screen.queryByRole('progressbar')).toBeNull()
  })

  it('자료 부족이면 증상 비율 대신 참여 진행 막대만 보인다', () => {
    render(<StatusCard week={HOME_MOCKS.insufficient} />)
    const card = screen.getByRole('region', { name: '우리 동네 이번 주 상태' })

    // 퍼센트 수치가 하나도 없어야 한다 (루트 CLAUDE.md "자료 부족이면 수치로 위험을 암시하지 않는다")
    expect(card.textContent).not.toMatch(/%/)
    expect(card.textContent).not.toContain('증상 보고')
    expect(card.textContent).toContain('64 / 100명')

    const bar = screen.getByRole('progressbar', { name: '우리 동네 참여 인원' })
    expect(bar.getAttribute('aria-valuenow')).toBe('64')
    expect(bar.getAttribute('aria-valuetext')).toBe('64명 참여, 공개 기준 100명')
  })

  it('자료 부족이면 상태 글자에 상태색을 쓰지 않는다', () => {
    render(<StatusCard week={HOME_MOCKS.insufficient} />)
    const word = screen.getByText('자료 부족')

    expect(word.classList).toContain('text-status-insufficient-text')
    expect(word.className).not.toMatch(/text-status-(normal|slight|high)/)
  })
})
