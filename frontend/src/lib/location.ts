/**
 * 앱 밖 주소로 문서를 옮긴다(`window.location.assign`). 카카오 인가 화면처럼 Next 라우터가 다룰 수 없는 다른 오리진으로 갈 때만 쓴다 —
 * 앱 안 이동은 `router.push` · `useNavTrail().replace` 다.
 *
 * 주소를 믿을 수 있는지(오리진 · 경로)는 부르는 쪽이 먼저 확인한다(`features/auth/kakao-client.ts` 의 `isKakaoAuthorizeUrl`).
 * 테스트는 이 모듈을 `vi.mock` 으로 바꾼다 — jsdom 의 `location.assign` 은 바꿔 끼울 수 없고 이동도 하지 않는다.
 */
export function assignLocation(url: string): void {
  window.location.assign(url)
}
