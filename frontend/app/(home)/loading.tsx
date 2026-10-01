import { LoadingState } from '@/components/loading-state'

/**
 * 홈을 불러오는 동안 (State-loading). 다른 화면에서 홈으로 올 때 보인다.
 *
 * 로딩 경계는 루트가 아니라 주요 메뉴 화면에만 둔다. 루트에 두면 모든 경로가 Suspense 안에서 그려져
 * `notFound()` 가 404 대신 200 으로 나간다(Next 는 스트리밍을 시작한 뒤 상태 코드를 바꾸지 못한다).
 * 홈을 괄호 폴더 `(home)` 에 둔 것도 이 경계를 홈에만 걸기 위해서다 — 주소는 그대로 `/` 다.
 */
export default function HomeLoading() {
  return <LoadingState nav="home" />
}
