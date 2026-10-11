import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import { PAST_REPORTS_PATH, ReportResponseError } from './report-api'
import { listPastReports } from './report-history'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

// 2026-10-06(화) 12:00 KST — 2026-W41
const NOW = new Date('2026-10-06T03:00:00Z')

/** 백엔드 `WeeklyReportResponse` 한 줄 */
const row = (isoWeek: string, ...codes: string[]) => ({
  isoWeek,
  districtCode: '11680640',
  symptomGroups: codes.map((code) => ({ code, name: code, description: code })),
  reportedAt: '2026-09-28T01:00:00Z',
  updatedAt: '2026-09-29T01:00:00Z',
})

beforeEach(() => {
  vi.mocked(apiRequest).mockReset()
})

describe('listPastReports (실데이터)', () => {
  it('GET /reports/me 의 contents 를 서버 순서 그대로 주와 보고 답으로 옮긴다. 목 재현 · 지금 시각은 보지 않는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({
      contents: [
        row('2026-W40'),
        row('2026-W39', 'RESPIRATORY'),
        row('2026-W36', 'RESPIRATORY', 'ENTERIC'),
      ],
      hasNext: false,
    })

    expect(await listPastReports('api', { now: NOW, empty: true })).toEqual([
      { isoWeek: '2026-W40', answer: { kind: 'none' } },
      { isoWeek: '2026-W39', answer: { kind: 'symptom', symptoms: ['respiratory'] } },
      {
        isoWeek: '2026-W36',
        answer: { kind: 'symptom', symptoms: ['respiratory', 'gastrointestinal'] },
      },
    ])
    expect(apiRequest).toHaveBeenCalledWith(PAST_REPORTS_PATH)
    expect(PAST_REPORTS_PATH).toBe('/api/v1/reports/me')
  })

  it('지난 보고가 없으면 빈 목록이다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ contents: [], hasNext: false })
    expect(await listPastReports('api')).toEqual([])
  })

  it('한 줄이라도 옮길 수 없으면(모르는 증상군 · 읽을 수 없는 주) 목록 전체를 거부한다 — 다른 보고로 보이지 않게', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({
      contents: [row('2026-W40'), row('2026-W39', 'SKIN')],
      hasNext: false,
    })
    await expect(listPastReports('api')).rejects.toBeInstanceOf(ReportResponseError)

    vi.mocked(apiRequest).mockResolvedValueOnce({ contents: [row('2026-40')], hasNext: false })
    await expect(listPastReports('api')).rejects.toBeInstanceOf(ReportResponseError)
  })

  it('요청 실패(동의 전 · 일시 장애)는 거부한다', async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(
      new ApiError({ status: 403, code: 'SECURITY_006', message: '거절' }),
    )
    await expect(listPastReports('api')).rejects.toMatchObject({ code: 'SECURITY_006' })
  })
})

describe('listPastReports (목)', () => {
  it('지난 주부터 거꾸로 예시 보고다. 보고하지 않은 주(W37)는 없고 이번 주는 넣지 않는다', async () => {
    expect(await listPastReports('mock', { now: NOW })).toEqual([
      { isoWeek: '2026-W40', answer: { kind: 'none' } },
      { isoWeek: '2026-W39', answer: { kind: 'symptom', symptoms: ['respiratory'] } },
      { isoWeek: '2026-W38', answer: { kind: 'none' } },
      {
        isoWeek: '2026-W36',
        answer: { kind: 'symptom', symptoms: ['respiratory', 'gastrointestinal'] },
      },
      { isoWeek: '2026-W35', answer: { kind: 'none' } },
    ])
    expect(apiRequest).not.toHaveBeenCalled()
  })

  it('해가 바뀌어도 ISO 주로 거꾸로 센다 (2026-W02 → 2025-W52)', async () => {
    const past = await listPastReports('mock', { now: new Date('2026-01-14T03:00:00Z') })
    expect(past.map((report) => report.isoWeek)).toEqual([
      '2026-W02',
      '2026-W01',
      '2025-W52',
      '2025-W50',
      '2025-W49',
    ])
  })

  it('목 빈 목록 재현(empty)이면 지난 주 보고가 없다', async () => {
    expect(await listPastReports('mock', { now: NOW, empty: true })).toEqual([])
  })
})
