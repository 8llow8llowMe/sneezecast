import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { apiRequest } from '@/lib/api/client'

import {
  CURRENT_REPORT_PATH,
  deleteCurrentReport,
  fetchCurrentReport,
  kstDateLabel,
  putCurrentReport,
  ReportResponseError,
  toSymptomGroups,
} from './report-api'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

beforeEach(() => {
  vi.mocked(apiRequest).mockReset()
})

/** backend `WeeklyReportResponse` */
function reportBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    isoWeek: '2026-W40',
    districtCode: '11680640',
    symptomGroups: [],
    reportedAt: '2026-10-01T05:12:00Z',
    updatedAt: '2026-10-01T05:12:00Z',
    ...overrides,
  }
}

const RESPIRATORY = { code: 'RESPIRATORY', name: '호흡기', description: '발열 · 기침 · 인후통' }
const ENTERIC = { code: 'ENTERIC', name: '장관', description: '구토 · 설사' }

describe('toSymptomGroups — 화면 증상 → backend SymptomGroup', () => {
  it('증상 없음은 빈 배열이다 (건강한 보고도 정상 보고)', () => {
    expect(toSymptomGroups({ kind: 'none' })).toEqual([])
  })

  it('respiratory ↔ RESPIRATORY · gastrointestinal ↔ ENTERIC, 같은 값은 한 번만 싣는다', () => {
    expect(toSymptomGroups({ kind: 'symptom', symptoms: ['respiratory'] })).toEqual(['RESPIRATORY'])
    expect(
      toSymptomGroups({ kind: 'symptom', symptoms: ['gastrointestinal', 'respiratory'] }),
    ).toEqual(['ENTERIC', 'RESPIRATORY'])
    expect(toSymptomGroups({ kind: 'symptom', symptoms: ['respiratory', 'respiratory'] })).toEqual([
      'RESPIRATORY',
    ])
  })
})

describe('kstDateLabel — UTC 시각을 KST 날짜로', () => {
  const originalTz = process.env.TZ

  afterEach(() => {
    // 처음에 없던 값은 지운다 — 문자열 "undefined" 로 남기면 다른 테스트의 시간대가 바뀐다
    if (originalTz === undefined) delete process.env.TZ
    else process.env.TZ = originalTz
  })

  it('UTC 자정 근처 경계: 15:00Z 부터 다음 날이다 (KST = UTC+9)', () => {
    expect(kstDateLabel('2026-10-01T14:59:59Z')).toBe('10월 1일')
    expect(kstDateLabel('2026-10-01T15:00:00Z')).toBe('10월 2일')
    expect(kstDateLabel('2026-09-30T23:30:00Z')).toBe('10월 1일')
    // 해 · 달이 바뀌는 경계
    expect(kstDateLabel('2026-12-31T15:00:00Z')).toBe('1월 1일')
  })

  it('브라우저 시간대와 무관하다', () => {
    process.env.TZ = 'America/Los_Angeles'
    expect(kstDateLabel('2026-10-01T15:00:00Z')).toBe('10월 2일')
    process.env.TZ = 'UTC'
    expect(kstDateLabel('2026-10-01T14:59:59Z')).toBe('10월 1일')
  })

  it('읽을 수 없는 시각이면 ReportResponseError 다', () => {
    expect(() => kstDateLabel('어제')).toThrow(ReportResponseError)
  })
})

describe('putCurrentReport', () => {
  it('PUT /api/v1/reports/current 로 동네 코드 · 증상군만 보낸다 — 보고 주는 싣지 않는다', async () => {
    vi.mocked(apiRequest).mockResolvedValue(reportBody({ symptomGroups: [RESPIRATORY, ENTERIC] }))

    const report = await putCurrentReport('11680640', {
      kind: 'symptom',
      symptoms: ['respiratory', 'gastrointestinal'],
    })

    expect(apiRequest).toHaveBeenCalledWith(CURRENT_REPORT_PATH, {
      method: 'PUT',
      body: { districtCode: '11680640', symptomGroups: ['RESPIRATORY', 'ENTERIC'] },
    })
    expect(CURRENT_REPORT_PATH).toBe('/api/v1/reports/current')
    expect(report).toEqual({
      report: {
        answer: { kind: 'symptom', symptoms: ['respiratory', 'gastrointestinal'] },
        reportedLabel: '10월 1일',
      },
      isoWeek: '2026-W40',
      firstSubmission: true,
    })
  })

  it('증상 없음은 빈 배열로 보내고 증상 없음으로 받는다', async () => {
    vi.mocked(apiRequest).mockResolvedValue(reportBody())

    const report = await putCurrentReport('11680640', { kind: 'none' })

    expect(vi.mocked(apiRequest).mock.calls[0]?.[1]).toMatchObject({
      body: { districtCode: '11680640', symptomGroups: [] },
    })
    expect(report.report.answer).toEqual({ kind: 'none' })
  })

  it('보고한 날은 첫 보고 시각(reportedAt)의 KST 날짜다 — 고친 시각(updatedAt)이 아니다', async () => {
    vi.mocked(apiRequest).mockResolvedValue(
      reportBody({ reportedAt: '2026-09-28T15:30:00Z', updatedAt: '2026-10-01T05:12:00Z' }),
    )
    const received = await putCurrentReport('11680640', { kind: 'none' })
    expect(received.report.reportedLabel).toBe('9월 29일')
  })

  it('처음 저장인지는 reportedAt === updatedAt 으로 정한다 — 다르면 이미 있던 보고를 고친 것이다', async () => {
    vi.mocked(apiRequest).mockResolvedValue(
      reportBody({ reportedAt: '2026-10-01T05:12:00Z', updatedAt: '2026-10-01T05:12:00Z' }),
    )
    expect((await putCurrentReport('11680640', { kind: 'none' })).firstSubmission).toBe(true)

    vi.mocked(apiRequest).mockResolvedValue(
      reportBody({ reportedAt: '2026-10-01T05:12:00Z', updatedAt: '2026-10-01T05:12:01Z' }),
    )
    expect((await putCurrentReport('11680640', { kind: 'none' })).firstSubmission).toBe(false)
  })

  it('읽을 수 없는 주 · 수정 시각이면 ReportResponseError 다', async () => {
    vi.mocked(apiRequest).mockResolvedValue(reportBody({ isoWeek: '2026-40' }))
    await expect(putCurrentReport('11680640', { kind: 'none' })).rejects.toBeInstanceOf(
      ReportResponseError,
    )
    vi.mocked(apiRequest).mockResolvedValue(reportBody({ updatedAt: 'later' }))
    await expect(putCurrentReport('11680640', { kind: 'none' })).rejects.toBeInstanceOf(
      ReportResponseError,
    )
  })

  it('실패는 그대로 거부한다', async () => {
    const failure = new Error('거절')
    vi.mocked(apiRequest).mockRejectedValue(failure)
    await expect(putCurrentReport('11680640', { kind: 'none' })).rejects.toBe(failure)
  })
})

describe('fetchCurrentReport', () => {
  it('GET 이고, 아직 보내지 않았으면(dataBody null) null 이다', async () => {
    vi.mocked(apiRequest).mockResolvedValue(null)
    await expect(fetchCurrentReport()).resolves.toBeNull()
    expect(apiRequest).toHaveBeenCalledWith(CURRENT_REPORT_PATH)
  })

  it('응답의 증상군을 화면 증상으로 옮긴다', async () => {
    vi.mocked(apiRequest).mockResolvedValue(reportBody({ symptomGroups: [ENTERIC] }))
    await expect(fetchCurrentReport()).resolves.toEqual({
      report: {
        answer: { kind: 'symptom', symptoms: ['gastrointestinal'] },
        reportedLabel: '10월 1일',
      },
      isoWeek: '2026-W40',
      firstSubmission: true,
    })
  })

  it('모르는 증상군 코드는 버리지 않고 거부한다 — 증상 보고가 증상 없음 · 다른 증상으로 보이지 않게', async () => {
    vi.mocked(apiRequest).mockResolvedValue(
      reportBody({ symptomGroups: [{ code: 'SKIN', name: '피부' }] }),
    )
    await expect(fetchCurrentReport()).rejects.toBeInstanceOf(ReportResponseError)

    vi.mocked(apiRequest).mockResolvedValue(
      reportBody({ symptomGroups: [RESPIRATORY, { code: 'toString' }] }),
    )
    await expect(fetchCurrentReport()).rejects.toBeInstanceOf(ReportResponseError)
  })
})

describe('deleteCurrentReport', () => {
  it('DELETE /api/v1/reports/current 다', async () => {
    vi.mocked(apiRequest).mockResolvedValue(null)
    await deleteCurrentReport()
    expect(apiRequest).toHaveBeenCalledWith(CURRENT_REPORT_PATH, { method: 'DELETE' })
  })
})
