import { type ReactNode, Suspense } from 'react'
import type { Metadata, Viewport } from 'next'

import { SessionExpiryWatcher } from '@/features/auth/session-expiry-watcher'
import { clientEnv } from '@/lib/env.client'
import { THEME_COLOR } from '@/styles/theme-color'

// Pretendard Variable — unicode-range 로 분할된 dynamic subset.
// 브라우저가 페이지에 실제 등장한 글자 범위만 내려받아 통짜 variable(약 2MB)보다 초기 로드가 작다.
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import './globals.css'

export const metadata: Metadata = {
  // 없으면 Next 가 og:image 등을 상대 경로로 내보내 공유 크롤러가 읽지 못한다
  metadataBase: new URL(clientEnv.siteUrl),
  title: {
    default: '우리동네체온계',
    template: '%s · 우리동네체온계',
  },
  description: '이웃의 주간 건강 보고로 우리 동네 증상 변화를 확인해요.',
  applicationName: '우리동네체온계',
}

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: THEME_COLOR,
  // 홈 화면에 추가한 PWA 가 화면 끝까지 그리게 한다. 홈 인디케이터 영역은 pb-safe · pb-sheet 가 비운다
  viewportFit: 'cover',
}

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="ko">
      <body className="min-h-dvh antialiased">
        {children}
        {/* 로그인 만료를 받아 로그인 화면으로 보낸다. 주소 쿼리(목 재현 입력)를 읽어 Suspense 로 감싼다 */}
        <Suspense fallback={null}>
          <SessionExpiryWatcher />
        </Suspense>
      </body>
    </html>
  )
}
