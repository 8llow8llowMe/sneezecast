import { type ReactNode, Suspense } from 'react'
import type { Metadata, Viewport } from 'next'

import { SessionExpiryWatcher } from '@/features/auth/session-expiry-watcher'
import { APP_DESCRIPTION, APP_NAME, APP_SHORT_NAME } from '@/lib/app-info'
import { clientEnv } from '@/lib/env.client'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import { THEME_COLOR } from '@/styles/theme-color'

// Pretendard Variable — unicode-range 로 분할된 dynamic subset.
// 브라우저가 페이지에 실제 등장한 글자 범위만 내려받아 통짜 variable(약 2MB)보다 초기 로드가 작다.
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import './globals.css'

export const metadata: Metadata = {
  // 없으면 Next 가 og:image 등을 상대 경로로 내보내 공유 크롤러가 읽지 못한다
  metadataBase: new URL(clientEnv.siteUrl),
  title: {
    default: APP_NAME,
    template: `%s · ${APP_NAME}`,
  },
  description: APP_DESCRIPTION,
  applicationName: APP_NAME,
  // iOS 홈 화면 앱 표시. 매니페스트(app/manifest.ts)는 Next 가 <link rel="manifest"> 로 넣고,
  // 아이콘은 app/icon.tsx(파비콘) · app/apple-icon.tsx(apple-touch-icon) 가 넣는다.
  appleWebApp: {
    capable: true,
    // 아이콘 아래 이름. 매니페스트 short_name 과 같다(src/lib/app-info.ts)
    title: APP_SHORT_NAME,
    // 흰 바탕에 검은 글자 상태 표시줄. black-translucent 는 화면이 상태 표시줄 밑까지 올라가는데
    // 머리줄이 위쪽 안전 영역을 비우지 않고, 흰 글자가 흰 화면에 묻힌다
    statusBarStyle: 'default',
  },
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
        {/* 앱 안 이동 경로 기록. 모든 화면의 "뒤로" 가 이 기록으로 판단한다(docs/conventions.md "화면의 뒤로").
            앱 안 replace 는 모두 이 기록을 거쳐야 해 로그인 만료 감시도 안에 둔다 */}
        <NavTrailProvider>
          {children}
          {/* 로그인 만료를 받아 로그인 화면으로 보낸다. 주소 쿼리(목 재현 입력)를 읽어 Suspense 로 감싼다 */}
          <Suspense fallback={null}>
            <SessionExpiryWatcher />
          </Suspense>
        </NavTrailProvider>
      </body>
    </html>
  )
}
