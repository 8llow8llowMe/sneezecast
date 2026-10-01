import { HomeScreen } from '@/features/home/home-screen'
import { pickHomeMock } from '@/features/home/mock'
import { districtFromParam } from '@/features/region/region-param'

/**
 * S03 홈. API 연동 전이라 목 데이터로 그린다.
 *
 * `?mock=normal|slight|high|insufficient` 로 상태를 고른다. 기본은 `insufficient`(자료 부족)다 —
 * 실제 자료가 없는 지금 수치를 지어내 보이지 않는다. API 연동 이슈에서 목 데이터를 걷어낸다.
 *
 * `?region=<행정동 코드>` 는 둘러보기(S02-1)에서 고른 동네다. 찾으면 그 이름을 보이고,
 * 없거나 모르는 코드면 목 데이터의 이름을 그대로 둔다. home 이 region 을 모르게 여기서 맞춘다
 * (값 해석은 `districtFromParam` 이 맡고 단위 테스트가 있다).
 */
export default async function HomePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { mock, region } = await searchParams
  const week = pickHomeMock(mock)
  const district = await districtFromParam(region)
  return <HomeScreen week={district ? { ...week, regionName: district.name } : week} />
}
