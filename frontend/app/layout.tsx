import type { Metadata, Viewport } from 'next'
import type { ReactNode } from 'react'

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
}

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="ko">
      <body className="min-h-dvh antialiased">{children}</body>
    </html>
  )
}
