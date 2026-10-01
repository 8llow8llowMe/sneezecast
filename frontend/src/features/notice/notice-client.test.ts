import { describe, expect, it } from 'vitest'

import { HOME_MOCKS } from '@/features/home/mock'

import { NOTICE_EXAMPLE_DISTRICT, NOTICE_EXAMPLE_WEEK, NOTICE_MOCKS, pickNoticeMock } from './mock'
import { getRegionNotice } from './notice-client'
import { noticePath } from './paths'

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
    await expect(getRegionNotice('11680640', '2025-W47', 'published')).resolves.toEqual({
      regionCode: '11680640',
      regionName: '역삼1동',
      isoWeek: '2025-W47',
      officialHref: '/official',
      ...NOTICE_MOCKS.published,
    })
  })

  it('안내 예시 동네를 안다', async () => {
    const notice = await getRegionNotice(NOTICE_EXAMPLE_DISTRICT.code, '2025-W47')
    expect(notice?.regionName).toBe('○○동')
    expect(notice?.notice).toBeNull()
    expect(notice?.stats.status).toBe('insufficient')
  })

  it('모르는 동네면 null 이다', async () => {
    await expect(getRegionNotice('00000000', '2025-W47', 'published')).resolves.toBeNull()
  })
})

describe('홈 목의 안내 링크', () => {
  it('안내 예시 동네 · 주의 발행 목으로 간다 (홈 목은 notice 를 import 하지 않아 문자열이다)', () => {
    expect(HOME_MOCKS.high.notice?.href).toBe(
      noticePath(NOTICE_EXAMPLE_DISTRICT.code, NOTICE_EXAMPLE_WEEK, 'mock=published'),
    )
  })
})
