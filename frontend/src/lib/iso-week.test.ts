import { describe, expect, it } from 'vitest'

import {
  formatIsoWeekOfMonth,
  formatIsoWeekRange,
  isoWeekMonday,
  kstIsoWeek,
  parseIsoWeek,
} from './iso-week'

describe('parseIsoWeek', () => {
  it('YYYY-Www 를 연도 · 주로 읽는다', () => {
    expect(parseIsoWeek('2025-W47')).toEqual({ year: 2025, week: 47 })
    expect(parseIsoWeek('2026-W01')).toEqual({ year: 2026, week: 1 })
  })

  it('53주는 그 해에 53주가 있을 때만 받는다', () => {
    // 2026 은 1월 1일이 목요일이라 53주가 있다. 2025 는 52주까지다
    expect(parseIsoWeek('2026-W53')).toEqual({ year: 2026, week: 53 })
    expect(parseIsoWeek('2025-W53')).toBeNull()
    // 윤년이고 1월 1일이 수요일이면 53주가 있다 (2020)
    expect(parseIsoWeek('2020-W53')).toEqual({ year: 2020, week: 53 })
  })

  it('2000년 전 주는 받지 않는다 (Date.UTC 가 0~99년을 1900년대로 읽는다)', () => {
    expect(parseIsoWeek('0099-W10')).toBeNull()
    expect(parseIsoWeek('1999-W52')).toBeNull()
    expect(parseIsoWeek('2000-W01')).toEqual({ year: 2000, week: 1 })
  })

  it('모양이 다르거나 없는 주면 null 이다', () => {
    for (const value of [
      '',
      '2025-W00',
      '2025-W54',
      '2025-W7',
      '2025W47',
      '2025-w47',
      '2025-47',
      ' 2025-W47',
      '2025-W47 ',
      '2025-W47x',
      '0000-W01',
    ]) {
      expect(parseIsoWeek(value), value).toBeNull()
    }
  })
})

describe('isoWeekMonday', () => {
  it('그 주의 월요일(UTC 자정)이다', () => {
    expect(isoWeekMonday({ year: 2025, week: 47 }).toISOString()).toBe('2025-11-17T00:00:00.000Z')
    // 1주의 월요일이 지난해 12월일 수 있다
    expect(isoWeekMonday({ year: 2025, week: 1 }).toISOString()).toBe('2024-12-30T00:00:00.000Z')
    expect(isoWeekMonday({ year: 2026, week: 53 }).toISOString()).toBe('2026-12-28T00:00:00.000Z')
  })
})

describe('formatIsoWeekRange', () => {
  it('월요일~일요일을 월 · 일로 적는다 (Guide-published-T "11월 17일~23일")', () => {
    expect(formatIsoWeekRange('2025-W47')).toBe('11월 17일~23일')
  })

  it('달이 바뀌면 뒤쪽에도 월을 적는다', () => {
    expect(formatIsoWeekRange('2025-W49')).toBe('12월 1일~7일')
    expect(formatIsoWeekRange('2025-W44')).toBe('10월 27일~11월 2일')
    expect(formatIsoWeekRange('2025-W01')).toBe('12월 30일~1월 5일')
  })

  it('읽을 수 없는 주면 null 이다', () => {
    expect(formatIsoWeekRange('2025-W53')).toBeNull()
    expect(formatIsoWeekRange('latest')).toBeNull()
  })
})

describe('formatIsoWeekOfMonth', () => {
  it('목요일이 든 달의 몇째 주로 적는다 (Guide-published "11월 3주")', () => {
    expect(formatIsoWeekOfMonth('2025-W47')).toBe('11월 3주')
    // 10월 27일~11월 2일 — 목요일(10월 30일)이 10월이라 10월 5주다
    expect(formatIsoWeekOfMonth('2025-W44')).toBe('10월 5주')
    expect(formatIsoWeekOfMonth('2025-W45')).toBe('11월 1주')
    // 2024년 12월 30일 ~ 2025년 1월 5일 — 목요일이 1월 2일이라 1월 1주다
    expect(formatIsoWeekOfMonth('2025-W01')).toBe('1월 1주')
  })

  it('읽을 수 없는 주면 null 이다', () => {
    expect(formatIsoWeekOfMonth('2025-W00')).toBeNull()
  })
})

describe('kstIsoWeek — KST 기준 지금 ISO 주', () => {
  it('일요일 23:59 KST 까지는 그 주, 월요일 00:00 KST 부터 다음 주다', () => {
    expect(kstIsoWeek(new Date('2026-10-04T14:59:59Z'))).toBe('2026-W40') // 일 23:59:59 KST
    expect(kstIsoWeek(new Date('2026-10-04T15:00:00Z'))).toBe('2026-W41') // 월 00:00 KST
    // UTC 로는 아직 일요일이지만 KST 는 월요일이다
    expect(kstIsoWeek(new Date('2026-10-04T20:00:00Z'))).toBe('2026-W41')
  })

  it('53주가 있는 해와 연말 · 연초 경계', () => {
    // 2026년 1월 1일이 목요일이라 53주까지 있다
    expect(kstIsoWeek(new Date('2026-12-31T03:00:00Z'))).toBe('2026-W53')
    expect(kstIsoWeek(new Date('2027-01-03T14:59:59Z'))).toBe('2026-W53') // 일 23:59:59 KST
    expect(kstIsoWeek(new Date('2027-01-03T15:00:00Z'))).toBe('2027-W01') // 월 00:00 KST
    // 12월 말 월요일이 다음 해 1주인 해
    expect(kstIsoWeek(new Date('2024-12-29T15:00:00Z'))).toBe('2025-W01') // 2024-12-30(월) KST
    expect(kstIsoWeek(new Date('2024-12-29T14:59:59Z'))).toBe('2024-W52')
  })

  it('브라우저 시간대와 무관하다', () => {
    const originalTz = process.env.TZ
    process.env.TZ = 'America/Los_Angeles'
    try {
      expect(kstIsoWeek(new Date('2026-10-04T15:00:00Z'))).toBe('2026-W41')
    } finally {
      if (originalTz === undefined) delete process.env.TZ
      else process.env.TZ = originalTz
    }
  })
})
