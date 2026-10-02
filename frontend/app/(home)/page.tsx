import { HomeScreen } from '@/features/home/home-screen'
import { pickHomeMock } from '@/features/home/mock'
import { districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

/**
 * S03 홈. API 연동 전이라 목 데이터로 그린다.
 *
 * `?mock=normal|slight|high|insufficient` 로 상태를 고른다. 기본은 `insufficient`(자료 부족)다 —
 * 실제 자료가 없는 지금 수치를 지어내 보이지 않는다. API 연동 이슈에서 목 데이터를 걷어낸다.
 *
 * `?region=<행정동 코드>` 는 둘러보기(S02-1)에서 고른 동네다. 찾으면 그 이름을 보이고,
 * 없거나 모르는 코드면 목 데이터의 이름을 그대로 둔다. home 이 region 을 모르게 여기서 맞춘다
 * (값 해석은 `districtFromParam` 이 맡고 단위 테스트가 있다). 찾은 코드는 홈이 메뉴 링크에 붙여 잃지 않게 한다.
 *
 * 회원 · 동의 상태(목)는 홈이 `useMockAuth` 로 읽는다. QA 용 `?mock-auth=guest|member|member-no-consent` 덮어쓰기도
 * 그 훅이 주소에서 읽는다 — 동의한 뒤 홈이 주소에서 지우면 바로 따라가야 해서 서버 props 로 넘기지 않는다.
 *
 * 받은 시각(`receivedAt`)은 이 요청에서 홈 자료를 다 구한 시각이다. 오프라인 띠(State-offline)가 "○월 ○일 00:00에 받은 정보예요" 로
 * 보인다. 화면 자료(RSC 결과)와 함께 실려 가므로 라우터가 그 결과를 다시 쓸 때도 시각이 자료와 어긋나지 않는다.
 * API 연동 때는 응답을 받은 시각으로 바꾼다.
 */
export default async function HomePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { mock, region } = await searchParams
  const week = pickHomeMock(mock)
  const source = await readServerDataSource()
  const district = await districtFromParam(region, source)
  const receivedAt = new Date().toISOString()
  return (
    <HomeScreen
      week={district ? { ...week, regionName: district.name } : week}
      regionCode={district?.code ?? null}
      receivedAt={receivedAt}
    />
  )
}
