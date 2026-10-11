import { LoadingState } from '@/components/loading-state'

/**
 * 공식 정보를 불러오는 동안 (State-loading 의 모양, 지금 메뉴는 공식 정보 화면과 같은 홈). 서버 페이지가 공식 정보 API 를
 * 기다리는 동안 앞 화면 대신 보인다. 경계를 이 경로에만 두는 이유는 `app/(home)/loading.tsx` 와 같다.
 */
export default function OfficialLoading() {
  return <LoadingState nav="home" />
}
