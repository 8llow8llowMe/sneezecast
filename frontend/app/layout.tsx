import { type ReactNode, Suspense } from 'react'
import type { Metadata, Viewport } from 'next'

import { DataSourceToggle } from '@/components/data-source-toggle'
import { SessionBootstrap } from '@/features/auth/session-bootstrap'
import { SessionExpiryWatcher } from '@/features/auth/session-expiry-watcher'
import { APP_DESCRIPTION, APP_NAME, APP_SHORT_NAME } from '@/lib/app-info'
import { isDataSourceSwitchable } from '@/lib/data-source'
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
          {/* 세션 저장소를 켜고 실데이터면 새로고침 전 세션을 되살린다(docs/conventions.md "세션 저장소") */}
          <SessionBootstrap />
          {children}
          {/* 로그인 만료를 받아 로그인 화면으로 보낸다. 주소 쿼리(목 재현 입력)를 읽어 Suspense 로 감싼다 */}
          <Suspense fallback={null}>
            <SessionExpiryWatcher />
          </Suspense>
        </NavTrailProvider>
        {/* 데이터 출처 토글(개발용). 전환할 수 있는 사이트(dev 웹 · 로컬)에서만 그린다. body 마지막에 두어 Tab 첫 포커스가 되지 않게 한다.
            z-index 를 주지 않는다 — z-index 가 있는 시트 · 버튼 묶음 · 가림막은 DOM 순서와 무관하게 위에 온다(components/data-source-toggle.tsx) */}
        {isDataSourceSwitchable() ? <DataSourceToggle /> : null}
      </body>
    </html>
  )
}
