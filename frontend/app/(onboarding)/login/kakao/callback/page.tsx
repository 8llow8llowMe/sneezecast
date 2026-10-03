import type { Metadata } from 'next'

import { KakaoCallbackScreen } from '@/features/auth/kakao-callback-screen'

export const metadata: Metadata = { title: '카카오 로그인' }

/**
 * 카카오 콜백 (#167, 백엔드 `KAKAO_REDIRECT_URI`). 카카오가 붙인 `code` · `state` 는 서버에서 읽지 않는다 — 화면이 브라우저에서 읽자마자
 * 주소에서 지우고 로그인 요청 본문으로만 보낸다(`kakao-callback-screen.tsx`). 그래서 `searchParams` 를 받지 않는다.
 *
 * 모든 화면처럼 동적 렌더링이라(CSP nonce, #176) 쿼리를 단 채 그리면 Next 가 요청 주소를 응답 HTML(RSC 페이로드)에 싣는다.
 * 그래서 `proxy.ts` 가 카카오가 붙인 쿼리를 fragment 로 옮겨 303 으로 다시 열게 하고(`features/auth/kakao-callback-redirect.ts`),
 * 이 페이지는 늘 쿼리 없이 그려진다. 화면은 hash 에서 값을 읽는다
 */
export default function KakaoCallbackPage() {
  return <KakaoCallbackScreen />
}
