import { beforeEach, describe, expect, it, vi } from 'vitest'

import { HOME_MOCKS } from '@/features/home/mock'
import type * as regionClient from '@/features/region/region-client'
import { findDistrict } from '@/features/region/region-client'
import { unavailableError } from '@/lib/api/api-error'

import { NOTICE_EXAMPLE_DISTRICT, NOTICE_EXAMPLE_WEEK, NOTICE_MOCKS, pickNoticeMock } from './mock'
import { getRegionNotice } from './notice-client'
import { noticePath } from './paths'

vi.mock('@/features/region/region-client', async (importOriginal) => {
  const actual = await importOriginal<typeof regionClient>()
  return { ...actual, findDistrict: vi.fn(actual.findDistrict) }
})

beforeEach(() => {
  vi.mocked(findDistrict).mockClear()
})

describe('pickNoticeMock', () => {
  it('?mock= 값으로 상태를 고른다', () => {
    expect(pickNoticeMock('published')).toBe(NOTICE_MOCKS.published)
    expect(pickNoticeMock(['corrected', 'none'])).toBe(NOTICE_MOCKS.corrected)
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

  it('API 출처면 동네를 행정동 API 로 확인한다', async () => {
    vi.mocked(findDistrict).mockResolvedValueOnce({
      code: '11440660',
      name: '서교동',
      sigungu: '서울특별시 마포구',
      active: true,
    })
    const notice = await getRegionNotice('11440660', '2025-W47', 'api')
    expect(findDistrict).toHaveBeenCalledWith('11440660', 'api')
    expect(notice?.regionName).toBe('서교동')
  })

  it('API 출처에서 폐지된 동네면 null 이다', async () => {
    vi.mocked(findDistrict).mockResolvedValueOnce({
      code: '11680999',
      name: '옛동',
      sigungu: '서울특별시 강남구',
      active: false,
    })
    await expect(getRegionNotice('11680999', '2025-W47', 'api')).resolves.toBeNull()
  })

  it('API 출처에서는 안내 예시 동네(지어낸 코드)도 행정동 API 로 확인한다', async () => {
    vi.mocked(findDistrict).mockResolvedValueOnce(null)
    await expect(
      getRegionNotice(NOTICE_EXAMPLE_DISTRICT.code, '2025-W47', 'api'),
    ).resolves.toBeNull()
    expect(findDistrict).toHaveBeenCalledWith(NOTICE_EXAMPLE_DISTRICT.code, 'api')
  })

  it('동네 확인이 실패하면 거부한다 — 페이지 오류 경계가 받는다', async () => {
    const error = unavailableError('network', 0)
    vi.mocked(findDistrict).mockRejectedValueOnce(error)
    await expect(getRegionNotice('11440660', '2025-W47', 'api')).rejects.toBe(error)
  })
})

describe('홈 목의 안내 링크', () => {
  it('안내 예시 동네 · 주의 발행 목으로 간다 (홈 목은 notice 를 import 하지 않아 문자열이다)', () => {
    expect(HOME_MOCKS.high.notice?.href).toBe(
      noticePath(NOTICE_EXAMPLE_DISTRICT.code, NOTICE_EXAMPLE_WEEK, 'mock=published'),
    )
  })
})
