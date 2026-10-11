import { beforeEach, describe, expect, it, vi } from 'vitest'

import { HOME_MOCKS } from '@/features/home/mock'
import { MAP_MOCK_KEYS, pickMapMock } from '@/features/map/mock'
import type * as regionClient from '@/features/region/region-client'
import { findDistrict } from '@/features/region/region-client'
import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import { NOTICE_EXAMPLE_DISTRICT, NOTICE_EXAMPLE_WEEK, NOTICE_MOCKS, pickNoticeMock } from './mock'
import { getRegionNotice } from './notice-client'
import { noticePath } from './paths'

vi.mock('@/features/region/region-client', async (importOriginal) => {
  const actual = await importOriginal<typeof regionClient>()
  return { ...actual, findDistrict: vi.fn(actual.findDistrict) }
})

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

const request = vi.mocked(apiRequest)

/** 안내 API · 집계 API 응답(값이면 성공, Error 면 실패) */
function reply(advisory: unknown, aggregate: unknown) {
  request.mockImplementation((path) => {
    const value = path === '/api/v1/advisories' ? advisory : aggregate
    return value instanceof Error ? Promise.reject(value) : Promise.resolve(value)
  })
}

beforeEach(() => {
  vi.mocked(findDistrict).mockClear()
  request.mockReset()
})

describe('pickNoticeMock', () => {
  it('?mock= 값으로 상태를 고른다', () => {
    expect(pickNoticeMock('published')).toBe(NOTICE_MOCKS.published)
    expect(pickNoticeMock(['corrected', 'none'])).toBe(NOTICE_MOCKS.corrected)
    expect(pickNoticeMock('retracted')).toBe(NOTICE_MOCKS.retracted)
  })

  it('철회 목은 발행된 안내가 없고 철회 표시만 있다 — 철회 · 정정 사유는 백엔드가 공개 응답에 내리지 않아 목에도 없다', () => {
    expect(NOTICE_MOCKS.retracted.notice).toBeNull()
    expect(NOTICE_MOCKS.retracted.retraction).toEqual({ retractedOn: '2025-11-20' })
    expect(NOTICE_MOCKS.corrected.notice?.corrections).toEqual([{ correctedOn: '2025-11-19' }])
    for (const key of ['published', 'corrected', 'none', 'insufficient'] as const) {
      expect(NOTICE_MOCKS[key].retraction, key).toBeNull()
    }
  })

  it('없거나 모르는 값이면 자료 부족의 안내 없음이다', () => {
    expect(pickNoticeMock(undefined)).toBe(NOTICE_MOCKS.insufficient)
    expect(pickNoticeMock('high')).toBe(NOTICE_MOCKS.insufficient)
    expect(pickNoticeMock('constructor')).toBe(NOTICE_MOCKS.insufficient)
  })

  it('발행된 안내는 수치가 있는 주에만 있다', () => {
    for (const mock of Object.values(NOTICE_MOCKS)) {
      if (mock.notice) expect(mock.stats.status).not.toBe('insufficient')
    }
  })
})

describe('getRegionNotice', () => {
  it('아는 동네면 주소의 동네 · 주에 목 상태를 붙인다', async () => {
    await expect(getRegionNotice('11680640', '2025-W47', 'mock', 'published')).resolves.toEqual({
      regionCode: '11680640',
      regionName: '역삼1동',
      isoWeek: '2025-W47',
      officialHref: '/official',
      ...NOTICE_MOCKS.published,
    })
  })

  it('안내 예시 동네를 안다', async () => {
    const notice = await getRegionNotice(NOTICE_EXAMPLE_DISTRICT.code, '2025-W47', 'mock')
    expect(notice?.regionName).toBe('○○동')
    expect(notice?.notice).toBeNull()
    expect(notice?.stats.status).toBe('insufficient')
  })

  it('운영자 목의 예시 동네(○○1동~○○5동)도 안다 — 발행 이력의 사용자 화면에서 보기', async () => {
    const notice = await getRegionNotice('99990105', '2025-W46', 'mock', 'published')
    expect(notice?.regionName).toBe('○○5동')
    expect(notice?.notice).not.toBeNull()
  })

  it('모르는 동네면 null 이다', async () => {
    await expect(getRegionNotice('00000000', '2025-W47', 'mock', 'published')).resolves.toBeNull()
  })

  it('API 출처면 안내 API · 같은 주 집계로 그리고 ?mock= 을 듣지 않는다 — 동네 이름은 집계에서 읽는다', async () => {
    reply(null, {
      isoWeek: '2025-W47',
      finalized: true,
      publicThreshold: 100,
      slightDeltaPp: 3,
      highDeltaPp: 8,
      district: {
        code: '11440660',
        name: '서교동',
        sigungu: '서울특별시 마포구',
        active: true,
        level: { code: 'INSUFFICIENT', name: '자료 부족', description: '' },
        participantCount: 12,
      },
    })

    const notice = await getRegionNotice('11440660', '2025-W47', 'api', 'published')

    expect(notice).toEqual({
      regionCode: '11440660',
      regionName: '서교동',
      isoWeek: '2025-W47',
      officialHref: '/official',
      notice: null,
      retraction: null,
      stats: { status: 'insufficient', participants: 12, publicThreshold: 100 },
    })
    expect(request).toHaveBeenCalledWith('/api/v1/advisories', {
      auth: false,
      query: { districtCode: '11440660', week: '2025-W47' },
    })
    expect(findDistrict).not.toHaveBeenCalled()
  })

  it('API 출처에서 모르는 동네(안내 예시 동네 같은 지어낸 코드 포함)면 null 이다', async () => {
    reply(
      new ApiError({ status: 404, code: 'ADVISORY_008', message: '없는 행정동' }),
      new ApiError({ status: 404, code: 'AGGREGATE_001', message: '없는 행정동' }),
    )
    await expect(
      getRegionNotice(NOTICE_EXAMPLE_DISTRICT.code, '2025-W47', 'api'),
    ).resolves.toBeNull()
  })

  it('API 출처에서 요청이 실패하면 거부한다 — 페이지 오류 경계가 받는다', async () => {
    const error = unavailableError('network', 0)
    reply(error, error)
    await expect(getRegionNotice('11440660', '2025-W47', 'api')).rejects.toBe(error)
  })
})

describe('홈 목의 안내 링크', () => {
  it('안내 예시 동네 · 주의 발행 목으로 간다 (홈 목은 notice 를 import 하지 않아 문자열이다)', () => {
    expect(HOME_MOCKS.high.notice?.href).toBe(
      noticePath(NOTICE_EXAMPLE_DISTRICT.code, NOTICE_EXAMPLE_WEEK, 'mock=published'),
    )
  })

  it('안내를 요약해 보이는 곳(홈 안내 섹션 · 지도의 이 동네 안내 보기)은 발행 중인 안내만 연다 — 철회된 안내는 요약하지 않는다', () => {
    const summaries = [
      ...Object.values(HOME_MOCKS).map((week) => week.notice),
      ...MAP_MOCK_KEYS.flatMap((key) =>
        pickMapMock(key, null).districts.map((district) => district.week.notice),
      ),
    ].filter((notice) => notice !== null)
    expect(summaries.length).toBeGreaterThan(0)
    for (const { href } of summaries) {
      const opened = pickNoticeMock(
        new URL(href, 'http://localhost').searchParams.get('mock') ?? undefined,
      )
      expect(opened.notice, href).not.toBeNull()
      expect(opened.retraction, href).toBeNull()
    }
  })
})
