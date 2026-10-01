/**
 * ISO 주 (`YYYY-Www`, 월요일 시작). 백엔드 집계 · 예방 안내의 `iso_week` 와 같은 모양이다
 * (`backend/docs/entity-design.md` — KST 기준, 문자열 정렬이 시간 순). 질병관리청 주차(일요일 시작)와 섞지 않는다.
 *
 * 날짜는 시각 없는 달력 날짜라 UTC 자정으로 계산하고 시간대로 바꾸지 않는다.
 */
export type IsoWeek = { year: number; week: number }

const ISO_WEEK = /^(\d{4})-W(\d{2})$/
const MIN_YEAR = 2000

/** 그 해의 ISO 주 수. 1월 1일이 목요일이거나, 윤년이고 1월 1일이 수요일이면 53주다 */
function weeksInYear(year: number): number {
  const jan1 = new Date(Date.UTC(year, 0, 1)).getUTCDay()
  const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
  return jan1 === 4 || (leap && jan1 === 3) ? 53 : 52
}

/** `YYYY-Www` 를 연도 · 주로. 모양이 다르거나 그 해에 없는 주(00 · 54 · 53주가 없는 해의 53)이거나 2000년 전이면 null 이다 */
export function parseIsoWeek(value: string): IsoWeek | null {
  const match = ISO_WEEK.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const week = Number(match[2])
  // 2000년 미만은 받지 않는다 — 서비스 자료가 없고, Date.UTC 는 0~99년을 1900년대로 읽는다
  if (year < MIN_YEAR || week < 1 || week > weeksInYear(year)) return null
  return { year, week }
}

function addDays(date: Date, days: number): Date {
  const next = new Date(date)
  next.setUTCDate(date.getUTCDate() + days)
  return next
}

/** 그 주의 월요일 (UTC 자정). 1월 4일이 든 주가 1주다 */
export function isoWeekMonday({ year, week }: IsoWeek): Date {
  const jan4 = new Date(Date.UTC(year, 0, 4))
  // 일요일(0)을 7로 보아 월요일이 1인 요일 번호
  const weekday = jan4.getUTCDay() || 7
  return addDays(jan4, 1 - weekday + (week - 1) * 7)
}

/**
 * 집계 기간 (예: `11월 17일~23일`, 달이 바뀌면 `10월 27일~11월 2일`). 연도는 적지 않는다.
 * 읽을 수 없는 주면 null 이다.
 */
export function formatIsoWeekRange(value: string): string | null {
  const parsed = parseIsoWeek(value)
  if (!parsed) return null
  const start = isoWeekMonday(parsed)
  const end = addDays(start, 6)
  const startLabel = `${start.getUTCMonth() + 1}월 ${start.getUTCDate()}일`
  const endLabel =
    end.getUTCMonth() === start.getUTCMonth()
      ? `${end.getUTCDate()}일`
      : `${end.getUTCMonth() + 1}월 ${end.getUTCDate()}일`
  return `${startLabel}~${endLabel}`
}

/**
 * 몇 월 몇째 주 (예: `11월 3주`). **그 주의 목요일이 든 달**의 몇째 주로 센다 — ISO 주가 해를 정하는 방식과 같아
 * 한 주가 두 달에 걸쳐도 한 달에만 속한다(10월 27일~11월 2일은 목요일이 10월 30일이라 `10월 5주`).
 * 읽을 수 없는 주면 null 이다.
 */
export function formatIsoWeekOfMonth(value: string): string | null {
  const parsed = parseIsoWeek(value)
  if (!parsed) return null
  const thursday = addDays(isoWeekMonday(parsed), 3)
  return `${thursday.getUTCMonth() + 1}월 ${Math.ceil(thursday.getUTCDate() / 7)}주`
}
