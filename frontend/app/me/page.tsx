import { pickHomeMock } from '@/features/home/mock'
import { MeScreen } from '@/features/me/me-screen'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

/**
 * S10 내 정보. 회원 상태 · 프로필(목)은 화면이 `useMockAuth` · `useMockProfile` 로 읽는다 — QA 용
 * `?mock-auth=guest|member|member-no-consent` · `?mock-provider=email|kakao` 덮어쓰기도 그 훅이 주소에서 읽는다.
 * 확인 대화상자는 `?confirm=logout|consent-withdraw|withdraw` 로 열린다.
 *
 * `?region=<행정동 코드>` 는 홈처럼 둘러보기에서 고른 동네다. 찾으면 그 이름을 보이고 메뉴 링크에 붙인다.
 * 없으면 홈 목 데이터와 같은 동네 이름(예시 값)을 쓴다 — 연동 때 회원은 `GET /me` 의 내 동네로 바꾼다.
 */
export default async function MePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { region } = await searchParams
  const source = await readServerDataSource()
  const district = await districtFromParam(region, source)
  return (
    <MeScreen
      regionName={district?.name ?? pickHomeMock(undefined).regionName}
      regionCode={district?.code ?? null}
    />
  )
}
