import { afterEach, describe, expect, it, vi } from 'vitest'

import type {
  DistrictAggregateResponse,
  DistrictWeeklyAggregateResponse,
} from '@/features/home/weekly-aggregate'
import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import {
  ADVISORIES_PATH,
  fetchRegionNotice,
  kstDateOf,
  type RegionAdvisoryResponse,
  toRegionNoticeBody,
} from './advisory-api'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

const request = vi.mocked(apiRequest)

const CODE = '11680640'
const WEEK = '2025-W46'
const AGGREGATE_PATH = `/api/v1/districts/${CODE}/weekly-aggregate`

const code = (value: string) => ({ code: value, name: value, description: value })

/** 같은 주 집계 — 인용값과 일부러 다르게 둬 어느 값이 어디서 왔는지 가린다 */
const HIGH: DistrictAggregateResponse = {
  code: CODE,
  name: '역삼1동',
  sigungu: '서울특별시 강남구',
  active: true,
  level: code('HIGH'),
  participantCount: 140,
  calculatedAt: '2025-11-16T15:10:00Z',
  symptomaticCount: 27,
  symptomRate: 19.3,
  baselineRate: 9.4,
  groups: [
    { group: code('RESPIRATORY'), rate: 15.7, trend: code('HIGH') },
    { group: code('ENTERIC'), rate: 4.3 },
  ],
}

const INSUFFICIENT: DistrictAggregateResponse = {
  code: CODE,
  name: '역삼1동',
  sigungu: '서울특별시 강남구',
  active: true,
  level: code('INSUFFICIENT'),
  insufficientReason: code('LOW_SAMPLE'),
  participantCount: 41,
}

function aggregate(district: DistrictAggregateResponse): DistrictWeeklyAggregateResponse {
  return {
    isoWeek: WEEK,
    finalized: true,
    publicThreshold: 100,
    slightDeltaPp: 3,
    highDeltaPp: 8,
    district,
  }
}

const PUBLISHED: RegionAdvisoryResponse = {
  districtCode: CODE,
  isoWeek: WEEK,
  advisory: {
    title: '발열·기침 보고가 지난 4주보다 많이 늘었어요',
    body: '손을 자주 씻어 주세요.\n열이 나면 사람 많은 곳을 피해 주세요.',
    // 한국 시각 11월 18일 00:30 — UTC 날짜(17일)와 다르다
    publishedAt: '2025-11-17T15:30:00Z',
    citation: {
      level: code('HIGH'),
      participantCount: 128,
      symptomaticCount: 23,
      symptomRate: 18,
      groups: [
        { group: code('RESPIRATORY'), count: 18, rate: 14.1 },
        { group: code('ENTERIC'), count: 5, rate: 3.9 },
      ],
    },
    corrections: [{ correctedAt: '2025-11-20T15:00:00Z' }, { correctedAt: '2025-11-18T01:00:00Z' }],
  },
  retraction: null,
}

const RETRACTED: RegionAdvisoryResponse = {
  districtCode: CODE,
  isoWeek: WEEK,
  advisory: null,
  retraction: { retractedAt: '2025-11-19T16:00:00Z' },
}

const NOW = new Date('2025-11-20T01:00:00Z')

afterEach(() => {
  vi.clearAllMocks()
})

describe('kstDateOf', () => {
  it('UTC 시각을 한국 날짜로 바꾸고, 읽을 수 없으면 빈 문자열이다', () => {
    expect(kstDateOf('2025-11-17T15:30:00Z')).toBe('2025-11-18')
    expect(kstDateOf('2025-11-17T14:59:59Z')).toBe('2025-11-17')
    expect(kstDateOf('어제')).toBe('')
  })
})

describe('toRegionNoticeBody — 발행 중', () => {
  it('제목 · 본문 · 발행일 · 정정일(최근 것부터)을 옮기고 정정 사유는 두지 않는다', () => {
    const body = toRegionNoticeBody(PUBLISHED, aggregate(HIGH), WEEK, NOW)

    expect(body.retraction).toBeNull()
    expect(body.notice).toEqual({
      title: '발열·기침 보고가 지난 4주보다 많이 늘었어요',
      publishedOn: '2025-11-18',
      body: '손을 자주 씻어 주세요.\n열이 나면 사람 많은 곳을 피해 주세요.',
      corrections: [{ correctedOn: '2025-11-21' }, { correctedOn: '2025-11-18' }],
    })
  })

  it('수치는 인용값이고 지난 4주 평균 · 증상군 변화만 같은 주 집계에서 읽는다', () => {
    const { stats } = toRegionNoticeBody(PUBLISHED, aggregate(HIGH), WEEK, NOW)

    expect(stats).toEqual({
      status: 'high',
      participants: 128,
      symptomRate: 18,
      baselineRate: 9.4,
      groups: [
        { key: 'respiratory', rate: 14.1, trend: 'high' },
        // 집계에 변화가 없는 증상군(전주 자료 부족)은 변화 없이 그린다
        { key: 'gastrointestinal', rate: 3.9 },
      ],
    })
  })

  it('인용 단계가 집계 단계와 달라도 인용 단계를 보인다 — 운영자가 검토한 값이다', () => {
    const cited = structuredClone(PUBLISHED)
    if (!cited.advisory) throw new Error('발행 중인 안내다')
    cited.advisory.citation.level = code('SLIGHT')
    cited.advisory.citation.groups.push({ group: code('UNKNOWN'), count: 1, rate: 1 })

    const { stats } = toRegionNoticeBody(cited, aggregate(HIGH), WEEK, NOW)

    expect(stats.status).toBe('slight')
    // 모르는 증상군은 그 줄만 뺀다
    expect(stats.status !== 'insufficient' && stats.groups.map((group) => group.key)).toEqual([
      'respiratory',
      'gastrointestinal',
    ])
  })

  it('정정이 없으면 빈 목록이다', () => {
    const plain = structuredClone(PUBLISHED)
    if (!plain.advisory) throw new Error('발행 중인 안내다')
    plain.advisory.corrections = []

    expect(toRegionNoticeBody(plain, aggregate(HIGH), WEEK, NOW).notice?.corrections).toEqual([])
  })

  it('인용 단계가 자료 부족 · 모르는 값이거나 그 주 집계가 자료 부족이면 던진다 — 지어낸 기준선으로 채우지 않는다', () => {
    for (const level of ['INSUFFICIENT', 'UNKNOWN']) {
      const broken = structuredClone(PUBLISHED)
      if (!broken.advisory) throw new Error('발행 중인 안내다')
      broken.advisory.citation.level = code(level)
      expect(() => toRegionNoticeBody(broken, aggregate(HIGH), WEEK, NOW), level).toThrow()
    }
    expect(() => toRegionNoticeBody(PUBLISHED, aggregate(INSUFFICIENT), WEEK, NOW)).toThrow()
  })
})

describe('toRegionNoticeBody — 철회 · 안내 없음', () => {
  it('철회는 철회일(한국 날짜)만 있고 사유 · 내린 안내 내용은 없다. 수치는 그 주 집계다', () => {
    const body = toRegionNoticeBody(RETRACTED, aggregate(HIGH), WEEK, NOW)

    expect(body.notice).toBeNull()
    expect(body.retraction).toEqual({ retractedOn: '2025-11-20' })
    expect(body.stats).toEqual({
      status: 'high',
      participants: 140,
      symptomRate: 19.3,
      baselineRate: 9.4,
      groups: [
        { key: 'respiratory', rate: 15.7, trend: 'high' },
        { key: 'gastrointestinal', rate: 4.3 },
      ],
    })
  })

  it('자료 부족 주의 철회 · 안내 없음은 수치 필드 없이 참여 · 공개 기준만 있다', () => {
    for (const advisory of [RETRACTED, null]) {
      const { stats } = toRegionNoticeBody(advisory, aggregate(INSUFFICIENT), WEEK, NOW)
      expect(stats).toEqual({ status: 'insufficient', participants: 41, publicThreshold: 100 })
    }
  })

  it('안내 없음(dataBody null)은 안내 · 철회 표시가 없다', () => {
    const body = toRegionNoticeBody(null, aggregate(HIGH), WEEK, NOW)
    expect(body.notice).toBeNull()
    expect(body.retraction).toBeNull()
    expect(body.stats.status).toBe('high')
  })

  it('안내 · 철회가 모두 없거나 주가 요청과 다르면 던진다', () => {
    expect(() =>
      toRegionNoticeBody({ ...RETRACTED, retraction: null }, aggregate(HIGH), WEEK, NOW),
    ).toThrow()
    expect(() =>
      toRegionNoticeBody({ ...RETRACTED, isoWeek: '2025-W45' }, aggregate(HIGH), WEEK, NOW),
    ).toThrow()
    expect(() =>
      toRegionNoticeBody(null, { ...aggregate(HIGH), isoWeek: '2025-W45' }, WEEK, NOW),
    ).toThrow()
  })
})

describe('fetchRegionNotice', () => {
  /** 경로마다 다른 응답(값이면 성공, Error 면 실패)을 준다 — 두 요청은 동시에 나간다 */
  function reply(advisory: RegionAdvisoryResponse | null | Error, week: unknown) {
    request.mockImplementation((path) => {
      const value = path === ADVISORIES_PATH ? advisory : week
      return value instanceof Error ? Promise.reject(value) : Promise.resolve(value)
    })
  }

  it('안내 API 와 같은 동네 · 주의 집계를 Authorization 없이 함께 읽고, 동네 이름은 집계에서 읽는다', async () => {
    reply(PUBLISHED, aggregate(HIGH))

    const found = await fetchRegionNotice(CODE, WEEK, () => NOW)

    expect(request).toHaveBeenCalledTimes(2)
    expect(request).toHaveBeenCalledWith(ADVISORIES_PATH, {
      auth: false,
      query: { districtCode: CODE, week: WEEK },
    })
    expect(request).toHaveBeenCalledWith(AGGREGATE_PATH, { auth: false, query: { week: WEEK } })
    expect(found?.regionName).toBe('역삼1동')
    expect(found?.body.notice?.title).toBe('발열·기침 보고가 지난 4주보다 많이 늘었어요')
  })

  it('폐지된 동네도 그 주 안내를 보인다 — 백엔드가 오류로 보지 않는다', async () => {
    reply(PUBLISHED, aggregate({ ...HIGH, active: false }))
    expect((await fetchRegionNotice(CODE, WEEK, () => NOW))?.body.notice).not.toBeNull()
  })

  it('형식이 틀린 코드는 요청 없이 null 이다', async () => {
    await expect(fetchRegionNotice('1168064', WEEK)).resolves.toBeNull()
    expect(request).not.toHaveBeenCalled()
  })

  it.each([
    ['ADVISORY_008', 404],
    ['ADVISORY_009', 400],
    ['ADVISORY_104', 400],
  ])('안내 API 가 그 동네 · 주를 모르면(%s) null 이다 — 없는 화면', async (errorCode, status) => {
    reply(new ApiError({ status, code: errorCode, message: '조회할 수 없다' }), aggregate(HIGH))
    await expect(fetchRegionNotice(CODE, WEEK, () => NOW)).resolves.toBeNull()
  })

  it.each([
    ['AGGREGATE_001', 404],
    ['AGGREGATE_002', 400],
  ])('집계가 그 동네 · 주를 모르면(%s) null 이다', async (errorCode, status) => {
    reply(PUBLISHED, new ApiError({ status, code: errorCode, message: '조회할 수 없다' }))
    await expect(fetchRegionNotice(CODE, WEEK, () => NOW)).resolves.toBeNull()
  })

  it('어느 한쪽이 일시 장애 · 다른 오류면 거부한다 — 페이지 오류 경계가 받는다', async () => {
    const down = unavailableError('network', 0)
    reply(PUBLISHED, down)
    await expect(fetchRegionNotice(CODE, WEEK, () => NOW)).rejects.toBe(down)

    const gateway = new ApiError({ status: 504, code: 'GATEWAY_004', message: '지연' })
    reply(gateway, aggregate(HIGH))
    await expect(fetchRegionNotice(CODE, WEEK, () => NOW)).rejects.toBe(gateway)
  })

  it('계약과 다른 응답은 거부한다', async () => {
    reply(RETRACTED, aggregate({ ...HIGH, level: code('UNKNOWN') }))
    await expect(fetchRegionNotice(CODE, WEEK, () => NOW)).rejects.toThrow()
  })
})
