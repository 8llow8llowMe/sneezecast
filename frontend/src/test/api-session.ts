import { vi } from 'vitest'

import { DATA_SOURCE_COOKIE } from '@/lib/data-source'
import { writeSessionHint } from '@/lib/session/session-hint'
import { type AuthToken, resetSessionForTests } from '@/lib/session/session-store'

/* 실데이터 모드(세션 저장소)를 흉내 내는 테스트 도구. jsdom 환경 파일에서만 쓴다(쿠키) */

/** 실데이터 모드로 바꾼다(출처 쿠키). 정리는 `resetApiSession` */
export function selectApiSource(): void {
  document.cookie = `${DATA_SOURCE_COOKIE}=api; Path=/`
}

/** 출처 쿠키 · 힌트 쿠키 · 메모리 세션 · 가짜 fetch 를 지운다 */
export function resetApiSession(): void {
  document.cookie = `${DATA_SOURCE_COOKIE}=; Path=/; Max-Age=0`
  writeSessionHint(false)
  resetSessionForTests()
  vi.unstubAllGlobals()
}

export function memberToken(overrides: Partial<AuthToken> = {}): AuthToken {
  return {
    memberId: '1843956734582784',
    role: 'USER',
    accessToken: 'access-1',
    accessTokenExpiresIn: 900,
    pendingConsents: [],
    reportWritable: true,
    ...overrides,
  }
}

function envelope(dataBody: unknown, error?: { code: string; status: number }): Response {
  const dataHeader = error
    ? { success: false, resultCode: error.code, resultMessage: '거절', fieldErrors: null }
    : { success: true, resultCode: null, resultMessage: null, fieldErrors: null }
  return new Response(JSON.stringify({ dataHeader, dataBody: error ? null : dataBody }), {
    status: error?.status ?? 200,
  })
}

/**
 * 재발급 응답을 테스트가 정할 때까지 붙잡아 두는 서버(가짜 fetch). 힌트 쿠키도 남긴다 —
 * 그다음 `restoreSession()` 을 부르면 `restoring` 에 머문다
 */
export function holdReissue(): {
  succeed: (token?: AuthToken) => void
  fail: (code: string, status: number) => void
} {
  writeSessionHint(true)
  let reply: (response: Response) => void = () => {}
  vi.stubGlobal(
    'fetch',
    vi.fn(() => new Promise<Response>((resolve) => (reply = resolve))),
  )
  return {
    succeed: (token = memberToken()) => reply(envelope(token)),
    fail: (code, status) => reply(envelope(null, { code, status })),
  }
}
