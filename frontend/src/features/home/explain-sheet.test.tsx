// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { ExplainSheet } from './explain-sheet'
import { HOME_MOCKS } from './mock'
import type { MeasuredHomeWeekly } from './types'

const HIGH = HOME_MOCKS.high as MeasuredHomeWeekly

function rows() {
  return [...document.querySelectorAll('dl > div')].map((row) => ({
    term: row.querySelector('dt')?.textContent,
    detail: row.querySelector('dd')?.textContent,
  }))
}

describe('ExplainSheet', () => {
  it('판정에 쓴 숫자와 기준을 다섯 줄로 보인다', () => {
    render(<ExplainSheet week={HIGH} open onClose={() => {}} />)

    expect(screen.getByRole('dialog', { name: '이렇게 판단했어요' })).toBeDefined()
    expect(screen.getByText('○○동 · 11월 17일~23일 · 시민 자가보고')).toBeDefined()
    expect(rows()).toEqual([
      { term: '이번 주 증상 보고', detail: '18%참여 128명 중 23명' },
      { term: '기준선', detail: '9%지난 4주 평균' },
      { term: '판정', detail: '많이 늘었어요+3%p 이상 조금 · +8%p 이상 많이' },
      { term: '공개 조건', detail: '참여 100명 이상미만이면 자료 부족으로 표시' },
      { term: '집계 방식', detail: '1인 1주 1회같은 주 수정은 마지막 보고만 · 이상 보고는 제외' },
    ])
  })

  it('판정 기준은 데이터로 받은 값을 쓴다', () => {
    render(
      <ExplainSheet
        week={{ ...HIGH, thresholds: { slightDeltaPp: 4, highDeltaPp: 10 }, publicThreshold: 150 }}
        open
        onClose={() => {}}
      />,
    )

    expect(rows()[2]?.detail).toContain('+4%p 이상 조금 · +10%p 이상 많이')
    expect(rows()[3]?.detail).toContain('참여 150명 이상')
  })

  it('진단이 아니라는 안내를 보이고, 확인을 누르면 닫는다', async () => {
    const onClose = vi.fn()
    render(<ExplainSheet week={HIGH} open onClose={onClose} />)

    expect(
      screen.getByText('시민이 스스로 보고한 자료라 진단이나 공식 유행 판단이 아니에요.'),
    ).toBeDefined()
    await userEvent.setup().click(screen.getByRole('button', { name: '확인' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
