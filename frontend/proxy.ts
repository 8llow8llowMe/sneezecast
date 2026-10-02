import type { NextRequest } from 'next/server'

import { firstVisit } from '@/features/onboarding/first-visit'

/**
 * Next 16 Proxy(옛 미들웨어). 화면을 그리기 전에 돈다.
 *
 * 지금은 처음 온 사람을 시작 화면으로 보내는 일 하나만 한다(`features/onboarding/first-visit.ts`, docs/design/SCREENS.md "첫 진입").
 * 로직은 그 모듈에 두고 단위 테스트한다. 이 파일은 경로 고르기(`config.matcher`)와 연결만 맡는다.
 */
export function proxy(request: NextRequest) {
  return firstVisit(request)
}

export const config = {
  // 화면 요청만 받는다. 빌드 산출물(`_next/`) · API · 아이콘 라우트(`icon` · `apple-icon` · `app-icons/`) ·
  // 점이 든 경로(public 파일 · `manifest.webmanifest` 등)는 뺀다. 값은 빌드 때 정적으로 읽으므로 리터럴로 둔다
  matcher: ['/((?!_next/|api/|icon|apple-icon|app-icons/|.*\\.).*)'],
}
