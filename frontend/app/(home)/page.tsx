import { HomeScreen } from '@/features/home/home-screen'
import { pickHomeMock } from '@/features/home/mock'
import { districtCodeFromParam, districtFromParam } from '@/features/region/region-param'
import { readServerDataSource } from '@/lib/data-source.server'

/**
 * S03 홈. 동네 현황의 출처는 데이터 출처(`readServerDataSource`)로 정한다(#252).
 *
 * - **실데이터**: 목 주를 넘기지 않는다(`week={null}`). 화면이 보이는 동네(둘러보기 동네, 없으면 회원의 내 동네)의 행정동 주간 집계
 *   (`GET /api/v1/districts/{code}/weekly-aggregate`)를 읽는다 — 내 동네는 서버가 모르는 회원 정보(세션은 화면에만 있다)라
 *   서버에서 읽을 수 없다. 읽는 중 · 실패는 화면 안 안내(불러오는 중 · 빨강 상자 + 다시 시도)다.
 *   `?region=` 은 모양(숫자 8자리)만 보고 넘긴다 — 이름 · 있는 동네인지는 집계 응답(`district.name` · 404 · 폐지면 동네 없음)이 정한다.
 *   행정동 API 로 따로 확인하면 그 요청의 일시 장애가 "모르는 동네" 로 삼켜져 홈이 다시 시도 없이 내 동네로 바뀐다.
 * - **목데이터**: `?mock=normal|slight|high|insufficient` 로 상태를 고른다. 기본은 `insufficient`(자료 부족)다 — 수치를 지어내 보이지 않는다.
 *
 * `?region=<행정동 코드>` 는 둘러보기(S02-1)에서 고른 동네다. 찾으면 그 이름을 보이고,
 * 없거나 모르는 코드면 목 데이터의 이름을 그대로 둔다. home 이 region 을 모르게 여기서 맞춘다
 * (값 해석은 `districtFromParam` 이 맡고 단위 테스트가 있다). 찾은 코드는 홈이 메뉴 링크에 붙여 잃지 않게 한다.
 *
 * 회원 · 동의 상태(목)는 홈이 `useMockAuth` 로 읽는다. QA 용 `?mock-auth=guest|member|member-no-consent` 덮어쓰기도
 * 그 훅이 주소에서 읽는다 — 동의한 뒤 홈이 주소에서 지우면 바로 따라가야 해서 서버 props 로 넘기지 않는다.
 *
 * 받은 시각(`receivedAt`)은 목데이터에서 이 요청에서 홈 자료를 다 구한 시각이다. 오프라인 띠(State-offline)가 "○월 ○일 00:00에 받은 정보예요" 로
 * 보인다. 화면 자료(RSC 결과)와 함께 실려 가므로 라우터가 그 결과를 다시 쓸 때도 시각이 자료와 어긋나지 않는다.
 * 실데이터는 화면이 집계 응답을 받은 시각을 쓴다(여기서는 넘기지 않는다).
 */
export default async function HomePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { mock, region } = await searchParams
  const source = await readServerDataSource()
  if (source === 'api') {
    return (
      <HomeScreen
        week={null}
        regionName={pickHomeMock(undefined).regionName}
        regionCode={districtCodeFromParam(region)}
      />
    )
  }
  const district = await districtFromParam(region, source)
  const regionName = district?.name ?? pickHomeMock(undefined).regionName
  const week = pickHomeMock(mock)
  const receivedAt = new Date().toISOString()
  return (
    <HomeScreen
      week={{ ...week, regionName }}
      regionName={regionName}
      regionCode={district?.code ?? null}
      receivedAt={receivedAt}
    />
  )
}
