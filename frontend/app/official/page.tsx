import type { Metadata } from 'next'

import { pickHomeMock } from '@/features/home/mock'
import { getLatestOfficial } from '@/features/official/official-client'
import { OfficialScreen } from '@/features/official/official-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

export const metadata: Metadata = { title: '질병관리청 발표' }

/**
 * S08 공식 정보. 출처(`readServerDataSource()`)에 따라 공식 정보 API(`GET /api/v1/official/latest`, 인증 불필요)나 목 데이터로 그린다
 * (`getLatestOfficial`). 공식 정보는 세션과 무관해 서버에서 읽는다 — 실패하면 거부가 루트 오류 경계(`app/error.tsx`)로 가고
 * `다시 시도` 가 서버에서 다시 받는다.
 *
 * `?mock=published|empty` 는 목데이터 출처에서만 상태를 고른다. 기본은 `empty`(받은 자료 없음)다 — 수치를 지어내 보이지 않는다.
 * `?region=<행정동 코드>` 는 홈과 같이 받아 머리줄 동네 이름 · 메뉴 링크에 쓴다(공식 자료는 동네와 무관하다).
 */
export default async function OfficialPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { mock, region } = await searchParams
  const source = await readServerDataSource()
  const [official, district] = await Promise.all([
    getLatestOfficial(source, mock),
    districtFromParam(region, source),
  ])
  return (
    <OfficialScreen
      official={official}
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
