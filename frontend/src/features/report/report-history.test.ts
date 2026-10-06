import { describe, expect, it } from 'vitest'

import { listPastReports } from './report-history'

// 2026-10-06(화) 12:00 KST — 2026-W41
const NOW = new Date('2026-10-06T03:00:00Z')

describe('listPastReports', () => {
  it('실데이터는 요청하지 않고 불러올 수 없음이다 — 지어낸 이력을 보이지 않는다', () => {
    expect(listPastReports('api', { now: NOW })).toEqual({ status: 'unavailable' })
    // 목 재현 쿼리가 붙어도 실데이터는 그대로다
    expect(listPastReports('api', { now: NOW, empty: true })).toEqual({ status: 'unavailable' })
  })

  it('목은 지난 주부터 거꾸로 예시 보고다. 보고하지 않은 주(W37)는 없고 이번 주는 넣지 않는다', () => {
    const past = listPastReports('mock', { now: NOW })
    expect(past).toEqual({
      status: 'ready',
      reports: [
        { isoWeek: '2026-W40', answer: { kind: 'none' } },
        { isoWeek: '2026-W39', answer: { kind: 'symptom', symptoms: ['respiratory'] } },
        { isoWeek: '2026-W38', answer: { kind: 'none' } },
        {
          isoWeek: '2026-W36',
          answer: { kind: 'symptom', symptoms: ['respiratory', 'gastrointestinal'] },
        },
        { isoWeek: '2026-W35', answer: { kind: 'none' } },
      ],
    })
  })

  it('해가 바뀌어도 ISO 주로 거꾸로 센다 (2026-W02 → 2025-W52)', () => {
    const past = listPastReports('mock', { now: new Date('2026-01-14T03:00:00Z') })
    expect(past.status === 'ready' && past.reports.map((report) => report.isoWeek)).toEqual([
      '2026-W02',
      '2026-W01',
      '2025-W52',
      '2025-W50',
      '2025-W49',
    ])
  })

  it('목 빈 목록 재현(empty)이면 지난 주 보고가 없다', () => {
    expect(listPastReports('mock', { now: NOW, empty: true })).toEqual({
      status: 'ready',
      reports: [],
    })
  })
})
