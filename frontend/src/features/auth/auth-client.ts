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
 * 값을 바꾸는 곳은 이 모듈의 함수뿐이다: 카카오 가입 성공(`signup` kind kakao) · 이메일 로그인 성공(`loginWithEmail`)은
 * `member-no-consent`, 건강정보 동의 성공(`agreeHealthConsent`)은 `member`, 동의 철회 성공(`withdrawHealthConsent`)은
 * `member-no-consent`, 로그아웃 · 탈퇴 성공(`logout` · `withdrawMembership`)은 `guest`. 화면 코드는 고치지 않는다.
 * 모듈 메모리에만 두어 새로고침하면 `guest` 로 돌아간다 — 브라우저 저장소에 남기지 않는다.
 *
 * 내 정보(S10)가 보일 프로필(`MockProfile`)도 같은 세션에 둔다. 로그인 · 가입할 때 채우고 로그아웃 · 탈퇴하면 지운다.
 * 연동 때 `GET /api/v1/members/me`(백엔드 #58) 응답으로 바꾼다.
 */
export type MockAuthState = 'guest' | 'member-no-consent' | 'member'

export const MOCK_AUTH_STATES: readonly MockAuthState[] = ['guest', 'member-no-consent', 'member']

/**
 * 내 정보에 보일 회원 프로필 (목). 로그인 방법(`provider`) · 이메일 · 닉네임만 둔다 — 이름 · 연락처 · 주소는 받지 않는다.
 * 연동 때 `GET /api/v1/members/me` 응답으로 바꾼다. 카카오 회원의 이메일 · 닉네임과 이메일 로그인의 닉네임은 목이 모르므로
 * 시안의 예시 값(`EXAMPLE_PROFILES`)을 쓴다.
 */
export type MockProfile = { provider: 'email' | 'kakao'; email: string; nickname: string }

/** 시안(Settings · Settings-kakao)의 예시 값. 실제 값은 `GET /me` 에서 받는다 */
export const EXAMPLE_PROFILES: Readonly<Record<MockProfile['provider'], MockProfile>> = {
  email: { provider: 'email', email: 'dong@example.com', nickname: '동네지기' },
  kakao: { provider: 'kakao', email: 'dong@kakao.com', nickname: '동네지기' },
}

let mockSession: MockAuthState = 'guest'
let mockProfile: MockProfile | null = null
const mockSessionListeners = new Set<() => void>()
/** 이메일 가입에서 받은 닉네임. 가입 뒤 이어지는 이메일 로그인이 프로필에 쓴다(목 서버가 회원 정보를 들고 있는 흉내) */
const mockNicknames = new Map<string, string>()

export function getMockSession(): MockAuthState {
  return mockSession
}

/** 목 세션의 프로필. 비회원이거나 `?mock-auth=` 덮어쓰기만 있으면 null 이다 */
export function getMockProfile(): MockProfile | null {
  return mockProfile
}

/** 목 세션이 바뀔 때 부른다 (`useSyncExternalStore` 의 subscribe). 돌려준 함수로 구독을 끊는다 */
export function subscribeMockSession(listener: () => void): () => void {
  mockSessionListeners.add(listener)
  return () => {
    mockSessionListeners.delete(listener)
  }
}

/** 세션을 바꾼다. `profile` 을 넘기지 않으면 프로필은 그대로 두고, `guest` 가 되면 늘 지운다 */
function setMockSession(next: MockAuthState, profile?: MockProfile) {
  const given = next === 'guest' ? null : (profile ?? mockProfile)
  // 같은 값의 프로필이면 이전 객체를 그대로 둔다 — 같은 회원이 다시 로그인해도 다시 그리지 않는다
  const nextProfile = sameProfile(given, mockProfile) ? mockProfile : given
  if (mockSession === next && mockProfile === nextProfile) return
  mockSession = next
  mockProfile = nextProfile
  mockSessionListeners.forEach((listener) => listener())
}

function sameProfile(a: MockProfile | null, b: MockProfile | null): boolean {
  if (a === null || b === null) return a === b
  return a.provider === b.provider && a.email === b.email && a.nickname === b.nickname
}

/** 테스트에서 목 세션을 처음(`guest`)으로 되돌린다. 화면 코드는 부르지 않는다 */
export function resetMockSession() {
  setMockSession('guest')
  mockNicknames.clear()
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
  const key = normalizeEmail(email)
  setMockSession('member-no-consent', {
    provider: 'email',
    email: key,
    nickname: mockNicknames.get(key) ?? EXAMPLE_PROFILES.email.nickname,
  })
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
 * 목은 서버가 갖는 코드 · 보낸 시각 · 인증 시각과 연동 때 이 모듈이 셀 실패 수를 함께 이 모듈 안(메모리)에 둔다
 * (`createMockCodeStore` · `signupVerifiedAt`). 화면을 새로 열면 초기화된다.
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

/** 코드 확인 실패. 가입 인증 · 재설정 인증이 같은 뜻으로 쓴다 */
export type VerifyCodeFailure = Exclude<VerifyCodeResult, { status: 'ok' }>

/**
 * 목 서버의 코드 저장소 하나. 서버가 갖는 코드 · 보낸 시각과 연동 때 이 모듈이 셀 실패 수를 둔다.
 * 가입 인증과 비밀번호 재설정은 서버에서 Redis 키가 다르므로(backend/docs/modules.md) 목도 저장소를 따로 둔다 —
 * 가입 코드로 재설정 인증을 마치거나 그 반대가 되지 않게 한다. 인증을 마친 뒤 무엇을 남길지(가입은 이메일별 인증 표시,
 * 재설정은 일회용 토큰)는 쓰는 쪽이 정한다.
 */
function createMockCodeStore() {
  const codes = new Map<string, { sentAt: number; remainingAttempts: number }>()

  function send(email: string): SendCodeResult {
    const key = normalizeEmail(email)
    if (key === MOCK_LIMIT_EMAIL) return { status: 'limit' }
    codes.set(key, { sentAt: Date.now(), remainingAttempts: CODE_MAX_ATTEMPTS })
    return { status: 'sent' }
  }

  /** 맞으면 코드를 지우고(서버처럼 한 번만 쓴다) `ok` 다 */
  function verify(email: string, code: string): VerifyCodeResult {
    const key = normalizeEmail(email)
    const sent = codes.get(key)
    // 보낸 코드가 없으면(이미 쓴 코드 · 잠겨 지운 코드 · 서버가 지운 코드) 만료로 본다 — 다시 받으면 된다
    if (!sent) return { status: 'expired' }
    if (Date.now() - sent.sentAt > CODE_TTL_SECONDS * 1000) return { status: 'expired' }
    if (code === MOCK_LOCKED_CODE) {
      codes.delete(key)
      return { status: 'locked' }
    }
    if (code === MOCK_WRONG_CODE || !/^\d{6}$/.test(code)) {
      sent.remainingAttempts -= 1
      if (sent.remainingAttempts > 0) {
        return { status: 'wrong', remainingAttempts: sent.remainingAttempts }
      }
      // 서버는 잠그면서 코드를 지운다(AUTH_005). 그다음 확인은 만료(AUTH_004)다
      codes.delete(key)
      return { status: 'locked' }
    }
    codes.delete(key)
    return { status: 'ok' }
  }

  return { send, verify }
}

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

const signupCodes = createMockCodeStore()
/** 이메일별 가입 인증을 마친 시각(ms). 서버의 인증 완료 표시를 흉내 낸다 */
const signupVerifiedAt = new Map<string, number>()

export function sendEmailCode(email: string): Promise<SendCodeResult> {
  return Promise.resolve(signupCodes.send(email))
}

export function verifyEmailCode(email: string, code: string): Promise<VerifyCodeResult> {
  const result = signupCodes.verify(email, code)
  if (result.status === 'ok') signupVerifiedAt.set(normalizeEmail(email), Date.now())
  return Promise.resolve(result)
}

/**
 * 가입 인증 표시를 쓴다. 재현용 만료 이메일 · 인증하지 않음 · 마친 지 30분이 지남이면 false 다.
 * 서버처럼 쓴 인증 표시는 지운다
 */
function consumeSignupVerification(email: string): boolean {
  const key = normalizeEmail(email)
  const at = signupVerifiedAt.get(key)
  if (
    key === MOCK_VERIFY_EXPIRED_EMAIL ||
    at === undefined ||
    Date.now() - at > EMAIL_VERIFICATION_TTL_SECONDS * 1000
  ) {
    return false
  }
  signupVerifiedAt.delete(key)
  return true
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
    // 카카오 가입은 카카오 로그인으로 이미 로그인한 상태다. 이메일 가입은 이어지는 loginWithEmail 이 회원으로 만든다.
    // 카카오가 주는 이메일 · 닉네임은 목이 몰라 예시 값을 쓴다
    setMockSession('member-no-consent', EXAMPLE_PROFILES.kakao)
    return Promise.resolve({ status: 'ok' })
  }
  if (normalizeEmail(request.email) === MOCK_SIGNUP_FAIL_EMAIL) {
    return Promise.reject(new Error('mock signup failure'))
  }
  // 서버처럼 가입에 쓴 인증 표시는 지운다
  const ok = consumeSignupVerification(request.email)
  if (ok) mockNicknames.set(normalizeEmail(request.email), request.nickname)
  return Promise.resolve({ status: ok ? 'ok' : 'verification-expired' })
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

/* ── 로그아웃 · 건강정보 동의 철회 · 탈퇴 (S10 확인 대화상자) ───────────────────────────────
 *
 * 연동 때 바꾼다: 로그아웃 `POST /api/v1/auth/logout`(백엔드 #57 — refresh 세션 폐기 + access token 블랙리스트),
 * 건강정보 동의 철회(#59 — 원시 보고 파기 요청 + refresh 세션 전부 폐기), 탈퇴 `POST /api/v1/members/me/withdraw`(#59).
 * 요청 시간 제한 · 네트워크 실패는 API 계층이 맡고, 실패하면 Promise 를 거부한다 — 화면은 대화상자 안에서 다시 시도하라고 알린다.
 *
 * 성공하면 화면과 무관하게 여기서 목 세션을 바꾼다(응답 전에 화면이 닫혀도 서버에는 결과가 남는다):
 * 로그아웃 · 탈퇴 → `guest`(프로필도 지운다), 동의 철회 → `member-no-consent`(동의한 회원 세션일 때만 — 비회원 세션은 그대로).
 *
 * 목에서 실패를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다). 프로필 이메일로 가린다 — 그 이메일로 이메일 로그인한 뒤 연다:
 * - 로그아웃: `logout-fail@example.com` → 거부(세션은 그대로)
 * - 동의 철회: `consent-withdraw-fail@example.com` → 거부
 * - 탈퇴: `withdraw-fail@example.com` → 거부
 */

export const MOCK_LOGOUT_FAIL_EMAIL = 'logout-fail@example.com'
export const MOCK_CONSENT_WITHDRAW_FAIL_EMAIL = 'consent-withdraw-fail@example.com'
export const MOCK_WITHDRAW_FAIL_EMAIL = 'withdraw-fail@example.com'

function rejectIfProfileEmail(email: string, what: string): Promise<void> | null {
  return mockProfile?.email === email ? Promise.reject(new Error(`mock ${what} failure`)) : null
}

/** 이 기기에서 로그아웃한다 */
export function logout(): Promise<void> {
  const failure = rejectIfProfileEmail(MOCK_LOGOUT_FAIL_EMAIL, 'logout')
  if (failure) return failure
  setMockSession('guest')
  return Promise.resolve()
}

/**
 * 건강정보(민감정보) 처리 동의를 철회한다. 서버가 보낸 보고를 모두 지운다(파기 요청).
 *
 * 목은 `member-no-consent` 로 둔다. 백엔드 #59 는 철회와 함께 refresh 세션을 모두 폐기하고 요청 기기의 access token 도
 * 막는다(대화상자 문구 "모든 기기에서 로그아웃돼요") — 연동 때 철회 뒤 세션이 남는지 백엔드와 맞춘다(SCREENS.md 연동 요구사항).
 */
export function withdrawHealthConsent(): Promise<void> {
  const failure = rejectIfProfileEmail(MOCK_CONSENT_WITHDRAW_FAIL_EMAIL, 'consent withdraw')
  if (failure) return failure
  // 동의한 회원 세션만 바꾼다. `?mock-auth=member` 덮어쓰기로 비회원 세션에서 철회해도 회원이 되지 않는다
  if (mockSession === 'member') setMockSession('member-no-consent')
  return Promise.resolve()
}

/** 회원 탈퇴. 보낸 보고는 바로 지우고 계정은 30일 뒤 지운다(서버). 탈퇴 사유는 받지 않는다 */
export function withdrawMembership(): Promise<void> {
  const failure = rejectIfProfileEmail(MOCK_WITHDRAW_FAIL_EMAIL, 'withdraw')
  if (failure) return failure
  setMockSession('guest')
  return Promise.resolve()
}

/* ── 비밀번호 재설정 (S13-6) ────────────────────────────────────────────────────────
 *
 * **백엔드 #58 은 구현 전이고 경로만 정해져 있다**: `POST /api/v1/auth/password/reset/send-code` · `POST /api/v1/auth/password/reset`.
 * 시안이 코드 확인 단계를 따로 두므로 프론트는 다음 모양을 가정한다(docs/api-contract-draft.md "비밀번호 재설정" 제안 1):
 * 코드 받기 → 코드 확인(**일회용 재설정 토큰을 응답 본문으로 받는다**) → 토큰 + 새 비밀번호로 재설정(토큰을 소비한다).
 *
 * 가입처럼 서버가 이메일별 인증 표시를 들고 재설정이 이메일 + 새 비밀번호만 받는 방식은 **쓰지 않는다** — 인증 표시가
 * 살아 있는 동안 코드를 모르고 이메일만 아는 사람이 비밀번호를 바꿀 수 있다. 재설정 권한은 코드를 맞힌 쪽이 받은 토큰에 묶는다.
 *
 * - 코드 받기 · 확인은 **가입 여부와 무관하게 같은 응답**이라고 가정한다(계정 열거 방지 — 가입 인증과 같다). 화면은 늘 코드 단계로 간다
 * - 코드 한도 · 수명은 가입 인증과 같다(6자리 · 5분 · 다시 받기 60초 · 오입력 5회, 위 상수를 같이 쓴다)
 * - 남은 시도 횟수는 가입 인증처럼 서버가 주지 않는다고 보고, 연동 때 이 모듈이 이메일별 실패 수를 세어 `remainingAttempts` 를
 *   채운다. 코드를 다시 받으면(sent) 0 으로 되돌린다
 * - 재설정 토큰은 한 번만 쓰고 수명은 `PASSWORD_RESET_TOKEN_TTL_SECONDS`(15분)다. 화면은 Provider 메모리에만 들고
 *   주소 · 로그 · 브라우저 저장소에 남기지 않는다. 연동 때 요청 본문으로만 보낸다
 * - 새 비밀번호도 로그나 저장소에 남기지 않는다. 요청 시간 제한 · 네트워크 실패는 API 계층이 맡고 실패하면 Promise 를 거부한다
 *
 * 목에서 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다). 코드는 가입 인증과 같은 입력을 쓰고 저장소는 따로다:
 * - 코드 받기: 이메일 `limit@example.com` → `limit`, 그 밖 → 보냄
 * - 코드 확인: `999999` → `locked`, `000000` → `wrong`(5번째에 `locked`), 5분이 지났거나 보낸 코드가 없으면 `expired`, 그 밖 6자리 → 토큰
 * - 재설정: 이메일 `verify-expired@example.com` 로 받은 토큰은 늘 `verification-expired`, `reset-fail@example.com` 로 받은 토큰이면
 *   응답을 받지 못한다(거부, 토큰은 남는다). 그 밖에도 토큰이 없거나 15분이 지났거나 이미 썼으면 `verification-expired` 다
 */

/** 재설정 토큰 수명(초). 코드를 맞힌 뒤 새 비밀번호를 정할 때까지 쓸 수 있는 시간 */
export const PASSWORD_RESET_TOKEN_TTL_SECONDS = 900

export const MOCK_RESET_FAIL_EMAIL = 'reset-fail@example.com'

export type PasswordResetVerifyResult =
  /** 인증 완료. 재설정에 한 번 쓸 토큰을 준다 */
  { status: 'ok'; resetToken: string } | VerifyCodeFailure

const passwordResetCodes = createMockCodeStore()
/** 목 서버가 내준 재설정 토큰. 토큰 → 받은 이메일 · 내준 시각 */
const mockResetTokens = new Map<string, { email: string; issuedAt: number }>()
let mockResetTokenSeq = 0

export function sendPasswordResetCode(email: string): Promise<SendCodeResult> {
  return Promise.resolve(passwordResetCodes.send(email))
}

export function verifyPasswordResetCode(
  email: string,
  code: string,
): Promise<PasswordResetVerifyResult> {
  const result = passwordResetCodes.verify(email, code)
  if (result.status !== 'ok') return Promise.resolve(result)
  // 목 토큰은 맞히기 쉬운 순번이다. 서버는 추측할 수 없는 값을 준다
  mockResetTokenSeq += 1
  const resetToken = `mock-reset-${mockResetTokenSeq}`
  mockResetTokens.set(resetToken, { email: normalizeEmail(email), issuedAt: Date.now() })
  return Promise.resolve({ status: 'ok', resetToken })
}

/** `verification-expired` 는 재설정 토큰이 없거나 15분이 지났거나 이미 썼다는 뜻이다. 이메일 단계부터 다시 한다 */
export type PasswordResetResult = { status: 'ok' } | { status: 'verification-expired' }

export function resetPassword(
  resetToken: string,
  newPassword: string,
): Promise<PasswordResetResult> {
  // 목은 새 비밀번호를 쓰지 않는다. 규칙(8~20자 · 영문 · 숫자 · 공백 금지)은 서버가 다시 검사한다
  void newPassword
  const issued = mockResetTokens.get(resetToken)
  if (!issued) return Promise.resolve({ status: 'verification-expired' })
  // 응답을 받지 못한 경우다. 서버가 바꿨는지 모르므로 토큰은 남겨 다시 누를 수 있게 한다
  if (issued.email === MOCK_RESET_FAIL_EMAIL) {
    return Promise.reject(new Error('mock password reset failure'))
  }
  if (
    issued.email === MOCK_VERIFY_EXPIRED_EMAIL ||
    Date.now() - issued.issuedAt > PASSWORD_RESET_TOKEN_TTL_SECONDS * 1000
  ) {
    mockResetTokens.delete(resetToken)
    return Promise.resolve({ status: 'verification-expired' })
  }
  // 서버처럼 쓴 토큰은 지운다 — 같은 토큰으로 두 번 바꾸지 못한다
  mockResetTokens.delete(resetToken)
  return Promise.resolve({ status: 'ok' })
}
