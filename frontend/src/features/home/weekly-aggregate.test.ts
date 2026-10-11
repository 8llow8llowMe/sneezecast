import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import {
  type AggregateWeekResponse,
  currentReportLabels,
  type DistrictAggregateResponse,
  fetchHomeWeekly,
  toHomeWeekly,
} from './weekly-aggregate'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

const request = vi.mocked(apiRequest)

const WEEK: AggregateWeekResponse = {
  isoWeek: '2025-W47',
  finalized: false,
  publicThreshold: 100,
  slightDeltaPp: 3,
  highDeltaPp: 8,
}

const code = (value: string) => ({ code: value, name: value, description: value })

const ADVISORY = {
  isoWeek: '2025-W46',
  title: '호흡기 증상 보고가 늘었어요',
  publishedAt: '2025-11-17T15:30:00Z',
}

/** 수치가 있는 동네(많이 늘었어요). 호흡기는 전주 대비 변화가 있고 장관은 없다(전주 자료 부족) */
const HIGH: DistrictAggregateResponse = {
  code: '11680640',
  name: '역삼1동',
  sigungu: '서울특별시 강남구',
  active: true,
  level: code('HIGH'),
  participantCount: 128,
  calculatedAt: '2025-11-20T00:00:00Z',
  symptomaticCount: 23,
  symptomRate: 18,
  baselineRate: 9.4,
  groups: [
    { group: code('RESPIRATORY'), rate: 14.1, trend: code('HIGH') },
    { group: code('ENTERIC'), rate: 3.9 },
  ],
  publishedAdvisory: ADVISORY,
}

/** 자료 부족 — 서버가 수치 키를 뺀다 */
const INSUFFICIENT: DistrictAggregateResponse = {
  code: '11680640',
  name: '역삼1동',
  sigungu: '서울특별시 강남구',
  active: true,
  level: code('INSUFFICIENT'),
  insufficientReason: code('LOW_SAMPLE'),
  participantCount: 41,
  calculatedAt: '2025-11-19T00:00:00Z',
}

/** 2025-11-20 10:00 KST */
const NOW = new Date('2025-11-20T01:00:00Z')

afterEach(() => {
  vi.clearAllMocks()
})

describe('toHomeWeekly — 수치가 있는 동네', () => {
  it('단계 · 비율 · 기준선 · 증상 보고 수 · 판정 기준값 · 공개 기준을 옮긴다', () => {
    const week = toHomeWeekly(WEEK, HIGH, NOW)

    expect(week).toMatchObject({
      regionName: '역삼1동',
      status: 'high',
      summary: '증상 보고가 지난 4주보다 많이 늘었어요',
      participants: 128,
      publicThreshold: 100,
      symptomRate: 18,
      baselineRate: 9.4,
      symptomReports: 23,
      thresholds: { slightDeltaPp: 3, highDeltaPp: 8 },
    })
  })

  it('주차 · 집계 기간 · 보고 기간을 응답 주로 만들고, 같은 한국 날짜에 계산했으면 `오늘` 이다', () => {
    const week = toHomeWeekly(WEEK, HIGH, NOW)

    expect(week.weekLabel).toBe('11월 3주')
    expect(week.weekRangeLabel).toBe('11월 17일~23일')
    expect(week.reportPeriodLabel).toBe('11월 17일(월)~23일(일)')
    // 2025-11-20T00:00Z 는 한국 시각 11월 20일 09:00
    expect(week.updatedLabel).toBe('오늘 09:00 갱신')
    expect(toHomeWeekly(WEEK, HIGH, new Date('2025-11-21T01:00:00Z')).updatedLabel).toBe(
      '11월 20일 09:00 갱신',
    )
  })

  it('증상군을 화면 키로 옮기고, 변화가 없으면 키를 두지 않으며, 주별 비율(series)은 지어내지 않는다', () => {
    const week = toHomeWeekly(WEEK, HIGH, NOW)
    if (week.status === 'insufficient') throw new Error('수치가 있는 주여야 한다')

    expect(week.groups).toEqual([
      { key: 'respiratory', rate: 14.1, trend: 'high' },
      { key: 'gastrointestinal', rate: 3.9 },
    ])
    expect(week.groups.every((group) => group.series === undefined)).toBe(true)
  })

  it('모르는 증상군 · 변화 코드는 그 줄 · 그 글자만 뺀다', () => {
    const week = toHomeWeekly(
      WEEK,
      {
        ...HIGH,
        groups: [
          { group: code('RESPIRATORY'), rate: 14.1, trend: code('SKYROCKET') },
          { group: code('SKIN'), rate: 1 },
        ],
      },
      NOW,
    )
    if (week.status === 'insufficient') throw new Error('수치가 있는 주여야 한다')

    expect(week.groups).toEqual([{ key: 'respiratory', rate: 14.1 }])
  })

  it('발행 안내는 한국 날짜 발행일 · 제목으로 옮기고, 목록 · 근거 · 동네 안내 주소(#256 전)는 비운다', () => {
    const week = toHomeWeekly(WEEK, HIGH, NOW)

    // 2025-11-17T15:30Z 는 한국 시각 11월 18일 00:30
    expect(week.notice).toEqual({
      publishedLabel: '11월 18일 발행',
      title: '호흡기 증상 보고가 늘었어요',
    })
  })

  it('공식 정보 요약은 응답에 없어 null 이다', () => {
    expect(toHomeWeekly(WEEK, HIGH, NOW).official).toBeNull()
  })

  it('수치 단계인데 수치 키가 없으면 던진다 — 지어낸 값으로 채우지 않는다', () => {
    const missing = { ...HIGH }
    delete missing.symptomRate
    expect(() => toHomeWeekly(WEEK, missing, NOW)).toThrow()
  })

  it('모르는 단계 · 읽을 수 없는 주면 던진다', () => {
    expect(() => toHomeWeekly(WEEK, { ...HIGH, level: code('UNKNOWN') }, NOW)).toThrow()
    expect(() => toHomeWeekly({ ...WEEK, isoWeek: '2025-W53' }, HIGH, NOW)).toThrow()
  })
})

describe('toHomeWeekly — 자료 부족', () => {
  it('수치 필드가 없는 갈래이고 참여 인원 · 공개 기준만 있다', () => {
    const week = toHomeWeekly(WEEK, INSUFFICIENT, NOW)

    expect(week.status).toBe('insufficient')
    expect(week.participants).toBe(41)
    expect(week.publicThreshold).toBe(100)
    for (const key of ['symptomRate', 'baselineRate', 'symptomReports', 'groups', 'thresholds']) {
      expect(week).not.toHaveProperty(key)
    }
  })

  it('이유(참여 부족 · 급변 · 기준선 부족)와 무관하게 같은 자료 부족이다', () => {
    const weeks = ['LOW_SAMPLE', 'UNSTABLE', 'NO_BASELINE'].map((reason) =>
      toHomeWeekly(
        WEEK,
        { ...INSUFFICIENT, participantCount: 150, insufficientReason: code(reason) },
        NOW,
      ),
    )
    expect(new Set(weeks.map((week) => JSON.stringify(week))).size).toBe(1)
    expect(weeks[0]?.summary).toBe('아직 변화를 판단할 자료가 부족해요')
  })

  it('보고가 한 번도 없던 동(집계 행 없음, 갱신 시각 없음)은 `아직 집계 전` 이다', () => {
    const noRow = { ...INSUFFICIENT }
    delete noRow.calculatedAt
    const week = toHomeWeekly(WEEK, { ...noRow, participantCount: 0 }, NOW)

    expect(week.updatedLabel).toBe('아직 집계 전')
    expect(week.participants).toBe(0)
  })

  it('자료 부족 칸에도 발행 안내가 붙는다', () => {
    const week = toHomeWeekly(WEEK, { ...INSUFFICIENT, publishedAdvisory: ADVISORY }, NOW)
    expect(week.notice?.title).toBe('호흡기 증상 보고가 늘었어요')
  })
})

describe('fetchHomeWeekly', () => {
  it('공개 API 라 Authorization 없이(`auth: false`) 그 동네의 현재 주 집계를 읽는다', async () => {
    request.mockResolvedValueOnce({ ...WEEK, district: HIGH })

    const week = await fetchHomeWeekly('11680640', () => NOW)

    expect(request).toHaveBeenCalledWith('/api/v1/districts/11680640/weekly-aggregate', {
      auth: false,
    })
    expect(week?.status).toBe('high')
  })

  it('폐지된 코드(active false)는 null 이다 — 둘러보기 동네는 지금 있는 동네만 받는다', async () => {
    request.mockResolvedValueOnce({ ...WEEK, district: { ...HIGH, active: false } })
    await expect(fetchHomeWeekly('11680640', () => NOW)).resolves.toBeNull()
  })

  it('형식이 틀린 코드는 요청 없이 null 이다', async () => {
    await expect(fetchHomeWeekly('1168064', () => NOW)).resolves.toBeNull()
    expect(request).not.toHaveBeenCalled()
  })

  it('없는 코드(AGGREGATE_001)는 null 이다', async () => {
    request.mockRejectedValueOnce(
      new ApiError({ status: 404, code: 'AGGREGATE_001', message: '없는 행정동' }),
    )
    await expect(fetchHomeWeekly('11680640', () => NOW)).resolves.toBeNull()
  })

  it('그 밖의 실패 · 계약과 다른 응답은 거부한다', async () => {
    request.mockRejectedValueOnce(
      new ApiError({ status: 0, code: 'UNAVAILABLE', message: '일시 장애' }),
    )
    await expect(fetchHomeWeekly('11680640', () => NOW)).rejects.toBeInstanceOf(ApiError)

    request.mockResolvedValueOnce({ ...WEEK, district: { ...HIGH, level: code('UNKNOWN') } })
    await expect(fetchHomeWeekly('11680640', () => NOW)).rejects.toThrow()
  })
})

describe('currentReportLabels', () => {
  it('지금 한국 날짜의 ISO 주로 집계 기간 · 보고 기간을 적는다', () => {
    // 2025-11-23T15:30Z 는 한국 시각 11월 24일(월) 00:30 — 다음 주다
    expect(currentReportLabels(new Date('2025-11-23T15:30:00Z'))).toEqual({
      weekRangeLabel: '11월 24일~30일',
      reportPeriodLabel: '11월 24일(월)~30일(일)',
    })
  })
})
