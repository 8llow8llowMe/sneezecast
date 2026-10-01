import { describe, expect, it } from 'vitest'

import { OFFICIAL_MOCKS, pickOfficialMock } from './mock'

describe('pickOfficialMock', () => {
  it('값이 없으면 받은 발표 없음이다 — 발표를 지어내 보이지 않는다', () => {
    expect(pickOfficialMock(undefined)).toBe(OFFICIAL_MOCKS.empty)
  })

  it('모르는 값이면 받은 발표 없음이다', () => {
    expect(pickOfficialMock('high')).toBe(OFFICIAL_MOCKS.empty)
    expect(pickOfficialMock('toString')).toBe(OFFICIAL_MOCKS.empty)
  })

  it('아는 값이면 그 상태다. 여러 개면 첫 값을 쓴다', () => {
    expect(pickOfficialMock('published')).toBe(OFFICIAL_MOCKS.published)
    expect(pickOfficialMock(['published', 'empty'])).toBe(OFFICIAL_MOCKS.published)
  })
})
