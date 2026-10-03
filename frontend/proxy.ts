import type { NextRequest } from 'next/server'

import { kakaoCallbackRedirect } from '@/features/auth/kakao-callback-redirect'
import { firstVisit } from '@/features/onboarding/first-visit'
import { clientEnv } from '@/lib/env.client'
import {
  contentSecurityPolicy,
  type CspEnv,
  withContentSecurityPolicy,
} from '@/lib/security/content-security-policy'

/**
 * Next 16 Proxy(옛 미들웨어). 화면을 그리기 전에 돈다.
 *
 * 하는 일 세 가지. 로직은 각 모듈에 두고 단위 테스트한다. 이 파일은 경로 고르기(`config.matcher`)와 연결만 맡는다. 인증 가드는 없다.
 * - 화면 요청마다 nonce 를 새로 만들어 CSP 를 요청 · 응답 헤더에 싣는다(`lib/security/content-security-policy.ts`, docs/conventions.md "보안 헤더 · CSP").
 * - 카카오 콜백의 쿼리(인가 코드)를 fragment 로 옮겨 303 으로 보낸다(`features/auth/kakao-callback-redirect.ts`). 첫 진입보다 먼저 본다 —
 *   콜백은 원래 시작 화면으로 보내지 않는 화면이고, 방문 표시는 리다이렉트를 받은 문서 요청에서 심는다.
 * - 처음 온 사람을 시작 화면으로 보낸다(`features/onboarding/first-visit.ts`, docs/design/SCREENS.md "첫 진입").
 */
const CSP_ENV: CspEnv = {
  dev: process.env.NODE_ENV === 'development',
  apiBaseUrl: clientEnv.apiBaseUrl,
  siteUrl: clientEnv.siteUrl,
}

export function proxy(request: NextRequest) {
  return withContentSecurityPolicy(
    request,
    contentSecurityPolicy(CSP_ENV),
    (init) => kakaoCallbackRedirect(request) ?? firstVisit(request, init),
  )
}

export const config = {
  // 화면 요청만 받는다. 빌드 산출물(`_next/`) · API · 아이콘 라우트(`icon` · `apple-icon` · `app-icons/`) ·
  // 점이 든 경로(public 파일 · `manifest.webmanifest` 등)는 뺀다 — 이 응답들은 CSP 를 받지 않는다(문서가 아니라 지킬 스크립트가 없다).
  // 값은 빌드 때 정적으로 읽으므로 리터럴로 둔다
  matcher: ['/((?!_next/|api/|icon|apple-icon|app-icons/|.*\\.).*)'],
}
