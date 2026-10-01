import type { Metadata } from 'next'

import { InstallScreen } from '@/features/install/install-screen'
import { districtFromParam } from '@/features/region/region-param'

export const metadata: Metadata = { title: '홈 화면에 추가' }

/**
 * S12 홈 화면 추가 안내. 내 정보의 알림 미지원 안내(Settings-nopush)에서 온다.
 *
 * `?region=<행정동 코드>` 는 홈처럼 둘러보기에서 고른 동네다. 아는 코드만 넘겨, 주소로 바로 들어와 닫을 때 홈 주소에 남긴다.
 * 기기 판별과 QA 용 덮어쓰기(`?mock-auth=` · `?mock-push=`)는 화면이 브라우저에서 읽는다.
 */
export default async function InstallPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const district = await districtFromParam(region)
  return <InstallScreen regionCode={district?.code ?? null} />
}
