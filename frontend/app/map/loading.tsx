import { LoadingState } from '@/components/loading-state'

/**
 * 지도를 불러오는 동안 (State-loading 의 모양에 지금 메뉴만 지도로). 경계를 둔 이유는 `app/(home)/loading.tsx` 와 같다 —
 * 루트가 아니라 이 경로에만 두어 다른 경로의 `notFound()` 가 404 로 나간다.
 */
export default function MapLoading() {
  return <LoadingState nav="map" />
}
