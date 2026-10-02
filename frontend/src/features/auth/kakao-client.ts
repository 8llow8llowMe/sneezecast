import { SETUP_REGION_FROM_KAKAO_PATH } from '@/features/onboarding/paths'
import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import type { DataSource } from '@/lib/data-source'
import { type AuthToken, setSession } from '@/lib/session/session-store'

import { signInMockKakao } from './auth-client'
import { kakaoTicketLost } from './kakao-ticket'
import type { KakaoFailReason } from './login-notice'

/* ── 카카오 로그인 · 계정 연결 (S13-1, #167 — 백엔드 #61) ─────────────────────────────────────────────
 *
 * 계약 정본은 backend/docs/modules.md auth "카카오 로그인 · 가입 · 연결" · "화면 계약" 의 카카오 절이다.
 *
 * 1. `startKakaoLogin` — `GET /api/v1/auth/kakao/authorize?switchAccount=` 가 카카오 인가 주소(`authorizeUrl`)와 state 쿠키를 준다.
 *    화면은 그 주소로 문서를 옮긴다(`assignLocation`). 리다이렉트가 아니라 fetch 라 `credentials: 'include'`(API 계층 기본값)로 쿠키를 받는다
 * 2. 카카오가 프론트 콜백(`/login/kakao/callback`)으로 `code` · `state` 를 붙여 돌아온다. 콜백 화면이 `kakaoLogin` 을 한 번 부른다
 * 3. 결과 `LOGGED_IN`(세션을 넣음) · `SIGNUP_REQUIRED`(가입 마무리 S02-1 → S02-3 의 `signup` 카카오 갈래, `auth-client.ts`) ·
 *    `LINK_REQUIRED`(계정 연결 확인 → `linkKakaoAccount`)
 *
 * - 모두 인증이 필요 없는 요청이라 `auth: false` 다. state · 가입표 · 연결 확인표는 HttpOnly 쿠키(`Path=/api/v1/auth` · SameSite=Strict)라
 *   화면이 다루지 않는다. 웹과 API 가 같은 사이트(`*.sneezecast.com`)일 때만 실린다 — FE 로컬(`http://localhost`)에서는 state 쿠키가
 *   실리지 않아 콜백이 `AUTH_020` 으로 끝난다(재발급과 같은 한계)
 * - 인가 코드 · state · 인가 주소(state 가 실려 있다)는 로그 · 저장소 · 오류 문구에 남기지 않는다. 코드 · state 는 요청 본문으로만 보내므로
 *   API(게이트웨이 · auth) 로그에는 남지 않는다. 카카오가 콜백 문서 주소(`/login/kakao/callback?code=…`)에 붙여 보내는 것은 막을 수 없어,
 *   그 문서 요청 자체의 쿼리가 남는지는 웹 앞단 접근 로그 형식(Infra)에 달렸다. 브라우저 쪽은 콜백이 주소에서 바로 지우고,
 *   콜백 문서에는 `Referrer-Policy: no-referrer` 를 붙인다(`next.config.ts`)
 */

const KAKAO_AUTHORIZE_PATH = '/api/v1/auth/kakao/authorize'
const KAKAO_LOGIN_PATH = '/api/v1/auth/kakao/login'
const KAKAO_LINK_PATH = '/api/v1/auth/kakao/link'

/** 백엔드가 주는 인가 주소의 오리진 · 경로(`oauth.kakao.authorize-uri` = `https://kauth.kakao.com/oauth/authorize`) */
const KAKAO_AUTHORIZE_ORIGIN = 'https://kauth.kakao.com'
const KAKAO_AUTHORIZE_URL_PATH = '/oauth/authorize'

function errorCodeOf(error: unknown): string | null {
  return error instanceof ApiError ? error.code : null
}

/**
 * 따라가도 되는 카카오 인가 주소인지 — https 이고 카카오 인가 호스트(`kauth.kakao.com`, 기본 포트) · 경로(`/oauth/authorize`)이며
 * 사용자 정보(`user:pass@`)가 없을 때만 참이다. 응답이 어긋나거나 바뀌어도 다른 곳으로 문서를 옮기지 않는다(오픈 리다이렉트 방지)
 */
export function isKakaoAuthorizeUrl(value: unknown): value is string {
  if (typeof value !== 'string') return false
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return false
  }
  return (
    url.origin === KAKAO_AUTHORIZE_ORIGIN &&
    url.pathname === KAKAO_AUTHORIZE_URL_PATH &&
    url.username === '' &&
    url.password === ''
  )
}

/**
 * - `redirect`: 그 주소로 간다. `external` 이면 앱 밖(카카오 인가 화면 — `assignLocation`), 아니면 앱 안(`router.push`)
 * - `limited`: 이 기기(IP)에서 카카오 로그인을 너무 많이 시작했다(`AUTH_028`, 429). 잠시 뒤 다시 하라고 알린다
 */
export type KakaoStartResult =
  { status: 'redirect'; href: string; external: boolean } | { status: 'limited' }

/**
 * 카카오 로그인을 시작한다. `switchAccount` 는 "다른 카카오 계정으로 계속하기" 다 — 카카오가 로그인된 계정을 그대로 쓰지 않고
 * 계정을 고르게 한다(`prompt=select_account`). 백엔드는 이 요청에서 받다 만 가입표 · 연결 확인표 쿠키를 지운다.
 *
 * 실데이터: 인가 주소가 `isKakaoAuthorizeUrl` 을 통과할 때만 돌려주고 아니면 거부한다. `AUTH_028` → `limited`.
 * 그 밖(일시 장애 · 저장소 장애)은 거부한다 — 화면은 "카카오 로그인을 시작하지 못했어요".
 *
 * 목: 카카오를 다녀오지 않고 신규 회원으로 보아 동네 선택(S02-1, `?from=kakao`)으로 보낸다. 계정 연결 확인 · 로그인 · 실패는
 * 콜백 주소로 재현한다(아래 `kakaoLogin` 의 목).
 */
export async function startKakaoLogin(
  source: DataSource,
  { switchAccount = false }: { switchAccount?: boolean } = {},
): Promise<KakaoStartResult> {
  if (source === 'mock') {
    // 목에는 카카오 계정 고르기 화면이 없어 switchAccount 를 쓰지 않는다
    return { status: 'redirect', href: SETUP_REGION_FROM_KAKAO_PATH, external: false }
  }
  let body: { authorizeUrl?: unknown } | null
  try {
    body = await apiRequest<{ authorizeUrl?: unknown } | null>(KAKAO_AUTHORIZE_PATH, {
      query: { switchAccount },
      auth: false,
    })
  } catch (error) {
    if (errorCodeOf(error) === 'AUTH_028') return { status: 'limited' }
    throw error
  }
  const authorizeUrl = body?.authorizeUrl
  // 주소에는 state 가 실려 있어 오류 문구에 싣지 않는다
  if (!isKakaoAuthorizeUrl(authorizeUrl))
    throw new Error('startKakaoLogin: unexpected authorize url')
  return { status: 'redirect', href: authorizeUrl, external: true }
}

/**
 * 카카오 콜백 결과.
 * - `logged-in`: 카카오 로그인이 연결된 회원이다. 세션을 이미 넣었다
 * - `signup-required`: 처음 온 이메일이다. 가입표 쿠키를 받았다(30분) — 가입 마무리(S02-1 → S02-3)로 간다
 * - `link-required`: 같은 이메일의 이메일 계정이 있다. 연결 확인표 쿠키를 받았다(10분) — `maskedEmail` 은 서버가 가린 이메일(`d***@example.com`)
 * - `failed`: 화면이 사유를 따로 알리는 실패(`KakaoFailReason`)
 */
export type KakaoLoginResult =
  | { status: 'logged-in' }
  | { status: 'signup-required' }
  | { status: 'link-required'; maskedEmail: string }
  | { status: 'failed'; reason: KakaoFailReason }

/** 백엔드 `AuthOAuthLoginResponse`. 결과에 따라 채워지는 필드가 다르고 없는 필드는 빠진다 */
type KakaoLoginResponse = {
  result?: unknown
  memberId?: unknown
  role?: unknown
  accessToken?: unknown
  accessTokenExpiresIn?: unknown
  pendingConsents?: unknown
  reportWritable?: unknown
  email?: unknown
}

const KAKAO_LOGIN_FAILURES: Readonly<Record<string, KakaoFailReason>> = {
  AUTH_023: 'email-required',
  AUTH_024: 'email-unverified',
  MEMBER_003: 'suspended',
}

/** `LOGGED_IN` 응답에서 세션에 넣을 값만 고른다. 로그인 응답 필드가 빠졌으면 null(계약이 어긋났다) */
function tokenOf(response: KakaoLoginResponse): AuthToken | null {
  const { memberId, role, accessToken, accessTokenExpiresIn, pendingConsents, reportWritable } =
    response
  if (
    typeof memberId !== 'string' ||
    (role !== 'USER' && role !== 'OPERATOR' && role !== 'ADMIN') ||
    typeof accessToken !== 'string' ||
    typeof accessTokenExpiresIn !== 'number' ||
    !Array.isArray(pendingConsents) ||
    !pendingConsents.every((item) => typeof item === 'string') ||
    typeof reportWritable !== 'boolean'
  ) {
    return null
  }
  return {
    memberId,
    role,
    accessToken,
    accessTokenExpiresIn,
    pendingConsents,
    reportWritable,
  }
}

/**
 * 카카오 콜백의 로그인. 실데이터는 `POST /api/v1/auth/kakao/login {code, state}`(`auth: false`, state 쿠키)다.
 * 서버는 결과와 무관하게 state 쿠키를 지운다 — 같은 code · state 로 두 번 보내면 `AUTH_020` 이라, 부르는 화면이 한 번만 부른다.
 * `LOGGED_IN` 이면 응답을 그대로 `setSession` 한다(응답 전에 화면을 떠나도 넣는다 — 서버에는 이미 세션이 생겼다).
 *
 * 오류 코드 → 결과: `AUTH_023` → `email-required`, `AUTH_024` → `email-unverified`, `MEMBER_003` → `suspended`.
 * 그 밖(`AUTH_020` state 무효 · `021` 카카오가 code 거부 · `022` 카카오 장애 · `MEMBER_002` 탈퇴 · 저장소 장애 · 검증 `AUTH_117~120` ·
 * 일시 장애)과 모르는 결과 · 빠진 필드는 거부한다 — 화면은 사유 없이 "카카오 로그인을 마치지 못했어요" 로 알린다.
 * 탈퇴는 이메일 로그인(#163)처럼 따로 드러내지 않는다.
 *
 * 목: `code` 로 결과를 고른다(docs/design/SCREENS.md "카카오 로그인" 에도 적어 둔다). state 는 보지 않는다.
 * - `login` → `logged-in`(카카오 회원 목 세션), `link` → `link-required`(`d***@example.com`)
 * - 서버 오류 코드 모양(`AUTH_023` · `MEMBER_003` 등) → 실데이터가 그 오류를 받은 것과 같다
 * - 그 밖 → `signup-required`
 */
export async function kakaoLogin(
  code: string,
  state: string,
  source: DataSource,
): Promise<KakaoLoginResult> {
  let response: KakaoLoginResponse | null
  try {
    response =
      source === 'api'
        ? await apiRequest<KakaoLoginResponse | null>(KAKAO_LOGIN_PATH, {
            method: 'POST',
            body: { code, state },
            auth: false,
          })
        : mockKakaoLoginResponse(code)
  } catch (error) {
    const errorCode = errorCodeOf(error)
    const reason =
      errorCode !== null && Object.hasOwn(KAKAO_LOGIN_FAILURES, errorCode)
        ? KAKAO_LOGIN_FAILURES[errorCode]
        : undefined
    if (reason) return { status: 'failed', reason }
    throw error
  }
  switch (response?.result) {
    case 'LOGGED_IN': {
      if (source === 'mock') {
        signInMockKakao('kakao')
        return { status: 'logged-in' }
      }
      const token = tokenOf(response)
      if (!token) throw new Error('kakaoLogin: unexpected login response')
      setSession(token)
      return { status: 'logged-in' }
    }
    case 'SIGNUP_REQUIRED':
      return { status: 'signup-required' }
    case 'LINK_REQUIRED':
      if (typeof response.email !== 'string' || response.email === '') {
        throw new Error('kakaoLogin: link response without email')
      }
      return { status: 'link-required', maskedEmail: response.email }
    default:
      throw new Error('kakaoLogin: unexpected result')
  }
}

export const MOCK_KAKAO_LOGIN_CODE = 'login'
export const MOCK_KAKAO_LINK_CODE = 'link'
/** 목 연결 확인의 가린 이메일. 연결하면 목 프로필은 예시 이메일 계정(`dong@example.com`)이 된다 */
export const MOCK_KAKAO_MASKED_EMAIL = 'd***@example.com'

/** 목 서버의 카카오 로그인 응답. 서버 오류 코드 모양의 code 면 그 오류를 던진다 */
function mockKakaoLoginResponse(code: string): KakaoLoginResponse {
  if (/^(AUTH|MEMBER)_\d{3}$/.test(code)) {
    throw new ApiError({ status: 400, code, message: 'mock kakao login failure' })
  }
  if (code === MOCK_KAKAO_LOGIN_CODE) return { result: 'LOGGED_IN' }
  if (code === MOCK_KAKAO_LINK_CODE)
    return { result: 'LINK_REQUIRED', email: MOCK_KAKAO_MASKED_EMAIL }
  return { result: 'SIGNUP_REQUIRED' }
}

/**
 * - `ok`: 연결하고 로그인했다(세션을 넣었다)
 * - `restart`: 카카오 로그인부터 다시 한다 — 확인표가 없거나 10분이 지남(`AUTH_026`, 사유 `expired`) · 연결할 수 없는 계정
 *   (`AUTH_027` — 그사이 탈퇴 · 정지 · 이미 연결됨, 사유 없음) · 그 밖에 서비스가 업무 오류로 답함(저장소 장애 등 — 확인표를 이미
 *   잃었다, `kakaoTicketLost`, 사유 없음)
 */
export type KakaoLinkResult =
  { status: 'ok' } | { status: 'restart'; reason: KakaoFailReason | null }

/**
 * 계정 연결 확인의 `연결하고 계속하기`. 실데이터는 `POST /api/v1/auth/kakao/link`(바디 없음, `auth: false`, 연결 확인표 쿠키)다.
 * 응답은 로그인 응답(`AuthToken`)이라 그대로 `setSession` 한다. 연결해도 이메일 · 비밀번호 로그인은 그대로 된다.
 * 응답을 받지 못한 실패(네트워크 · 타임아웃 · 게이트웨이 오류)만 거부한다 — 확인표가 남았을 수 있어 화면은 다시 누르게 한다.
 *
 * 목: 늘 성공하고 목 세션을 연결한 이메일 계정으로 둔다(`signInMockKakao('linked')`).
 */
export async function linkKakaoAccount(source: DataSource): Promise<KakaoLinkResult> {
  if (source === 'mock') {
    signInMockKakao('linked')
    return { status: 'ok' }
  }
  let token: AuthToken
  try {
    token = await apiRequest<AuthToken>(KAKAO_LINK_PATH, { method: 'POST', auth: false })
  } catch (error) {
    switch (errorCodeOf(error)) {
      case 'AUTH_026':
        return { status: 'restart', reason: 'expired' }
      case 'AUTH_027':
        return { status: 'restart', reason: null }
      default:
        if (kakaoTicketLost(error)) return { status: 'restart', reason: null }
        throw error
    }
  }
  setSession(token)
  return { status: 'ok' }
}
