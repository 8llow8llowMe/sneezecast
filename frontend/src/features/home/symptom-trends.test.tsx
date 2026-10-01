// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { HOME_MOCKS } from './mock'
import { SymptomTrends } from './symptom-trends'

describe('SymptomTrends', () => {
  it('증상군마다 변화 문구와 막대 그래프를 보인다', () => {
    render(<SymptomTrends week={HOME_MOCKS.high} />)

    expect(screen.getByRole('heading', { name: '증상별 변화' })).toBeDefined()
    expect(screen.getByText('발열·기침·인후통')).toBeDefined()
    expect(screen.getByText('구토·설사')).toBeDefined()

    const charts = screen.getAllByRole('img')
    expect(charts).toHaveLength(2)
    expect(charts[0]?.getAttribute('aria-label')).toBe(
      '최근 7주 증상 보고 비율 9%, 11%, 12%, 15%, 18%, 21%, 24%',
    )
  })

  it('늘어난 증상군만 문구와 마지막 막대에 상태색을 쓴다', () => {
    render(<SymptomTrends week={HOME_MOCKS.high} />)

    expect(screen.getByText('많이 늘었어요').classList).toContain('text-status-high-text')
    expect(screen.getByText('비슷해요').classList).toContain('text-fg-sub')

    const [respiratory, gastro] = screen.getAllByRole('img')
    expect(respiratory?.lastElementChild?.classList).toContain('bg-status-high')
    expect(respiratory?.firstElementChild?.classList).toContain('bg-inactive-bar')
    expect(gastro?.lastElementChild?.classList).toContain('bg-muted-bar')
  })

  it('자료 부족이면 막대 · 수치 없이 안내만 보인다', () => {
    render(<SymptomTrends week={HOME_MOCKS.insufficient} />)

    expect(screen.queryAllByRole('img')).toHaveLength(0)
    expect(screen.getByText(/보고가 충분히 모이면 증상별 변화를 보여드려요/)).toBeDefined()
    expect(document.body.textContent).not.toMatch(/%/)
  })
})
