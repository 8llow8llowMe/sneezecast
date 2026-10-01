import { LoadingState } from '@/components/loading-state'

/**
 * 내 정보 · 계정 화면을 불러오는 동안 (State-loading 의 모양에 현재 메뉴만 내 정보로). 경계를 둔 이유는 `app/(home)/loading.tsx` 와 같다.
 */
export default function MeLoading() {
  return <LoadingState nav="me" />
}
