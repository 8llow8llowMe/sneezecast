import { isValidElement } from 'react'

import { afterEach, describe, expect, it, vi } from 'vitest'

import { NOTICE_EXAMPLE_DISTRICT, NOTICE_MOCKS } from '@/features/notice/mock'
import type { RegionNotice } from '@/features/notice/types'
import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import type { DataSource } from '@/lib/data-source'

import NoticePage from './page'

// 테스트에는 요청이 없어 `cookies()` 를 부를 수 없다 — 출처는 테스트마다 정한다
const dataSource = vi.hoisted((): { value: DataSource } => ({ value: 'mock' }))
vi.mock('@/lib/data-source.server', () => ({
  readServerDataSource: () => Promise.resolve(dataSource.value),
}))
vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))
// 없는 화면은 Next 가 던지는 표시로 알 수 있게 바꾼다
vi.mock('next/navigation', () => ({
  notFound: () => {
    throw new Error('NOT_FOUND')
  },
}))

const request = vi.mocked(apiRequest)

const CODE = '11680640'
const WEEK = '2025-W46'

const code = (value: string) => ({ code: value, name: value, description: value })

const AGGREGATE = {
  isoWeek: WEEK,
  finalized: true,
  publicThreshold: 100,
  slightDeltaPp: 3,
  highDeltaPp: 8,
  district: {
    code: CODE,
    name: '역삼1동',
    sigungu: '서울특별시 강남구',
    active: true,
    level: code('HIGH'),
    participantCount: 130,
    symptomaticCount: 24,
    symptomRate: 18.5,
    baselineRate: 9.2,
    groups: [{ group: code('RESPIRATORY'), rate: 14.6, trend: code('HIGH') }],
  },
}

const PUBLISHED = {
  districtCode: CODE,
  isoWeek: WEEK,
  advisory: {
    title: '발열·기침 보고가 지난 4주보다 많이 늘었어요',
    body: '손을 자주 씻어 주세요.',
    publishedAt: '2025-11-17T15:30:00Z',
    citation: {
      level: code('HIGH'),
      participantCount: 128,
      symptomaticCount: 23,
      symptomRate: 18,
      groups: [{ group: code('RESPIRATORY'), count: 18, rate: 14.1 }],
    },
    corrections: [],
  },
  retraction: null,
}

/** 안내 API · 집계 API 응답(값이면 성공, Error 면 실패) */
function reply(advisory: unknown, aggregate: unknown) {
  request.mockImplementation((path) => {
    const value = path === '/api/v1/advisories' ? advisory : aggregate
    return value instanceof Error ? Promise.reject(value) : Promise.resolve(value)
  })
}

type Props = { data: RegionNotice; currentWeek: string; regionCode: string | null }

function open(region: string, week: string, search: Record<string, string> = {}) {
  return NoticePage({
    params: Promise.resolve({ region, week }),
    searchParams: Promise.resolve(search),
  })
}

async function renderPage(
  region: string,
  week: string,
  search: Record<string, string> = {},
): Promise<Props> {
  const element = await open(region, week, search)
  expect(isValidElement(element)).toBe(true)
  return element.props as Props
}

describe('NoticePage', () => {
  afterEach(() => {
    dataSource.value = 'mock'
    vi.clearAllMocks()
  })

  it('목데이터는 ?mock= 으로 고르고 안내 · 집계를 요청하지 않는다', async () => {
    const { data } = await renderPage(NOTICE_EXAMPLE_DISTRICT.code, WEEK, { mock: 'published' })

    expect(data.notice).toBe(NOTICE_MOCKS.published.notice)
    expect(request).not.toHaveBeenCalled()
  })

  it('안내 주를 부를 기준으로 서버 시각의 KST 주를 넘긴다', async () => {
    vi.useFakeTimers({ now: new Date('2025-11-23T15:30:00Z'), toFake: ['Date'] })
    try {
      // 한국 시각 11월 24일(월) 00:30 — 다음 주다
      expect((await renderPage(NOTICE_EXAMPLE_DISTRICT.code, WEEK)).currentWeek).toBe('2025-W48')
    } finally {
      vi.useRealTimers()
    }
  })

  it('실데이터는 안내 API · 같은 주 집계로 그리고 ?mock= 을 듣지 않는다', async () => {
    dataSource.value = 'api'
    reply(PUBLISHED, AGGREGATE)

    const { data } = await renderPage(CODE, WEEK, { mock: 'insufficient' })

    expect(data).toMatchObject({
      regionCode: CODE,
      regionName: '역삼1동',
      isoWeek: WEEK,
      notice: { title: '발열·기침 보고가 지난 4주보다 많이 늘었어요', publishedOn: '2025-11-18' },
      retraction: null,
      stats: { status: 'high', participants: 128, symptomRate: 18, baselineRate: 9.2 },
    })
  })

  it('실데이터에서 모르는 동네 · 조회할 수 없는 주면 없는 화면이다', async () => {
    dataSource.value = 'api'
    reply(
      new ApiError({ status: 400, code: 'ADVISORY_009', message: '조회할 수 없는 주' }),
      new ApiError({ status: 400, code: 'AGGREGATE_002', message: '조회할 수 없는 주' }),
    )
    await expect(open(CODE, '2024-W01')).rejects.toThrow('NOT_FOUND')

    reply(
      new ApiError({ status: 404, code: 'ADVISORY_008', message: '없는 행정동' }),
      new ApiError({ status: 404, code: 'AGGREGATE_001', message: '없는 행정동' }),
    )
    await expect(open('99999999', WEEK)).rejects.toThrow('NOT_FOUND')
  })

  it('실데이터를 받지 못하면 거부해 오류 경계로 보낸다 — 예시 값을 대신 그리지 않는다', async () => {
    dataSource.value = 'api'
    const failure = unavailableError('timeout', 0)
    reply(PUBLISHED, failure)

    await expect(open(CODE, WEEK)).rejects.toBe(failure)
  })

  it('주소 모양이 틀리면 요청 없이 없는 화면이다', async () => {
    dataSource.value = 'api'
    await expect(open(CODE, '2025-W54')).rejects.toThrow('NOT_FOUND')
    expect(request).not.toHaveBeenCalled()
  })
})
