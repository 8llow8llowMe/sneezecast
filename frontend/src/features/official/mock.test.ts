import { describe, expect, it } from 'vitest'

import { OFFICIAL_MOCKS, pickOfficialMock } from './mock'

describe('OFFICIAL_MOCKS', () => {
  it('예시 발표는 실데이터와 같은 매핑을 거친 표본감시 세 묶음이다 — 단계 · 요약 · 발표일이 없다', () => {
    const { published } = OFFICIAL_MOCKS
    expect(published.status).toBe('published')
    if (published.status !== 'published') return
    expect(published.programs.map((program) => program.code)).toEqual([
      'INFLUENZA_ILI',
      'ARI',
      'ENTERIC',
    ])
    expect(published).not.toHaveProperty('stage')
    expect(published).not.toHaveProperty('summary')
  })
})

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
