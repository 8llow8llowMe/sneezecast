import type { Metadata } from 'next'

import { KakaoCallbackScreen } from '@/features/auth/kakao-callback-screen'

export const metadata: Metadata = { title: '카카오 로그인' }

/**
 * 카카오 콜백 (#167, 백엔드 `KAKAO_REDIRECT_URI`). 카카오가 붙인 `code` · `state` 는 서버에서 읽지 않는다 — 화면이 브라우저에서 읽자마자
 * 주소에서 지우고 로그인 요청 본문으로만 보낸다(`kakao-callback-screen.tsx`). 그래서 `searchParams` 를 받지 않는 정적 페이지다
 */
export default function KakaoCallbackPage() {
  return <KakaoCallbackScreen />
}
