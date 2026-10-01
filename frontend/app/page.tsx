import { HomeScreen } from '@/features/home/home-screen'
import { pickHomeMock } from '@/features/home/mock'

/**
 * S03 홈. API 연동 전이라 목 데이터로 그린다.
 *
 * `?mock=normal|slight|high|insufficient` 로 상태를 고른다. 기본은 `insufficient`(자료 부족)다 —
 * 실제 자료가 없는 지금 수치를 지어내 보이지 않는다. API 연동 이슈에서 목 데이터를 걷어낸다.
 */
export default async function HomePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { mock } = await searchParams
  return <HomeScreen week={pickHomeMock(mock)} />
}
