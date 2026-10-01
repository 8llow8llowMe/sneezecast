import { SETUP_REGION_FROM_KAKAO_PATH } from '@/features/onboarding/paths'

import type { Consent } from './legal'

/* ── 목 회원 상태 ──────────────────────────────────────────────────────────────────
 *
 * 홈이 보고 진입을 나눌 때 쓰는 회원 · 동의 상태다 (docs/design/SCREENS.md "목 회원 상태").
 * **API 연동 전 목이다.** 연동 때 이 자리를 실제 세션(토큰 · `report:write` scope)으로 바꾸고, 화면은 `useMockAuth` 를
 * 세션 훅으로 바꾼다.
 *
 * - `guest`: 로그인하지 않음 (기본값)
 * - `member-no-consent`: 회원이지만 건강정보(민감정보) 동의를 하지 않음
 * - `member`: 회원이고 건강정보 동의를 함 — 보고할 수 있다
 *
 * 값을 바꾸는 곳은 이 모듈의 세 함수뿐이다: 카카오 가입 성공(`signup` kind kakao) · 이메일 로그인 성공(`loginWithEmail`)은
 * `member-no-consent`, 건강정보 동의 성공(`agreeHealthConsent`)은 `member`. 화면 코드는 고치지 않는다.
 * 모듈 메모리에만 두어 새로고침하면 `guest` 로 돌아간다 — 브라우저 저장소에 남기지 않는다.
 */
export type MockAuthState = 'guest' | 'member-no-consent' | 'member'

export const MOCK_AUTH_STATES: readonly MockAuthState[] = ['guest', 'member-no-consent', 'member']

let mockSession: MockAuthState = 'guest'
const mockSessionListeners = new Set<() => void>()

export function getMockSession(): MockAuthState {
  return mockSession
}

/** 목 세션이 바뀔 때 부른다 (`useSyncExternalStore` 의 subscribe). 돌려준 함수로 구독을 끊는다 */
export function subscribeMockSession(listener: () => void): () => void {
  mockSessionListeners.add(listener)
  return () => {
    mockSessionListeners.delete(listener)
  }
}

function setMockSession(next: MockAuthState) {
  if (mockSession === next) return
  mockSession = next
  mockSessionListeners.forEach((listener) => listener())
}

/** 테스트에서 목 세션을 처음(`guest`)으로 되돌린다. 화면 코드는 부르지 않는다 */
export function resetMockSession() {
  setMockSession('guest')
}

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
  // 목은 동의 여부를 모른다. 연동 때는 서버 세션이 동의 상태를 알려 준다
  setMockSession('member-no-consent')
  return Promise.resolve({ status: 'ok' })
}

/**
 * 카카오 로그인을 시작한다. 돌려준 주소로 화면이 이동한다.
 *
 * 목은 신규 회원으로 보고 동네 선택(S02-1)으로 보낸다 — `?from=kakao` 를 붙여 S02-1 이 가입 종류를 카카오로 둔다
 * (홈의 로그인 안내 시트처럼 첫 진입 Provider 밖에서 시작해도 이어지게). 연동 때는 `GET /api/v1/auth/kakao/authorize` 로 브라우저를
 * 보내는 리다이렉트가 되고, 카카오 콜백이 신규 · 기존 회원을 가려 돌려보낸다(신규 회원은 같은 `?from=kakao` 주소). 실패하면 `/login?error=kakao-fail`,
 * 이메일 회원과 겹치면 `/login?error=kakao-exists` 로 온다.
 *
 * `switchAccount` 는 "다른 카카오 계정으로 계속하기" 다 — 연동 때 카카오 계정 고르기 화면을 띄우게 넘긴다.
 */
export function startKakaoLogin(
  options: { switchAccount?: boolean } = {},
): Promise<{ redirectTo: string }> {
  // 목에는 카카오 계정 고르기 화면이 없어 switchAccount 를 쓰지 않는다
  void options
  return Promise.resolve({ redirectTo: SETUP_REGION_FROM_KAKAO_PATH })
}

/* ── 이메일 가입 인증 (S13-2 · S13-3) ───────────────────────────────────────────────
 *
 * 연동 때 `POST /api/v1/auth/email/send-code` · `POST /api/v1/auth/email/verify-code`(백엔드 #56)로 바꾼다.
 * 화면 계약(backend/docs/modules.md "화면 계약"):
 * - 코드 받기는 **가입 여부와 무관하게 같은 응답**이다(계정 열거 방지). 이미 가입된 이메일이면 서버가 코드 대신
 *   안내 메일을 보낸다 — 화면은 늘 코드 단계로 가고 중립 문구로 알린다
 * - 코드 확인은 **토큰을 주지 않는다.** 인증 완료 표시는 서버가 이메일별로 30분 들고 있다가 가입 요청 때 확인한다
 * - 발송 제한(`AUTH_001` 쿨다운 · `AUTH_002` IP 상한)은 남은 시간을 주지 않는다 — 화면은 시간을 못 박지 않는다
 * - 남은 시도 횟수도 주지 않는다. 틀리면 `AUTH_003`, 5번째로 틀리면 `AUTH_005`(잠김)이고 서버가 코드를 지워
 *   그다음 확인은 `AUTH_004`(만료)다. 코드를 다시 받으면 서버의 실패 수가 0 이 된다.
 *   그래서 연동 때 이 모듈이 이메일별 실패 수를 세어 `remainingAttempts`(= `CODE_MAX_ATTEMPTS` - 실패 수)를 채우고,
 *   `sendEmailCode` 가 `sent` 이면 그 이메일의 실패 수를 0 으로 되돌린다. 화면 쪽 타입(`VerifyCodeResult`)은 그대로다
 *
 * 목에서 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다):
 * - 코드 받기: 이메일 `limit@example.com` → `limit`, 그 밖 → 보냄
 * - 코드 확인: `999999` → `locked`, `000000` → `wrong`(남은 시도가 1씩 준다. 0 이 되면 `locked`),
 *   5분이 지났거나 보낸 코드가 없으면(이미 인증에 쓴 코드 · 잠겨 지운 코드 포함) `expired`, 그 밖 6자리 → 성공.
 *   서버처럼 잠기면 코드를 지우므로 잠긴 뒤의 확인은 `expired` 다
 *
 * 목은 서버가 갖는 코드 · 보낸 시각 · 인증 시각과 연동 때 이 모듈이 셀 실패 수를 함께 이 모듈 안(메모리)에 둔다.
 * 화면을 새로 열면 초기화된다.
 */

/** 코드 유효 시간 · 다시 받기 대기 (Signup-code 시안 "5분 안에", "다시 받기 0:42") */
export const CODE_TTL_SECONDS = 300
export const RESEND_COOLDOWN_SECONDS = 60
/** 코드를 틀릴 수 있는 횟수(백엔드 오입력 5회). 첫 실패 뒤 "남은 시도는 4번이에요" 가 된다 */
export const CODE_MAX_ATTEMPTS = 5
/** 인증을 마친 뒤 가입 요청까지 쓸 수 있는 시간(백엔드 인증 완료 30분). 지나면 가입이 `verification-expired` 다 */
export const EMAIL_VERIFICATION_TTL_SECONDS = 1800

export const MOCK_LIMIT_EMAIL = 'limit@example.com'
export const MOCK_WRONG_CODE = '000000'
export const MOCK_LOCKED_CODE = '999999'

export type SendCodeResult = { status: 'sent' } | { status: 'limit' }

export type VerifyCodeResult =
  /** 인증 완료. 서버가 인증 표시를 들고 있으므로 돌려주는 값은 없다 */
  | { status: 'ok' }
  | { status: 'wrong'; remainingAttempts: number }
  | { status: 'expired' }
  | { status: 'locked' }

const mockCodes = new Map<string, { sentAt: number; remainingAttempts: number }>()
/** 이메일별 인증을 마친 시각(ms). 서버의 인증 완료 표시를 흉내 낸다 */
const mockVerifiedAt = new Map<string, number>()

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

export function sendEmailCode(email: string): Promise<SendCodeResult> {
  const key = normalizeEmail(email)
  if (key === MOCK_LIMIT_EMAIL) return Promise.resolve({ status: 'limit' })
  mockCodes.set(key, { sentAt: Date.now(), remainingAttempts: CODE_MAX_ATTEMPTS })
  return Promise.resolve({ status: 'sent' })
}

export function verifyEmailCode(email: string, code: string): Promise<VerifyCodeResult> {
  const key = normalizeEmail(email)
  const sent = mockCodes.get(key)
  // 보낸 코드가 없으면(이미 쓴 코드 · 잠겨 지운 코드 · 서버가 지운 코드) 만료로 본다 — 다시 받으면 된다
  if (!sent) return Promise.resolve({ status: 'expired' })
  if (Date.now() - sent.sentAt > CODE_TTL_SECONDS * 1000)
    return Promise.resolve({ status: 'expired' })
  if (code === MOCK_LOCKED_CODE) {
    mockCodes.delete(key)
    return Promise.resolve({ status: 'locked' })
  }
  if (code === MOCK_WRONG_CODE || !/^\d{6}$/.test(code)) {
    sent.remainingAttempts -= 1
    if (sent.remainingAttempts > 0) {
      return Promise.resolve({ status: 'wrong', remainingAttempts: sent.remainingAttempts })
    }
    // 서버는 잠그면서 코드를 지운다(AUTH_005). 그다음 확인은 만료(AUTH_004)다
    mockCodes.delete(key)
    return Promise.resolve({ status: 'locked' })
  }
  mockCodes.delete(key)
  mockVerifiedAt.set(key, Date.now())
  return Promise.resolve({ status: 'ok' })
}

/* ── 가입 · 내 동네 · 건강정보 동의 (S02-3 · S02-4) ─────────────────────────────────
 *
 * 연동 때 바꾼다: 가입 `POST /api/v1/auth/signup`(백엔드 #56), 내 동네 저장(#60), 건강정보 동의(#59).
 * 가입 응답에는 토큰이 없다 — 이메일 가입은 이어서 `loginWithEmail`(#57)로 로그인한 뒤 동네를 저장한다.
 * 카카오 가입은 카카오 로그인으로 이미 로그인한 상태라 바로 동네를 저장한다.
 *
 * 목에서 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다):
 * - 가입: 이메일 `signup-fail@example.com` 이면 응답을 받지 못한다(거부)
 * - 가입: 이메일 `verify-expired@example.com` 이면 늘 `verification-expired`(인증 30분이 지남, `AUTH_007`).
 *   그 밖의 이메일도 인증을 마치지 않았거나 마친 지 30분이 지났으면 `verification-expired` 다
 */

export const MOCK_SIGNUP_FAIL_EMAIL = 'signup-fail@example.com'
export const MOCK_VERIFY_EXPIRED_EMAIL = 'verify-expired@example.com'

/**
 * 가입 요청. 가입 종류(`kind`)는 화면이 가입 초안의 `method` 로 정한다.
 * 이메일 가입은 인증을 마친 이메일과 비밀번호 · 닉네임을 보낸다 — 인증 여부는 서버가 이메일로 확인한다.
 * 카카오 가입은 카카오가 이메일 · 닉네임을 주므로 동의만 보낸다.
 * 비밀번호는 로그나 저장소에 남기지 않는다. 화면은 가입 뒤 로그인까지 마치면 지운다.
 */
export type SignupRequest =
  | {
      kind: 'email'
      email: string
      password: string
      nickname: string
      consents: Consent[]
    }
  | { kind: 'kakao'; consents: Consent[] }

/** `verification-expired` 는 이메일 인증 표시가 없거나 30분이 지났다는 뜻이다(`AUTH_007`). 이메일 단계부터 다시 한다 */
export type SignupResult = { status: 'ok' } | { status: 'verification-expired' }

export function signup(request: SignupRequest): Promise<SignupResult> {
  if (request.kind === 'kakao') {
    // 카카오 가입은 카카오 로그인으로 이미 로그인한 상태다. 이메일 가입은 이어지는 loginWithEmail 이 회원으로 만든다
    setMockSession('member-no-consent')
    return Promise.resolve({ status: 'ok' })
  }
  const key = normalizeEmail(request.email)
  if (key === MOCK_SIGNUP_FAIL_EMAIL) return Promise.reject(new Error('mock signup failure'))
  const verifiedAt = mockVerifiedAt.get(key)
  if (
    key === MOCK_VERIFY_EXPIRED_EMAIL ||
    verifiedAt === undefined ||
    Date.now() - verifiedAt > EMAIL_VERIFICATION_TTL_SECONDS * 1000
  ) {
    return Promise.resolve({ status: 'verification-expired' })
  }
  // 서버처럼 가입에 쓴 인증 표시는 지운다
  mockVerifiedAt.delete(key)
  return Promise.resolve({ status: 'ok' })
}

/** 내 동네(행정동 코드) 저장 */
export function saveRegion(code: string): Promise<void> {
  void code
  return Promise.resolve()
}

/** 건강 · 증상 정보(민감정보) 처리 동의. 근거 문서 버전을 함께 보낸다 */
export function agreeHealthConsent(consent: Consent): Promise<void> {
  void consent
  // 동의를 보낸 화면이 응답 전에 닫혀도 서버에는 동의가 남는다. 세션 상태도 화면과 무관하게 여기서 바꾼다
  setMockSession('member')
  return Promise.resolve()
}
