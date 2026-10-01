import { parseIsoWeek } from '@/lib/iso-week'

/** S07 동네 안내 주소 앞부분 (`/notice/[region]/[week]`, docs/design/SCREENS.md) */
const NOTICE_BASE = '/notice'

/** 행정동 코드. SGIS 읍면동 코드 8자리다 (`features/region/types.ts`, 백엔드 `district.code`) */
const REGION_CODE = /^\d{8}$/

/**
 * 동네 안내 주소. `region` 은 행정동 코드, `isoWeek` 는 기준 주(`YYYY-Www`)다.
 * `search` 는 덧붙일 쿼리다(앞 `?` 없이, 예: 목 상태 `mock=published`).
 */
export function noticePath(region: string, isoWeek: string, search?: string): string {
  const path = `${NOTICE_BASE}/${encodeURIComponent(region)}/${encodeURIComponent(isoWeek)}`
  return search ? `${path}?${search}` : path
}

/**
 * 주소의 `[region]` · `[week]` 를 읽는다. 행정동 코드가 8자리 숫자가 아니거나 주가 ISO 주 모양이 아니면(그 해에 없는 주 포함) null 이다 —
 * 부르는 쪽은 없는 화면(404)으로 처리한다. 모양만 본다 — 있는 동네인지는 안내를 불러올 때 안다.
 */
export function parseNoticeRoute(
  region: string,
  week: string,
): { regionCode: string; isoWeek: string } | null {
  if (!REGION_CODE.test(region) || !parseIsoWeek(week)) return null
  return { regionCode: region, isoWeek: week }
}
