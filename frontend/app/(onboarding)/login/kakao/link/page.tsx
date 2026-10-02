import type { Metadata } from 'next'

import { KakaoLinkScreen } from '@/features/auth/kakao-link-screen'

export const metadata: Metadata = { title: '카카오 계정 연결' }

/**
 * 카카오 계정 연결 확인 (#167, 시안 Login-kakao-exists 자리). 가린 이메일은 첫 진입 Provider 메모리로만 받는다 — 주소에 싣지 않는다
 */
export default function KakaoLinkPage() {
  return <KakaoLinkScreen />
}
