import type { Metadata } from 'next'
import { notFound } from 'next/navigation'

import { getRegionNotice } from '@/features/notice/notice-client'
import { NoticeScreen } from '@/features/notice/notice-screen'
import { parseNoticeRoute } from '@/features/notice/paths'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'
import { kstIsoWeek } from '@/lib/iso-week'

export const metadata: Metadata = { title: '동네 안내' }

/**
 * S07 동네 안내. 출처(`readServerDataSource()`)에 따라 동네 안내 API + 같은 주 행정동 주간 집계(인증 불필요, #256)나 목 데이터로
 * 그린다(`getRegionNotice`). 안내는 세션과 무관해 서버에서 읽는다 — 실패하면 거부가 루트 오류 경계(`app/error.tsx`)로 가고
 * `다시 시도` 가 서버에서 다시 받는다.
 *
 * - `[region]` 은 행정동 코드(SGIS 8자리), `[week]` 는 기준 주(ISO 주 `YYYY-Www`, 백엔드 `iso_week`)다.
 *   모양이 틀리거나(그 해에 없는 주 포함) 모르는 동네 · 조회할 수 없는 주(미래 · 51주보다 이전, 실데이터)면 없는 화면(404)이다 —
 *   안내 없음으로 보이면 그런 동네 · 주가 있는 것처럼 읽힌다.
 * - `?mock=published|corrected|retracted|none|insufficient` 는 목데이터 출처에서만 상태를 고른다. 기본은 `insufficient`(자료 부족의
 *   안내 없음)다 — 수치 · 안내를 지어내 보이지 않는다. 실데이터는 `?mock=` 을 듣지 않는다.
 * - 제목의 주(`이번 주` · `지난주` · `11월 3주`)는 화면을 연 때의 KST 주(서버 시각)와 견줘 정한다(`noticeWeekName`).
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
  // 안내 주를 `이번 주` · `지난주` 로 부를 기준. 서버 시각으로 정해 넘겨 하이드레이션 그림이 서버 그림과 같게 한다
  return (
    <NoticeScreen
      data={data}
      currentWeek={kstIsoWeek(new Date())}
      regionCode={browse?.code ?? null}
    />
  )
}
