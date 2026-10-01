import { SETUP_REGION_PATH } from '@/features/onboarding/paths'

import type { Consent } from './legal'

/**
 * 로그인. **API 연동 전 목 구현이다.** 세션 · 토큰 저장은 연동 이슈 범위라 여기서 하지 않는다 — 성공하면 이동만 한다.
 *
 * 연동 이슈에서 함수 안만 `src/lib/api/` 를 거친 백엔드 호출(백엔드 #56~#61)로 바꾸고 화면 코드는 그대로 둔다.
 * 요청 시간 제한 · 네트워크 실패는 API 계층이 맡고, 실패하면 Promise 를 거부한다 — 화면은 다시 시도하라고 알린다.
 * 비밀번호는 어디에도 남기지 않는다(로그 · 저장소 · 주소 금지).
 * 화면을 떠난 뒤 늦게 온 응답은 화면이 버린다(`src/lib/use-active-ref.ts`). 요청 자체 취소(AbortController)는 연동 이슈에서 붙인다.
 *
 * 목에서 오류 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다):
 * - 이메일 `locked@example.com` → `locked` (로그인 시도가 많아 잠시 막힘)
 * - 비밀번호 `wrong` → `wrong` (이메일 또는 비밀번호가 맞지 않음)
 * - 그 밖 → 성공
 */

export const MOCK_LOCKED_EMAIL = 'locked@example.com'
export const MOCK_WRONG_PASSWORD = 'wrong'

export type EmailLoginResult = { status: 'ok' } | { status: 'wrong' } | { status: 'locked' }

export function loginWithEmail(email: string, password: string): Promise<EmailLoginResult> {
  if (email.trim().toLowerCase() === MOCK_LOCKED_EMAIL) return Promise.resolve({ status: 'locked' })
  if (password === MOCK_WRONG_PASSWORD) return Promise.resolve({ status: 'wrong' })
  return Promise.resolve({ status: 'ok' })
}

/**
 * 카카오 로그인을 시작한다. 돌려준 주소로 화면이 이동한다.
 *
 * 목은 신규 회원으로 보고 동네 선택(S02-1)으로 보낸다. 연동 때는 `GET /api/v1/auth/kakao/authorize` 로 브라우저를
 * 보내는 리다이렉트가 되고, 카카오 콜백이 신규 · 기존 회원을 가려 돌려보낸다. 실패하면 `/login?error=kakao-fail`,
 * 이메일 회원과 겹치면 `/login?error=kakao-exists` 로 온다.
 *
 * `switchAccount` 는 "다른 카카오 계정으로 계속하기" 다 — 연동 때 카카오 계정 고르기 화면을 띄우게 넘긴다.
 */
export function startKakaoLogin(
  options: { switchAccount?: boolean } = {},
): Promise<{ redirectTo: string }> {
  // 목에는 카카오 계정 고르기 화면이 없어 switchAccount 를 쓰지 않는다
  void options
  return Promise.resolve({ redirectTo: SETUP_REGION_PATH })
}

/* ── 이메일 가입 인증 (S13-2 · S13-3) ───────────────────────────────────────────────
 *
 * 연동 때 `POST /api/v1/auth/email/send-code` · `POST /api/v1/auth/email/verify-code`(백엔드 #56)로 바꾼다.
 *
 * 목에서 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다):
 * - 코드 받기: 이메일 `exists@example.com` → `exists`, `limit@example.com` → `limit`, 그 밖 → 보냄
 * - 코드 확인: `999999` → `locked`, `000000` → `wrong`(남은 시도가 1씩 준다. 0 이 되면 `locked`),
 *   5분이 지났거나 보낸 코드가 없으면(이미 인증에 쓴 코드 포함) `expired`, 그 밖 6자리 → 성공
 *
 * 남은 시도 · 보낸 시각은 서버가 갖는 값이라 목도 이 모듈 안(메모리)에만 둔다. 화면을 새로 열면 초기화된다.
 */

/** 코드 유효 시간 · 다시 받기 대기 (Signup-code 시안 "5분 안에", "다시 받기 0:42") */
export const CODE_TTL_SECONDS = 300
export const RESEND_COOLDOWN_SECONDS = 60
/** 첫 실패 뒤 "남은 시도는 3번이에요" 가 되도록 4번에서 시작한다 */
export const CODE_MAX_ATTEMPTS = 4

export const MOCK_EXISTS_EMAIL = 'exists@example.com'
export const MOCK_LIMIT_EMAIL = 'limit@example.com'
export const MOCK_WRONG_CODE = '000000'
export const MOCK_LOCKED_CODE = '999999'

export type SendCodeResult = { status: 'sent' } | { status: 'exists' } | { status: 'limit' }

export type VerifyCodeResult =
  /** 인증 완료. 가입 요청(S02-3)에 함께 보내는 값 — 목은 아무 뜻 없는 문자열이다 */
  | { status: 'ok'; verificationToken: string }
  | { status: 'wrong'; remainingAttempts: number }
  | { status: 'expired' }
  | { status: 'locked' }

const mockCodes = new Map<string, { sentAt: number; remainingAttempts: number }>()

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

export function sendEmailCode(email: string): Promise<SendCodeResult> {
  const key = normalizeEmail(email)
  if (key === MOCK_EXISTS_EMAIL) return Promise.resolve({ status: 'exists' })
  if (key === MOCK_LIMIT_EMAIL) return Promise.resolve({ status: 'limit' })
  mockCodes.set(key, { sentAt: Date.now(), remainingAttempts: CODE_MAX_ATTEMPTS })
  return Promise.resolve({ status: 'sent' })
}

export function verifyEmailCode(email: string, code: string): Promise<VerifyCodeResult> {
  const sent = mockCodes.get(normalizeEmail(email))
  // 보낸 코드가 없으면(이미 쓴 코드 · 서버가 지운 코드) 만료로 본다 — 다시 받으면 된다
  if (!sent) return Promise.resolve({ status: 'expired' })
  if (code === MOCK_LOCKED_CODE || sent.remainingAttempts <= 0) {
    return Promise.resolve({ status: 'locked' })
  }
  if (Date.now() - sent.sentAt > CODE_TTL_SECONDS * 1000)
    return Promise.resolve({ status: 'expired' })
  if (code === MOCK_WRONG_CODE || !/^\d{6}$/.test(code)) {
    sent.remainingAttempts -= 1
    return Promise.resolve(
      sent.remainingAttempts <= 0
        ? { status: 'locked' }
        : { status: 'wrong', remainingAttempts: sent.remainingAttempts },
    )
  }
  mockCodes.delete(normalizeEmail(email))
  return Promise.resolve({ status: 'ok', verificationToken: `mock-verified-${Date.now()}` })
}

/* ── 가입 · 내 동네 · 건강정보 동의 (S02-3 · S02-4) ─────────────────────────────────
 *
 * 연동 때 바꾼다: 가입 `POST /api/v1/auth/signup`(백엔드 #56), 내 동네 저장(#60), 건강정보 동의(#59).
 *
 * 목에서 실패를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다):
 * - 가입: 이메일 `signup-fail@example.com` 으로 가입하면 응답을 받지 못한다(거부)
 */

export const MOCK_SIGNUP_FAIL_EMAIL = 'signup-fail@example.com'

/**
 * 가입 요청. 가입 종류는 인증 값(`verificationToken`) 유무로 가린다 — 있으면 이메일 가입, 없으면 카카오 가입.
 * 카카오 가입은 카카오가 이메일 · 닉네임을 주므로 동의만 보낸다.
 * 비밀번호는 이 요청에 한 번 실어 보내고 화면은 바로 지운다(로그 · 저장소 금지).
 */
export type SignupRequest =
  | {
      kind: 'email'
      email: string
      verificationToken: string
      password: string
      nickname: string
      consents: Consent[]
    }
  | { kind: 'kakao'; consents: Consent[] }

export function signup(request: SignupRequest): Promise<void> {
  if (request.kind === 'email' && normalizeEmail(request.email) === MOCK_SIGNUP_FAIL_EMAIL) {
    return Promise.reject(new Error('mock signup failure'))
  }
  return Promise.resolve()
}

/** 내 동네(행정동 코드) 저장 */
export function saveRegion(code: string): Promise<void> {
  void code
  return Promise.resolve()
}

/** 건강 · 증상 정보(민감정보) 처리 동의. 근거 문서 버전을 함께 보낸다 */
export function agreeHealthConsent(consent: Consent): Promise<void> {
  void consent
  return Promise.resolve()
}
