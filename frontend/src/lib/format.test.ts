import { describe, expect, it } from 'vitest'

import { formatCount, formatMonthDayTime } from './format'

describe('formatCount', () => {
  it('천 단위 쉼표를 넣는다', () => {
    expect(formatCount(1280)).toBe('1,280')
  })
})

describe('formatMonthDayTime', () => {
  it('한국 시각의 월 · 일 · 24시간 시각을 시안 모양으로 쓴다', () => {
    expect(formatMonthDayTime('2025-11-20T21:14:00+09:00')).toBe('11월 20일 21:14')
    // 한 자리 날은 그대로, 시각은 두 자리로 맞춘다
    expect(formatMonthDayTime('2025-11-02T08:03:00+09:00')).toBe('11월 2일 08:03')
  })

  it('기기 시간대가 아니라 한국 시각이다 (UTC 자정 직전 → 다음 날 아침)', () => {
    expect(formatMonthDayTime('2025-11-19T23:30:00Z')).toBe('11월 20일 08:30')
  })

  it('자정은 24 가 아니라 00 이다', () => {
    expect(formatMonthDayTime('2025-11-20T00:05:00+09:00')).toBe('11월 20일 00:05')
  })

  it('읽을 수 없는 값이면 null 이다', () => {
    expect(formatMonthDayTime('not-a-date')).toBeNull()
  })
})
