import type { Metadata } from 'next'
import { notFound } from 'next/navigation'

import { getRegionNotice } from '@/features/notice/notice-client'
import { NoticeScreen } from '@/features/notice/notice-screen'
import { parseNoticeRoute } from '@/features/notice/paths'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: '동네 안내' }

/**
 * S07 동네 안내. API 연동 전이라 목 데이터로 그린다.
 *
 * - `[region]` 은 행정동 코드(SGIS 8자리), `[week]` 는 기준 주(ISO 주 `YYYY-Www`, 백엔드 `iso_week`)다.
 *   모양이 틀리거나(그 해에 없는 주 포함) 모르는 동네면 없는 화면(404)이다 — 안내 없음으로 보이면 그런 동네 · 주가 있는 것처럼 읽힌다.
 * - `?mock=published|corrected|retracted|none|insufficient` 로 상태를 고른다. 기본은 `insufficient`(자료 부족의 안내 없음)다 —
 *   실제 자료가 없는 지금 수치 · 안내를 지어내 보이지 않는다. API 연동 이슈에서 목 데이터를 걷어낸다.
 * - `?region=<행정동 코드>` 는 둘러보기 동네다(경로의 `[region]` 과 다름). 아는 코드면 메뉴 · 탭바 · 뒤로 주소에 남긴다(공식 정보와 같다).
 */
export default async function NoticePage({
  params,
  searchParams,
}: {
  params: Promise<{ region: string; week: string }>
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region, week } = await params
  const route = parseNoticeRoute(region, week)
  if (!route) notFound()
  const { mock, region: browseRegion } = await searchParams
  const source = await readServerDataSource()
  const data = await getRegionNotice(route.regionCode, route.isoWeek, source, mock)
  if (!data) notFound()
  const browse = await districtFromParam(browseRegion, source)
  return <NoticeScreen data={data} regionCode={browse?.code ?? null} />
}
