/** 사람 수 · 건수. 천 단위 쉼표를 넣는다 (예: 1,280) */
export function formatCount(value: number): string {
  return value.toLocaleString('ko-KR')
}

// 서비스 지역이 한국이라 기기 시간대와 무관하게 한국 시각으로 보인다
const MONTH_DAY_TIME = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  month: 'numeric',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/**
 * 월 · 일 · 시각 (예: `11월 20일 21:14`, `11월 2일 08:03` — Settings-devices 시안). 한국 시각이다.
 * 읽을 수 없는 값이면 null 이다 — 부르는 쪽은 시각 없이 그린다.
 */
export function formatMonthDayTime(value: string | Date): string | null {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const parts = MONTH_DAY_TIME.formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? ''
  return `${part('month')}월 ${part('day')}일 ${part('hour')}:${part('minute')}`
}

/**
 * 날짜(`YYYY-MM-DD`, 한국 날짜)의 월 · 일 (예: `12월 1일` — Setup-3-reconsent 시행일). 시각이 없는 날짜라 시간대로 바꾸지 않는다.
 * 모양이 다르거나 없는 날짜면 null 이다 — 부르는 쪽은 날짜 없이 그린다.
 */
export function formatMonthDay(date: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date)
  if (!match) return null
  const [, year, month, day] = match.map(Number) as [number, number, number, number]
  const parsed = new Date(Date.UTC(year, month - 1, day))
  if (parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== day) return null
  return `${month}월 ${day}일`
}
