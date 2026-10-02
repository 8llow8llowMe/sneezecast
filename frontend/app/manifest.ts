import type { MetadataRoute } from 'next'

import { MANIFEST_ICON_BASE, MANIFEST_ICONS } from '@/features/pwa/app-icon'
import { APP_DESCRIPTION, APP_NAME, APP_SHORT_NAME } from '@/lib/app-info'
import { THEME_COLOR } from '@/styles/theme-color'

/**
 * 웹 앱 매니페스트(`/manifest.webmanifest`). Next 가 모든 화면 <head> 에 <link rel="manifest"> 를 넣는다.
 *
 * - `display: 'standalone'`: iOS 16.4 이상은 홈 화면 앱에서만 웹 푸시를 받고, `detectPushSupport` 도
 *   `display-mode: standalone` 으로 홈 화면 앱인지 본다(src/lib/push-support.ts).
 * - `orientation` 은 두지 않는다. 태블릿 · 데스크톱 시안(834 · 1440)이 가로 화면이라 세로로 고정하지 않는다.
 * - 색은 화면 바탕(`color.bg`)과 같다. 주소창 색(`viewport.themeColor`)과 설치한 앱의 첫 화면이 화면과 이어진다.
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    id: '/',
    name: APP_NAME,
    short_name: APP_SHORT_NAME,
    description: APP_DESCRIPTION,
    lang: 'ko',
    start_url: '/',
    scope: '/',
    display: 'standalone',
    background_color: THEME_COLOR,
    theme_color: THEME_COLOR,
    icons: MANIFEST_ICONS.map((icon) => ({
      src: `${MANIFEST_ICON_BASE}/${icon.file}`,
      sizes: `${icon.size}x${icon.size}`,
      type: 'image/png',
      purpose: icon.purpose,
    })),
  }
}
