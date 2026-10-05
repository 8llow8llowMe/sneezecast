import { ABOLISHED_DISTRICT_EXAMPLE } from '@/features/region/mock'
import type { District } from '@/features/region/types'
import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import { type ApiErrorKind, classifyApiError } from '@/lib/api/error-kind'
import type { DataSource } from '@/lib/data-source'
import {
  type AuthToken,
  broadcastMemberRegionChanged,
  clearSession,
  getSessionSnapshot,
  setSession,
} from '@/lib/session/session-store'
import { clearSessionExpiring } from '@/lib/session-expiry'

import { kakaoTicketLost } from './kakao-ticket'
import type { Consent, ConsentType } from './legal'
import type { KakaoFailReason } from './login-notice'
import { type MyInfo, type MyRegion, patchMyNickname, putMyRegion } from './member-client'
import {
  endsMemberSession,
  getMemberInfoSnapshot,
  reloadMemberInfo,
  setMemberInfo,
  setMemberRegion,
} from './member-info'

/* ── 실데이터 연동 (#163) ──────────────────────────────────────────────────────────────
 *
 * 이메일 인증 · 가입 · 이메일 로그인 · 로그아웃(`sendEmailCode` · `verifyEmailCode` · `signup` 이메일 갈래 · `loginWithEmail` ·
 * `logout`)과 내 동네 저장(`saveRegion`, #164), 비밀번호 재설정 · 변경과 로그인한 기기(`sendPasswordResetCode` ·
 * `verifyPasswordResetCode` · `resetPassword` · `changePassword` · `listSessions` · `revokeSession` · `revokeOtherSessions`, #166),
 * 카카오 가입(`signup` 카카오 갈래, #167 — 카카오 로그인 · 연결은 `kakao-client.ts`), 닉네임 바꾸기(`updateNickname`, #192)는 마지막 인자로 데이터 출처(`DataSource`)를
 * 받는다. `api` 면 auth · 회원 API(docs/api-contract-draft.md "인증" · "회원")를 부르고, `mock` 이면 아래 목 동작 그대로다. 출처는 부르는 화면이 `useDataSource()` 로 읽어 넘긴다 — 이 모듈은 쿠키를 읽지 않는다
 * (docs/conventions.md "데이터 출처", `features/region/region-client.ts` 와 같다). 그 밖의 함수는 아직 출처와 무관하게 목이다.
 *
 * - 인증이 필요 없는 요청(코드 받기 · 코드 확인 · 가입 · 카카오 가입 · 로그인 · 비밀번호 재설정)은 `auth: false` 로 부른다 — 만료된 access 를
 *   실어 게이트웨이가 `SECURITY_002` 로 거절하는 일이 없게 한다. 로그아웃 · 비밀번호 변경 · 기기는 access 를 싣는다(API 계층이
 *   만료 전 재발급을 맡는다)
 * - 서버 오류 코드 중 화면 상태가 있는 것만 결과로 옮긴다. 일시 장애(`UNAVAILABLE` · 503) · 분류 밖 오류는 그대로 거부한다 —
 *   화면은 거부를 "잠시 뒤 다시 시도" 안내로 받는다(목의 응답 없음 재현과 같은 길)
 * - 비밀번호 · 인증 코드는 요청 본문으로만 보내고 로그 · 저장소 · 주소에 남기지 않는다. 토큰은 세션 저장소에만 넘긴다
 */

/** 서버 오류 코드. `ApiError` 가 아니면(호출한 쪽의 취소 · 프로그램 오류) null */
function errorCodeOf(error: unknown): string | null {
  return error instanceof ApiError ? error.code : null
}

/** 오류 코드 → 결과. 표에 없으면 null(거부할 오류다) */
function mapError<T>(error: unknown, table: Readonly<Record<string, T>>): T | null {
  const code = errorCodeOf(error)
  return code !== null && Object.hasOwn(table, code) ? (table[code] ?? null) : null
}

const SEND_CODE_PATH = '/api/v1/auth/email/send-code'
const VERIFY_CODE_PATH = '/api/v1/auth/email/verify-code'
const SIGNUP_PATH = '/api/v1/auth/signup'
const KAKAO_SIGNUP_PATH = '/api/v1/auth/kakao/signup'
const LOGIN_PATH = '/api/v1/auth/login'
const LOGOUT_PATH = '/api/v1/auth/logout'
const SESSIONS_PATH = '/api/v1/auth/sessions'
const PASSWORD_RESET_SEND_CODE_PATH = '/api/v1/auth/password/reset/send-code'
const PASSWORD_RESET_VERIFY_CODE_PATH = '/api/v1/auth/password/reset/verify-code'
const PASSWORD_RESET_PATH = '/api/v1/auth/password/reset'
const CHANGE_PASSWORD_PATH = '/api/v1/members/me/password'

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
 * 값을 바꾸는 곳은 이 모듈의 함수뿐이다: 카카오 가입 성공(`signup` kind kakao) · 이메일 로그인 성공(`loginWithEmail`) ·
 * 카카오 로그인 · 연결 목(`kakao-client.ts` 가 `signInMockKakao` 로 부름)은 `member-no-consent`, 건강정보 동의 성공
 * (`agreeHealthConsent`)은 `member`, 동의 철회 · 로그아웃 · 탈퇴 · 비밀번호 재설정 성공 (`withdrawHealthConsent` · `logout` · `withdrawMembership` · `resetPassword`)은 `guest`. 화면 코드는 고치지 않는다.
 * 모듈 메모리에만 두어 새로고침하면 `guest` 로 돌아간다 — 브라우저 저장소에 남기지 않는다.
 *
 * 내 정보(S10)가 보일 프로필(`MockProfile`)도 같은 세션에 둔다. 로그인 · 가입할 때 채우고 로그아웃 · 탈퇴하면 지운다.
 * 내 동네 저장(`saveRegion`)에 성공하면 동네를 바꾸고 폐지 표시를 끄고, 약관 재동의(`agreeTermsReconsent`)에 성공하면 재동의 표시를 끈다.
 * 닉네임 바꾸기(`updateNickname`)에 성공하면 닉네임을 바꾼다.
 * 연동 때 `GET /api/v1/members/me`(백엔드 #58) 응답으로 바꾼다.
 */
export type MockAuthState = 'guest' | 'member-no-consent' | 'member'

export const MOCK_AUTH_STATES: readonly MockAuthState[] = ['guest', 'member-no-consent', 'member']

/**
 * 내 정보에 보일 회원 프로필 (목). 로그인 방법(`provider`) · 이메일 · 닉네임 · 비밀번호가 있는지만 둔다 —
 * 이름 · 연락처 · 주소는 받지 않는다. 연동 때 `GET /api/v1/members/me` 응답으로 바꾼다. 카카오 회원의 이메일 · 닉네임과
 * 이메일 로그인의 닉네임은 목이 모르므로 시안의 예시 값(`EXAMPLE_PROFILES`)을 쓴다.
 *
 * `hasPassword` 는 이메일 · 비밀번호로도 로그인할 수 있는지다(`GET /me` 의 같은 이름). 이메일 가입은 늘 true, 카카오 가입은 false 다.
 * false 면 내 정보의 비밀번호 행 · `/me/password` 를 숨긴다 — 비밀번호 최초 설정 API 는 백엔드 #61 에서 없앴다(#166).
 *
 * 내 동네와 다시 들어올 때 거칠 화면의 조건(`regionAbolished` · `termsReconsentRequired`)도 둔다. 홈 · 내 정보가 이 값으로
 * 약관 재동의(Setup-3-reconsent) · 동네 다시 고르기(Setup-1-reselect)로 먼저 보낸다(`required-steps.ts`).
 * 연동 때 이 두 값을 어느 응답이 주는지 백엔드(#59 · #60)와 정한다(docs/design/SCREENS.md 연동 요구사항).
 */
export type MockProfile = {
  provider: 'email' | 'kakao'
  email: string
  nickname: string
  hasPassword: boolean
  /** 내 동네(행정동). 목이 모르면(가입 없이 이메일 로그인) null 이다 — 그래도 다시 고르게 하지는 않는다 */
  region: MemberRegion | null
  /** 내 동네가 행정구역 개편으로 폐지됐는지. true 면 `region` 은 옛 동네이고, 다시 골라야 보고를 셀 수 있다 */
  regionAbolished: boolean
  /** 필수 약관(서비스 이용약관)이 개정돼 다시 동의해야 하는지 */
  termsReconsentRequired: boolean
}

/** 회원의 내 동네. 옛 동네를 알릴 때 이름을 쓴다 */
export type MemberRegion = Pick<District, 'code' | 'name'>

/** 동네 · 재동의 조건이 없는 프로필 값. 로그인 · 가입이 처음 만드는 프로필이 쓴다 */
const NO_CONDITIONS = {
  region: null,
  regionAbolished: false,
  termsReconsentRequired: false,
} as const satisfies Pick<MockProfile, 'region' | 'regionAbolished' | 'termsReconsentRequired'>

/** 시안(Settings · Settings-kakao)의 예시 값. 실제 값은 `GET /me` 에서 받는다 */
export const EXAMPLE_PROFILES: Readonly<Record<MockProfile['provider'], MockProfile>> = {
  email: {
    provider: 'email',
    email: 'dong@example.com',
    nickname: '동네지기',
    hasPassword: true,
    ...NO_CONDITIONS,
  },
  kakao: {
    provider: 'kakao',
    email: 'dong@kakao.com',
    nickname: '동네지기',
    hasPassword: false,
    ...NO_CONDITIONS,
  },
}

let mockSession: MockAuthState = 'guest'
let mockProfile: MockProfile | null = null
/** 목 서버의 로그인한 기기 목록. 처음 읽을 때 예시 목록으로 채운다(`listSessions`) */
let mockDeviceSessions: DeviceSession[] | null = null
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

/**
 * 세션을 바꾼다. `profile` 을 넘기지 않으면 프로필은 그대로 두고, `guest` 가 되면 늘 지운다.
 * `guest` 가 되면 목 서버의 로그인한 기기 목록도 지운다 — 다음 로그인은 예시 목록부터 다시 시작한다
 */
function setMockSession(next: MockAuthState, profile?: MockProfile) {
  if (next === 'guest') mockDeviceSessions = null
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
  return (
    a.provider === b.provider &&
    a.email === b.email &&
    a.nickname === b.nickname &&
    a.hasPassword === b.hasPassword &&
    a.region?.code === b.region?.code &&
    a.region?.name === b.region?.name &&
    a.regionAbolished === b.regionAbolished &&
    a.termsReconsentRequired === b.termsReconsentRequired
  )
}

/** 테스트에서 목 세션을 처음(`guest`)으로 되돌린다. 화면 코드는 부르지 않는다 */
export function resetMockSession() {
  setMockSession('guest')
  mockNicknames.clear()
}

/**
 * 이메일 로그인. 실데이터는 `POST /api/v1/auth/login {email, password}`(`auth: false`)이고, 성공하면 응답(`AuthToken`)을
 * 그대로 세션 저장소에 넣는다(`setSession` — 이 탭이 회원이 되고 다른 탭에도 알린다). 목 세션은 건드리지 않는다.
 * 응답 전에 화면을 떠나도 세션은 넣는다(서버에는 이미 로그인 세션이 생겼다). 늦은 응답으로 이동하지 않는 것은 화면 몫이다
 * (`src/lib/use-active-ref.ts`).
 *
 * 오류 코드 → 결과 (backend/docs/modules.md "화면 계약"):
 * - `AUTH_011`(이메일 · 비밀번호 불일치 · 미가입) · `MEMBER_002`(탈퇴 — 미가입과 같게 보인다) → `wrong`.
 *   검증 오류 `AUTH_102` · `103`(이메일 길이 · 형식) · `113`(비밀번호 100자 초과)도 맞을 수 없는 입력이라 `wrong` 이다
 * - `AUTH_012`(이메일 잠금, 10분 고정) → `locked`, `AUTH_013`(IP 상한, 남은 시간 모름) → `limited`
 * - `MEMBER_003`(정지 — 비밀번호가 맞을 때만 온다) → `suspended`
 * - 그 밖(`AUTH_017` · 일시 장애 등)은 거부한다 — 화면은 "로그인하지 못했어요" 로 알린다
 *
 * 비밀번호는 어디에도 남기지 않는다(로그 · 저장소 · 주소 금지). 요청 자체 취소(AbortController)는 아직 붙이지 않았다.
 *
 * 목에서 오류 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다):
 * - 이메일 `locked@example.com` → `locked` (로그인 시도가 많아 잠시 막힘)
 * - 이메일 `limit@example.com` → `limited` (이 기기에서 요청이 많아 잠시 막힘 — 코드 받기 제한과 같은 입력)
 * - 이메일 `suspended@example.com` → `suspended` (이용 정지)
 * - 비밀번호 `wrong` → `wrong` (이메일 또는 비밀번호가 맞지 않음)
 * - 그 밖 → 성공. 다시 들어올 때 거칠 화면의 조건을 서버가 알려 주는 흉내로, 다음 이메일은 프로필에 조건을 켠다:
 *   `reconsent@example.com` · `reconsent-fail@example.com` → 약관 재동의, `reselect@example.com` · `reselect-fail@example.com`
 *   → 동네 폐지(옛 동네는 시안 예시 `○○1동`). `-fail` 은 그 화면의 저장이 실패한다(아래 재동의 · 동네 저장)
 */

export const MOCK_LOCKED_EMAIL = 'locked@example.com'
export const MOCK_SUSPENDED_EMAIL = 'suspended@example.com'
export const MOCK_WRONG_PASSWORD = 'wrong'

export type EmailLoginResult =
  | { status: 'ok' }
  | { status: 'wrong' }
  /** 이 이메일의 로그인 시도가 많아 잠금(10분) */
  | { status: 'locked' }
  /** 이 기기(IP)의 로그인 시도가 많아 잠시 막힘. 남은 시간은 모른다 */
  | { status: 'limited' }
  /** 이용이 정지된 계정 */
  | { status: 'suspended' }

type LoginFailure = Exclude<EmailLoginResult, { status: 'ok' }>['status']

const LOGIN_FAILURES: Readonly<Record<string, LoginFailure>> = {
  AUTH_011: 'wrong',
  MEMBER_002: 'wrong',
  AUTH_102: 'wrong',
  AUTH_103: 'wrong',
  AUTH_113: 'wrong',
  AUTH_012: 'locked',
  AUTH_013: 'limited',
  MEMBER_003: 'suspended',
}

export async function loginWithEmail(
  email: string,
  password: string,
  source: DataSource,
): Promise<EmailLoginResult> {
  if (source === 'api') {
    let token: AuthToken
    try {
      token = await apiRequest<AuthToken>(LOGIN_PATH, {
        method: 'POST',
        body: { email, password },
        auth: false,
      })
    } catch (error) {
      const failure = mapError(error, LOGIN_FAILURES)
      if (failure) return { status: failure }
      throw error
    }
    setSession(token)
    return { status: 'ok' }
  }
  const key = normalizeEmail(email)
  if (key === MOCK_LOCKED_EMAIL) return { status: 'locked' }
  if (key === MOCK_LIMIT_EMAIL) return { status: 'limited' }
  if (key === MOCK_SUSPENDED_EMAIL) return { status: 'suspended' }
  if (password === MOCK_WRONG_PASSWORD) return { status: 'wrong' }
  // 목은 동의 여부를 모른다. 실데이터는 로그인 응답(`reportWritable`)이 동의 상태를 알려 준다
  setMockSession('member-no-consent', {
    provider: 'email',
    email: key,
    nickname: mockNicknames.get(key) ?? EXAMPLE_PROFILES.email.nickname,
    hasPassword: true,
    ...conditionsFor(key),
  })
  return { status: 'ok' }
}

function conditionsFor(
  email: string,
): Pick<MockProfile, 'region' | 'regionAbolished' | 'termsReconsentRequired'> {
  const reconsent = email === MOCK_RECONSENT_EMAIL || email === MOCK_RECONSENT_FAIL_EMAIL
  const reselect = email === MOCK_RESELECT_EMAIL || email === MOCK_RESELECT_FAIL_EMAIL
  return {
    region: reselect
      ? { code: ABOLISHED_DISTRICT_EXAMPLE.code, name: ABOLISHED_DISTRICT_EXAMPLE.name }
      : null,
    regionAbolished: reselect,
    termsReconsentRequired: reconsent,
  }
}

/**
 * 카카오 로그인 · 연결의 목 세션 (`kakao-client.ts` 의 목 갈래만 부른다 — 화면 코드는 부르지 않는다). 목 세션은 이 모듈이 들고 있어
 * 바꾸는 함수를 여기 둔다.
 * - `kakao`: 카카오로 가입한 회원의 로그인(`LOGGED_IN`). 비밀번호가 없다 — 프로필은 시안 예시 값
 * - `linked`: 이메일 계정에 카카오 로그인을 연결함(`POST /kakao/link`). 로그인 방법은 카카오, 비밀번호는 그대로다
 */
export function signInMockKakao(kind: 'kakao' | 'linked'): void {
  setMockSession(
    'member-no-consent',
    kind === 'kakao'
      ? EXAMPLE_PROFILES.kakao
      : { ...EXAMPLE_PROFILES.email, provider: 'kakao', hasPassword: true },
  )
}

/* ── 이메일 가입 인증 (S13-2 · S13-3) ───────────────────────────────────────────────
 *
 * 실데이터: `POST /api/v1/auth/email/send-code {email}` · `POST /api/v1/auth/email/verify-code {email, code}`(백엔드 #56,
 * 둘 다 `auth: false`, 응답 본문 없음). 화면 계약(backend/docs/modules.md "화면 계약"):
 * - 코드 받기는 **가입 여부와 무관하게 같은 응답**이다(계정 열거 방지). 이미 가입된 이메일이면 서버가 코드 대신
 *   안내 메일을 보낸다 — 화면은 늘 코드 단계로 가고 중립 문구로 알린다
 * - 코드 확인은 **토큰을 주지 않는다.** 인증 완료 표시는 서버가 이메일별로 30분 들고 있다가 가입 요청 때 확인한다
 * - 발송 제한(`AUTH_001` 쿨다운 · `AUTH_002` IP 상한)은 남은 시간을 주지 않는다 — 둘 다 `limit` 이고 화면은 시간을 못 박지 않는다
 * - 남은 시도 횟수도 주지 않는다. 틀리면 `AUTH_003`, 5번째로 틀리면 `AUTH_005`(잠김)이고 서버가 코드를 지워
 *   그다음 확인은 `AUTH_004`(만료)다. 코드를 다시 받으면 서버의 실패 수가 0 이 된다.
 *   그래서 이 모듈이 이메일별 실패 수를 세어(`signupCodeFailures`) `remainingAttempts`(= `CODE_MAX_ATTEMPTS` - 실패 수, 1 이상)를
 *   채우고, 코드를 받으면(`sent`) · 인증을 마치면 · 잠기거나 만료되면 그 이메일의 실패 수를 0 으로 되돌린다.
 *   새로고침하면 세던 수가 사라져 남은 시도가 실제보다 많게 보일 수 있다 — 잠금은 서버가 정하므로 문구만 어긋난다
 * - 코드 확인 결과: `AUTH_003` → `wrong`, `AUTH_004` → `expired`, `AUTH_005`(시도 초과) · `AUTH_010`(IP 상한) → `locked`.
 *   IP 상한은 코드 단계에 따로 상태가 없어 "시도 횟수를 넘겼어요 · 이메일부터 다시" 로 보인다
 * - 그 밖(`AUTH_006` 저장소 장애 · 일시 장애 · 검증 오류)은 거부한다 — 화면은 "보내지 못했어요 / 확인하지 못했어요" 로 알린다
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

/** 실데이터의 이메일별 코드 확인 실패 수(서버가 남은 시도를 주지 않아 화면이 센다). 메모리에만 둔다 */
const signupCodeFailures = new Map<string, number>()

const SEND_CODE_FAILURES: Readonly<Record<string, 'limit'>> = {
  AUTH_001: 'limit',
  AUTH_002: 'limit',
}

export async function sendEmailCode(email: string, source: DataSource): Promise<SendCodeResult> {
  if (source === 'mock') return signupCodes.send(email)
  try {
    await apiRequest<null>(SEND_CODE_PATH, { method: 'POST', body: { email }, auth: false })
  } catch (error) {
    const failure = mapError(error, SEND_CODE_FAILURES)
    if (failure) return { status: failure }
    throw error
  }
  // 새 코드를 받으면 서버의 실패 수도 0 이다
  signupCodeFailures.delete(normalizeEmail(email))
  return { status: 'sent' }
}

export async function verifyEmailCode(
  email: string,
  code: string,
  source: DataSource,
): Promise<VerifyCodeResult> {
  if (source === 'mock') {
    const result = signupCodes.verify(email, code)
    if (result.status === 'ok') signupVerifiedAt.set(normalizeEmail(email), Date.now())
    return result
  }
  const key = normalizeEmail(email)
  try {
    await apiRequest<null>(VERIFY_CODE_PATH, {
      method: 'POST',
      body: { email, code },
      auth: false,
    })
  } catch (error) {
    const failure = verifyFailureOf(error, key, signupCodeFailures)
    if (failure) return failure
    throw error
  }
  signupCodeFailures.delete(key)
  return { status: 'ok' }
}

/**
 * 코드 확인 오류 → 결과. 표에 없으면 null(거부할 오류). 틀림이면 그 흐름의 실패 수(`failures` — 가입 · 재설정이 따로 둔다)를
 * 하나 올려 남은 시도를 채운다. 가입 인증과 비밀번호 재설정 인증은 서버가 같은 처리기 · 같은 실패 코드를 쓴다(backend/docs/modules.md)
 */
function verifyFailureOf(
  error: unknown,
  key: string,
  failures: Map<string, number>,
): VerifyCodeFailure | null {
  switch (errorCodeOf(error)) {
    case 'AUTH_003': {
      const count = (failures.get(key) ?? 0) + 1
      failures.set(key, count)
      // 서버는 5번째 실패를 AUTH_005 로 준다. 세던 수가 어긋나도(새로고침 · 다른 탭) 0번으로 보이지 않게 1 이상으로 둔다
      return { status: 'wrong', remainingAttempts: Math.max(1, CODE_MAX_ATTEMPTS - count) }
    }
    case 'AUTH_004':
      // 코드가 만료됐거나 없다. 다시 받으면 서버의 실패 수도 0 이다
      failures.delete(key)
      return { status: 'expired' }
    case 'AUTH_005':
      // 서버가 잠그면서 코드를 지웠다
      failures.delete(key)
      return { status: 'locked' }
    case 'AUTH_010':
      // IP 상한이다. 이 이메일의 코드 · 실패 수는 서버에 그대로라 세던 수도 둔다
      return { status: 'locked' }
    default:
      return null
  }
}

/** 가입 인증 표시가 살아 있는지. 재현용 만료 이메일 · 인증하지 않음 · 마친 지 30분이 지남이면 false 다 */
function hasSignupVerification(key: string): boolean {
  const at = signupVerifiedAt.get(key)
  return (
    key !== MOCK_VERIFY_EXPIRED_EMAIL &&
    at !== undefined &&
    Date.now() - at <= EMAIL_VERIFICATION_TTL_SECONDS * 1000
  )
}

/** 가입 인증 표시를 쓴다. 살아 있지 않으면 false 다. 서버처럼 쓴 인증 표시는 지운다 */
function consumeSignupVerification(email: string): boolean {
  const key = normalizeEmail(email)
  if (!hasSignupVerification(key)) return false
  signupVerifiedAt.delete(key)
  return true
}

/* ── 가입 · 내 동네 · 건강정보 동의 (S02-3 · S02-4) ─────────────────────────────────
 *
 * 이메일 가입은 실데이터에서 `POST /api/v1/auth/signup`(백엔드 #56, `auth: false`)이다. 내 동네 저장은 `PUT /api/v1/members/me/region`
 * (#60, 아래 `saveRegion`)이다. 카카오 가입은 `POST /api/v1/auth/kakao/signup`(#61 · #167, `auth: false`, 가입표 쿠키)이다.
 * 건강정보 동의(#59)는 아직 출처와 무관하게 목이다.
 * 이메일 가입 응답에는 토큰이 없다 — 이어서 `loginWithEmail`(#57)로 로그인한 뒤 동네를 저장한다.
 * 카카오 가입 응답은 로그인 응답(`AuthToken`)이라 바로 세션을 넣고 동네를 저장한다.
 *
 * 가입 오류 코드 → 결과: `AUTH_007`(인증 표시 없음 · 30분 지남) → `verification-expired`, `MEMBER_001`(가입된 이메일, 409) →
 * `email-taken`. 그 밖(검증 오류 · 일시 장애)은 거부한다 — 화면은 "가입하지 못했어요" 로 알린다.
 * `email-taken` 은 메일함 주인임을 인증한 뒤에만 오므로 가입 여부를 알려도 계정 열거가 되지 않는다(미끼 코드를 맞혀도 막힌다).
 *
 * 목에서 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다):
 * - 가입: 이메일 `signup-fail@example.com` 이면 응답을 받지 못한다(거부)
 * - 가입: 이메일 `verify-expired@example.com` 이면 늘 `verification-expired`(인증 30분이 지남, `AUTH_007`).
 *   그 밖의 이메일도 인증을 마치지 않았거나 마친 지 30분이 지났으면 `verification-expired` 다
 * - 가입: 이메일 `taken@example.com` 이면 인증을 마쳤어도 `email-taken`(서버처럼 인증 표시는 남는다)
 */

export const MOCK_SIGNUP_FAIL_EMAIL = 'signup-fail@example.com'
export const MOCK_VERIFY_EXPIRED_EMAIL = 'verify-expired@example.com'
export const MOCK_EMAIL_TAKEN_EMAIL = 'taken@example.com'

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

/**
 * - `verification-expired`: 이메일 인증 표시가 없거나 30분이 지났다(`AUTH_007`). 이메일 단계부터 다시 한다
 * - `email-taken`: 이미 가입된 이메일이다(`MEMBER_001` — 탈퇴 회원도 행이 파기될 때까지 이메일을 점유한다). 로그인으로 안내한다
 * - `kakao-restart`(카카오 가입만): 가입표가 없거나 30분이 지났다(`AUTH_025`, 사유 `expired`) · 그사이 같은 이메일로 가입됐다
 *   (`MEMBER_001`, 사유 없음 — 다시 카카오 로그인하면 계정 연결 확인으로 간다) · 그 밖의 업무 오류로 가입표를 잃었다(사유 없음).
 *   카카오 로그인부터 다시 한다
 */
export type SignupResult =
  | { status: 'ok' }
  | { status: 'verification-expired' }
  | { status: 'email-taken' }
  | { status: 'kakao-restart'; reason: KakaoFailReason | null }

const SIGNUP_FAILURES: Readonly<Record<string, 'verification-expired' | 'email-taken'>> = {
  AUTH_007: 'verification-expired',
  MEMBER_001: 'email-taken',
}

/** 동의 목록에 그 항목이 있는지. 가입 API 는 항목별 boolean 으로 받는다(문서 버전은 서버의 `legal.*-version` 을 쓴다) */
function agreed(consents: readonly Consent[], type: ConsentType): boolean {
  return consents.some((consent) => consent.type === type)
}

/**
 * 가입. 카카오 가입(`kind: 'kakao'`)의 실데이터는 `signupWithKakao` 다(아래).
 * 이메일 가입의 실데이터 본문은 `{ email, password, nickname, termsAgreed, privacyAgreed, ageOver19Confirmed,
 * sensitiveHealthInfoAgreed }` 이고 동의 값은 `consents` 에서 만든다. 건강정보 동의는 S02-4 에서 따로 보내므로 가입 화면은
 * 넣지 않는다(false). 비밀번호는 본문으로만 보낸다.
 */
export async function signup(request: SignupRequest, source: DataSource): Promise<SignupResult> {
  if (request.kind === 'kakao') {
    if (source === 'api') return signupWithKakao(request.consents)
    // 목 카카오 가입은 바로 회원이 된다(실데이터 응답이 로그인 응답인 것과 같다). 이메일 가입은 이어지는 loginWithEmail 이 회원으로 만든다.
    // 카카오가 주는 이메일 · 닉네임은 목이 몰라 예시 값을 쓴다
    setMockSession('member-no-consent', EXAMPLE_PROFILES.kakao)
    return { status: 'ok' }
  }
  if (source === 'api') {
    const { email, password, nickname, consents } = request
    try {
      await apiRequest<null>(SIGNUP_PATH, {
        method: 'POST',
        body: {
          email,
          password,
          nickname,
          termsAgreed: agreed(consents, 'TERMS_OF_SERVICE'),
          privacyAgreed: agreed(consents, 'PRIVACY_POLICY'),
          ageOver19Confirmed: agreed(consents, 'AGE_OVER_19'),
          sensitiveHealthInfoAgreed: agreed(consents, 'SENSITIVE_HEALTH_INFO'),
        },
        auth: false,
      })
    } catch (error) {
      const failure = mapError(error, SIGNUP_FAILURES)
      if (failure) return { status: failure }
      throw error
    }
    return { status: 'ok' }
  }
  const key = normalizeEmail(request.email)
  if (key === MOCK_SIGNUP_FAIL_EMAIL) throw new Error('mock signup failure')
  // 서버처럼 인증을 먼저 보고, 가입된 이메일이면 인증 표시를 쓰지 않는다(커밋 뒤에 쓴다)
  if (key === MOCK_EMAIL_TAKEN_EMAIL && hasSignupVerification(key)) {
    return { status: 'email-taken' }
  }
  // 서버처럼 가입에 쓴 인증 표시는 지운다
  const ok = consumeSignupVerification(request.email)
  if (ok) mockNicknames.set(key, request.nickname)
  return { status: ok ? 'ok' : 'verification-expired' }
}

/**
 * 카카오 가입 (실데이터). `POST /api/v1/auth/kakao/signup {termsAgreed, privacyAgreed, ageOver19Confirmed}` 를 `auth: false` 로 보낸다 —
 * 가입표는 카카오 로그인(`SIGNUP_REQUIRED`)이 심은 HttpOnly 쿠키라 브라우저가 싣는다(`credentials: 'include'`). 이메일 · 닉네임은
 * 서버가 가입표로 들고 있어 보내지 않는다. 응답은 로그인 응답(`AuthToken`, refresh 는 쿠키)이라 그대로 `setSession` 한다 —
 * 이어지는 동네 저장(`saveRegion`)이 회원 세션으로 나간다. 응답 전에 화면을 떠나도 세션은 넣는다(서버에는 이미 회원 · 세션이 생겼다).
 *
 * 오류: `AUTH_025`(가입표 없음 · 만료 · 이미 씀) → `kakao-restart`(사유 `expired`), `MEMBER_001`(그사이 같은 이메일로 가입됨) →
 * `kakao-restart`(사유 없음). 그 밖에 서비스가 업무 오류로 답했으면(저장소 장애 등 — 가입표를 이미 잃었다, `kakaoTicketLost`)
 * `kakao-restart`(사유 없음). 응답을 받지 못한 실패(네트워크 · 타임아웃 · 게이트웨이 오류)와 필수 동의 검증(`AUTH_110~112` —
 * 서버가 가입표를 지우지 않는다)만 거부한다 — 화면은 "가입하지 못했어요" 를 띄우고 다시 누르게 한다.
 */
async function signupWithKakao(consents: readonly Consent[]): Promise<SignupResult> {
  let token: AuthToken
  try {
    token = await apiRequest<AuthToken>(KAKAO_SIGNUP_PATH, {
      method: 'POST',
      body: {
        termsAgreed: agreed(consents, 'TERMS_OF_SERVICE'),
        privacyAgreed: agreed(consents, 'PRIVACY_POLICY'),
        ageOver19Confirmed: agreed(consents, 'AGE_OVER_19'),
      },
      auth: false,
    })
  } catch (error) {
    switch (errorCodeOf(error)) {
      case 'AUTH_025':
        return { status: 'kakao-restart', reason: 'expired' }
      case 'MEMBER_001':
        return { status: 'kakao-restart', reason: null }
      default:
        if (kakaoTicketLost(error)) return { status: 'kakao-restart', reason: null }
        throw error
    }
  }
  setSession(token)
  return { status: 'ok' }
}

/**
 * - `ok`: 저장했다
 * - `invalid`: 고를 수 없는 동네다 — 없는 코드(`REGION_001`) · 폐지된 코드(`REGION_002`) · 형식이 틀린 코드(`REGION_101` · `102`).
 *   서버는 저장하지 않았고, 화면은 다른 동네를 고르게 한다
 */
export type SaveRegionResult = { status: 'ok' } | { status: 'invalid' }

const SAVE_REGION_FAILURES: Readonly<Record<string, 'invalid'>> = {
  REGION_001: 'invalid',
  REGION_002: 'invalid',
  REGION_101: 'invalid',
  REGION_102: 'invalid',
}

/** 같은 회원의 첫 저장이 동시에 겹쳐 회원당 1행 제약에 막혔다(409). 다시 보내면 갱신으로 풀린다 */
const REGION_SAVE_CONFLICT = 'REGION_003'

/** 경합(`REGION_003`)이면 한 번만 다시 보낸다. 두 번째 결과는 그대로다 */
async function putRegionWithRetry(code: string): Promise<MyRegion> {
  try {
    return await putMyRegion(code)
  } catch (error) {
    if (errorCodeOf(error) !== REGION_SAVE_CONFLICT) throw error
    return putMyRegion(code)
  }
}

/**
 * 내 동네 저장. 가입 마무리(S02-3) · 내 동네 바꾸기(`/me/region`) · 폐지된 동네 다시 고르기(Setup-1-reselect) · 지도의
 * `내 동네로 설정`(#145)이 같이 쓴다 — 모두 백엔드 #60 의 `PUT /api/v1/members/me/region` 이다. 서버에는 **코드만** 보낸다.
 * 이름은 목 프로필이 내 동네를 들고 있게 받는다 — 그래서 시군구 없이 코드 · 이름만 받는다(지도 동네에는 시군구가 없다).
 *
 * 실데이터 (access 필요):
 * - 세션 저장소가 회원이 아니면 요청 없이 거부한다(401 을 받으러 보내지 않는다). 가입 마무리는 가입 응답(카카오) · 로그인(이메일)이
 *   세션을 넣은 뒤에 부른다
 * - 성공하면 응답(서버가 방금 확인한 동네)을 회원 정보 저장소의 내 동네로 넣는다(`setMemberRegion`) — 다시 읽지 않는다.
 *   보낼 때의 회원과 저장소의 회원이 다르면(그사이 로그아웃 · 다른 회원) 넣지 않는다
 * - 성공하면 같은 브라우저의 다른 탭에 알린다(`broadcastMemberRegionChanged`, #190) — 그 탭들이 옛 동네로 보고를 보내지 않게
 *   내 동네를 다시 읽는다. 값은 싣지 않는다. 이 탭은 위에서 넣었으니 다시 읽지 않는다
 * - `REGION_001` · `002` · `101` · `102` → `invalid`. `REGION_003`(동시 첫 저장 경합, 409)은 한 번 다시 보낸다
 * - 그 밖(`REGION_004` 행정동 확인 장애 503 · 일시 장애 · 두 번째 경합)은 거부한다 — 서버는 저장하지 않았고, 화면은
 *   "바꾸지 못했어요 · 잠시 뒤 다시" 로 알린다
 *
 * 목: 성공하면 화면과 무관하게 목 프로필의 동네를 바꾸고 폐지 표시를 끈다(응답 전에 화면을 떠나도 서버에서는 끝난 일이다).
 * 프로필이 없으면(`?mock-auth=` 덮어쓰기만 있음) 세션은 그대로다. 목은 `invalid` 를 돌려주지 않는다.
 * 목 재현: 프로필 이메일 `reselect-fail@example.com` 이면 거부한다(그 이메일로 가입해도 S02-3 의 동네 저장이 실패한다).
 */
export async function saveRegion(
  district: MemberRegion,
  source: DataSource,
): Promise<SaveRegionResult> {
  if (source === 'api') {
    const session = getSessionSnapshot()
    // 실데이터 세션이 없으면 보내지 않고 거부한다 — 토큰 없이 보내 401(SECURITY_001)을 받으러 가지 않는다
    if (session.status !== 'member') throw new Error('saveRegion: no member session')
    const { memberId } = session.summary
    let saved: MyRegion
    try {
      saved = await putRegionWithRetry(district.code)
    } catch (error) {
      const failure = mapError(error, SAVE_REGION_FAILURES)
      if (failure) return { status: failure }
      throw error
    }
    setMemberRegion(memberId, saved)
    broadcastMemberRegionChanged(memberId)
    return { status: 'ok' }
  }
  const failure = rejectIfProfileEmail(MOCK_RESELECT_FAIL_EMAIL, 'save region')
  if (failure) return failure
  if (mockProfile) {
    setMockSession(mockSession, {
      ...mockProfile,
      region: { code: district.code, name: district.name },
      regionAbolished: false,
    })
  }
  return { status: 'ok' }
}

/** 건강 · 증상 정보(민감정보) 처리 동의. 근거 문서 버전을 함께 보낸다 */
export function agreeHealthConsent(consent: Consent): Promise<void> {
  void consent
  // 동의를 보낸 화면이 응답 전에 닫혀도 서버에는 동의가 남는다. 세션 상태도 화면과 무관하게 여기서 바꾼다
  setMockSession('member')
  return Promise.resolve()
}

/* ── 약관 재동의 · 동네 다시 고르기 (Setup-3-reconsent · Setup-1-reselect) ───────────────────────────
 *
 * 연동 때 바꾼다: 재동의 — 백엔드 #59 의 재동의 API(요청 · 응답 모양은 아직 없다). 동네 다시 저장은 위 `saveRegion`(#60, 연동됨)이다.
 * 요청 시간 제한 · 네트워크 실패는 API 계층이 맡고, 실패하면 Promise 를 거부한다 — 화면은 다시 시도하라고 알린다.
 * 성공하면 화면과 무관하게 목 프로필의 조건을 먼저 끈다(응답 전에 화면을 떠나도 서버에서는 끝난 일이다).
 *
 * 목에서 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다). 프로필 이메일로 가린다 — 그 이메일로 이메일 로그인하면
 * 조건이 켜진 채 홈으로 간다:
 * - 조건만: `reconsent@example.com`(약관 재동의) · `reselect@example.com`(동네 폐지)
 * - 저장 실패: `reconsent-fail@example.com` → 재동의 거부, `reselect-fail@example.com` → 동네 저장 거부
 */

export const MOCK_RECONSENT_EMAIL = 'reconsent@example.com'
export const MOCK_RECONSENT_FAIL_EMAIL = 'reconsent-fail@example.com'
export const MOCK_RESELECT_EMAIL = 'reselect@example.com'
export const MOCK_RESELECT_FAIL_EMAIL = 'reselect-fail@example.com'

/** 개정된 필수 약관에 다시 동의한다. 근거 문서 버전(`legal.ts` 의 지금 버전)을 함께 보낸다 */
export function agreeTermsReconsent(consent: Consent): Promise<void> {
  void consent
  const failure = rejectIfProfileEmail(MOCK_RECONSENT_FAIL_EMAIL, 'terms reconsent')
  if (failure) return failure
  if (mockProfile?.termsReconsentRequired) {
    setMockSession(mockSession, { ...mockProfile, termsReconsentRequired: false })
  }
  return Promise.resolve()
}

/* ── 로그아웃 · 건강정보 동의 철회 · 탈퇴 (S10 확인 대화상자) ───────────────────────────────
 *
 * 로그아웃은 실데이터에서 `POST /api/v1/auth/logout`(백엔드 #57 — refresh 세션 폐기 + access token 블랙리스트)이다(아래 `logout`).
 * 연동 때 바꾼다: 건강정보 동의 철회(#59 — 원시 보고 파기 요청 + refresh 세션 전부 폐기), 탈퇴 `POST /api/v1/members/me/withdraw`(#59).
 * 요청 시간 제한 · 네트워크 실패는 API 계층이 맡고, 실패하면 Promise 를 거부한다 — 화면은 대화상자 안에서 다시 시도하라고 알린다.
 *
 * 성공하면 화면과 무관하게 여기서 목 세션을 바꾼다(응답 전에 화면이 닫혀도 서버에는 결과가 남는다):
 * 로그아웃 · 탈퇴 · 동의 철회 → `guest`(프로필도 지운다). 동의 철회는 서버가 모든 기기의 세션을 폐기하므로 로그아웃과 같은 결과다.
 *
 * 목에서 실패를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다). 프로필 이메일로 가린다 — 그 이메일로 이메일 로그인한 뒤 연다:
 * - 로그아웃: `logout-fail@example.com` → 거부(세션은 그대로)
 * - 동의 철회: `consent-withdraw-fail@example.com` → 거부
 * - 탈퇴: `withdraw-fail@example.com` → 거부
 */

export const MOCK_LOGOUT_FAIL_EMAIL = 'logout-fail@example.com'
export const MOCK_CONSENT_WITHDRAW_FAIL_EMAIL = 'consent-withdraw-fail@example.com'
export const MOCK_WITHDRAW_FAIL_EMAIL = 'withdraw-fail@example.com'

function rejectIfProfileEmail(email: string, what: string): Promise<never> | null {
  return mockProfile?.email === email ? Promise.reject(new Error(`mock ${what} failure`)) : null
}

/**
 * 토큰이 이미 무효라 서버 세션이 남아 있지 않은 갈래. 로그아웃은 끝난 것으로 본다:
 * 토큰 없이 보냄(`login-required` — 세션이 없거나 API 계층의 재발급이 재로그인으로 끝나 이미 세션을 비웠다) ·
 * access 거절(`reissue` — 재발급해도 거절됐다) · refresh 무효(`relogin`)
 */
const LOGOUT_SESSION_GONE: ReadonlySet<ApiErrorKind> = new Set([
  'login-required',
  'reissue',
  'relogin',
])

/**
 * 이 기기에서 로그아웃한다. 실데이터는 `POST /api/v1/auth/logout`(access 필요 — 만료가 가까우면 API 계층이 먼저 재발급한다).
 * 서버가 이 기기의 refresh 세션을 지우고 access 를 막으며 refresh 쿠키를 지운다.
 *
 * - 성공 → 세션 저장소를 비운다(`clearSession('logout')` — 힌트 쿠키를 지우고 다른 탭에도 알린다)
 * - 토큰이 이미 무효(`LOGOUT_SESSION_GONE`) → 성공과 같다. 서버에 지울 세션이 없고, 남은 쿠키는 쓸모가 없어 다음 로그인 때 덮인다
 *   (backend/docs/modules.md "화면 계약"). 세션 저장소가 아직 회원일 때만 비운다 — API 계층의 재발급이 재로그인으로 끝나 이미
 *   비웠으면(`clearSession('expired')`) 다시 비우지 않는다(로그아웃 알림을 한 번 더 방송하지 않는다)
 * - 성공 · 세션 사라짐 모두 만료 진행 표시를 끈다(`clearSessionExpiring`). 사용자가 고른 것은 로그아웃이라 화면의 홈 이동이 이기고,
 *   재발급이 켠 표시가 남아 나중에 엉뚱한 만료 토스트 · 가드 멈춤을 부르지 않게 한다
 * - **일시 장애 · 분류 밖 오류 → 거부하고 세션을 그대로 둔다.** 서버의 refresh 세션이 살아 있을 수 있는데 화면만 로그아웃된 것처럼
 *   보이면, 공용 기기에서 다음 사람이 새로고침으로 그 세션을 되살린다. 화면(내 정보 · 재동의)은 로그아웃 실패 안내를 띄우고 다시
 *   누르게 한다
 */
export async function logout(source: DataSource): Promise<void> {
  if (source === 'api') {
    let gone = false
    try {
      await apiRequest<null>(LOGOUT_PATH, { method: 'POST' })
    } catch (error) {
      if (!LOGOUT_SESSION_GONE.has(classifyApiError(error))) throw error
      gone = true
    }
    if (!gone || getSessionSnapshot().status === 'member') clearSession('logout')
    clearSessionExpiring()
    return
  }
  const failure = rejectIfProfileEmail(MOCK_LOGOUT_FAIL_EMAIL, 'logout')
  if (failure) return failure
  setMockSession('guest')
}

/**
 * 건강정보(민감정보) 처리 동의를 철회한다. 서버가 보낸 보고를 모두 지운다(파기 요청).
 *
 * 목은 로그아웃과 같이 `guest` 로 만들고 프로필을 지운다(보낸 보고 목도 세션이 바뀌어 함께 지운다). 백엔드 #59 는 철회와 함께
 * refresh 세션을 모두 폐기하고 요청 기기의 access token 도 막는다(대화상자 문구 "모든 기기에서 로그아웃돼요").
 * 연동 때 철회 응답 뒤 세션이 폐기되는지 백엔드와 맞춘다(SCREENS.md 연동 요구사항).
 */
export function withdrawHealthConsent(): Promise<void> {
  const failure = rejectIfProfileEmail(MOCK_CONSENT_WITHDRAW_FAIL_EMAIL, 'consent withdraw')
  if (failure) return failure
  // 철회하면 로그아웃된다(모든 기기). 비회원 세션(`?mock-auth=member` 덮어쓰기로 연 경우)은 이미 비회원이라 그대로다
  setMockSession('guest')
  return Promise.resolve()
}

/**
 * 로그인이 만료돼 세션을 비운다(State-session-expired). 화면 코드는 부르지 않는다 — 만료 알림(`src/lib/session-expiry.ts`)을 받는
 * `session-expiry-watcher.tsx` 만 부른다. 연동 때는 API 계층이 토큰을 지운 뒤 알리므로 함수 안만 실제 세션 비우기로 바꾼다
 */
export function expireMockSession(): void {
  setMockSession('guest')
}

/** 회원 탈퇴. 보낸 보고는 바로 지우고 계정은 30일 뒤 지운다(서버). 탈퇴 사유는 받지 않는다 */
export function withdrawMembership(): Promise<void> {
  const failure = rejectIfProfileEmail(MOCK_WITHDRAW_FAIL_EMAIL, 'withdraw')
  if (failure) return failure
  setMockSession('guest')
  return Promise.resolve()
}

/* ── 로그인한 기기 (S10 Settings-devices) ─────────────────────────────────────────────
 *
 * 실데이터 (access 필요, 백엔드 #57 — docs/api-contract-draft.md "인증"):
 * - 목록 `GET /api/v1/auth/sessions` → `{ sessions: [{ sessionId, deviceLabel, createdAt, lastUsedAt, current }], totalCount }`.
 *   화면 모델(`DeviceSession`)로 옮길 때 **세션 id · 기기 이름 · 마지막 사용 시각 · 이 기기인지만** 고른다 — 응답에 다른 값이 더
 *   실려 와도 옮기지 않는다. 시각은 ISO-8601 UTC 그대로 두고 화면이 한국 시각으로 쓴다(`formatMonthDayTime` — `Intl` `Asia/Seoul`)
 * - 한 기기 `DELETE /api/v1/auth/sessions/{sessionId}`(멱등 — 이미 없는 세션도 성공). 지금 기기(`current`)면 서버가 이 기기 세션과
 *   refresh 쿠키를 지우므로 세션 저장소도 비운다(`endThisDeviceSession`). `AUTH_114`(UUID 형식) 등 그 밖의 오류는 거부한다
 * - 다른 기기 모두 `DELETE /api/v1/auth/sessions`(지금 기기는 남는다). `AUTH_014`(서버가 이 토큰의 세션을 모름 — 다른 곳에서 이 기기를
 *   로그아웃했다)면 이 기기의 로그인도 끝난 것이라 `clearSession('expired')`(로그인 만료 안내 → 다시 로그인)로 비운 뒤 거부한다
 * - 일시 장애 · 분류 밖 오류는 거부한다 — 화면은 "불러오지 못했어요" · "로그아웃하지 못했어요" 로 알린다
 *
 * **IP · 접속 지역 · 정확한 위치는 받지도 그리지도 않는다**(루트 CLAUDE.md "개인정보" — 서버도 IP 를 저장하지 않는다).
 *
 * 목: 성공하면 화면과 무관하게 목 서버 목록에서 먼저 지운다(응답 전에 화면을 떠나도 서버에서는 끝난 일이다). 예시 목록
 * (`EXAMPLE_DEVICE_SESSIONS`)은 목에서만 쓴다 — 실데이터에서 채우지 않는다. 목은 이 기기의 세션을 받지 않는다(거부 — 화면도 보내지 않는다).
 *
 * 목에서 실패를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다). 프로필 이메일로 가린다 — 그 이메일로 이메일 로그인한 뒤 연다:
 * - 목록: `sessions-fail@example.com` → 거부(불러오지 못함)
 * - 로그아웃(한 기기 · 모두): `session-revoke-fail@example.com` → 거부(목록은 그대로)
 */

export const MOCK_SESSIONS_FAIL_EMAIL = 'sessions-fail@example.com'
export const MOCK_SESSION_REVOKE_FAIL_EMAIL = 'session-revoke-fail@example.com'

/** 로그인한 기기(refresh 세션) 하나 */
export type DeviceSession = {
  /** 세션 id(`sessionId`). 로그아웃할 때 보낸다 */
  id: string
  /** 기기 · 브라우저 (예: `Mac · Chrome`). 서버가 로그인할 때 User-Agent 를 줄여 만든 `deviceLabel` 이다(모르면 "알 수 없는 기기") */
  deviceName: string
  /** 마지막으로 쓴 시각 (ISO 8601, 실데이터는 UTC `lastUsedAt`). 이 기기는 "지금 사용 중" 으로 그려 이 값을 보이지 않는다 */
  lastActiveAt: string
  /** 지금 이 기기의 세션인지. 이 기기는 이 화면에서 로그아웃하지 않는다(내 정보의 로그아웃) */
  current: boolean
}

/** `GET /api/v1/auth/sessions` 의 `dataBody` (backend `AuthSessionsResponse` · `AuthSessionItem`) */
type SessionsResponse = {
  sessions: {
    sessionId: string
    deviceLabel: string
    createdAt: string
    lastUsedAt: string
    current: boolean
  }[]
  totalCount: number
}

/** 서버가 "다른 기기 모두 로그아웃" 을 요청한 토큰의 세션을 모른다(401) — 이 기기 로그인이 끝났다 */
const SESSION_UNKNOWN = 'AUTH_014'

/**
 * 시안(Settings-devices)의 예시 기기. **기기 이름 · 시각은 예시 값이다** — 목에서만 쓰고, 실제 값은 `GET /sessions` 에서 받는다.
 * 지금 쓰는 기기가 무엇이든 목의 "이 기기" 는 iPhone · Safari 다.
 */
const EXAMPLE_DEVICE_SESSIONS: readonly DeviceSession[] = [
  {
    id: 'mock-session-this',
    deviceName: 'iPhone · Safari',
    lastActiveAt: '2025-11-21T09:00:00+09:00',
    current: true,
  },
  {
    id: 'mock-session-mac',
    deviceName: 'Mac · Chrome',
    lastActiveAt: '2025-11-20T21:14:00+09:00',
    current: false,
  },
  {
    id: 'mock-session-galaxy',
    deviceName: 'Galaxy · 삼성 인터넷',
    lastActiveAt: '2025-11-02T08:03:00+09:00',
    current: false,
  },
]

function deviceSessions(): DeviceSession[] {
  mockDeviceSessions ??= EXAMPLE_DEVICE_SESSIONS.map((session) => ({ ...session }))
  return mockDeviceSessions
}

/**
 * 서버가 이 기기의 세션을 끝냈다(사용자가 고른 동작 — 이 기기 로그아웃 · 이 탭 계정의 비밀번호 재설정). 세션 저장소가 아직 회원이면
 * `clearSession('logout')` 으로 비운다: 만료가 아니라 사용자가 고른 결과라 "다시 로그인해 주세요" 안내(`expired`)를 띄우지 않고
 * (화면이 스스로 이동한다), 같은 refresh 쿠키를 쓰는 다른 탭에도 알린다. `logout` 과 같게 만료 진행 표시도 끈다
 */
function endThisDeviceSession(): void {
  if (getSessionSnapshot().status === 'member') clearSession('logout')
  clearSessionExpiring()
}

export async function listSessions(source: DataSource): Promise<DeviceSession[]> {
  if (source === 'api') {
    const response = await apiRequest<SessionsResponse>(SESSIONS_PATH)
    // 화면이 쓰는 네 값만 옮긴다. 생성 시각 · 개수는 그리지 않는다
    return response.sessions.map((session) => ({
      id: session.sessionId,
      deviceName: session.deviceLabel,
      lastActiveAt: session.lastUsedAt,
      current: session.current,
    }))
  }
  const failure = rejectIfProfileEmail(MOCK_SESSIONS_FAIL_EMAIL, 'list sessions')
  if (failure) return failure
  // 화면이 목록을 고쳐도 목 서버 목록이 바뀌지 않게 복사해 준다
  return deviceSessions().map((session) => ({ ...session }))
}

/**
 * 기기 하나를 로그아웃한다(그 기기의 refresh 세션 폐기). 이미 없는 세션이면(만료 · 다른 곳에서 지움) 끝난 것으로 본다.
 * 실데이터는 지금 기기(`current`)도 받는다 — 서버가 이 기기의 쿠키까지 지우므로 세션 저장소를 비운다.
 * 목은 이 기기의 세션을 받지 않는다(거부) — 이 기기 로그아웃은 `logout` 이다(화면도 이 기기에는 버튼을 두지 않는다).
 */
export async function revokeSession(
  session: Pick<DeviceSession, 'id' | 'current'>,
  source: DataSource,
): Promise<void> {
  if (source === 'api') {
    // 서버가 준 id 지만 경로에 그대로 붙이지 않는다(다른 경로로 새지 않게)
    await apiRequest<null>(`${SESSIONS_PATH}/${encodeURIComponent(session.id)}`, {
      method: 'DELETE',
    })
    if (session.current) endThisDeviceSession()
    return
  }
  const failure = rejectIfProfileEmail(MOCK_SESSION_REVOKE_FAIL_EMAIL, 'revoke session')
  if (failure) return failure
  const sessions = deviceSessions()
  if (sessions.some((item) => item.id === session.id && item.current)) {
    throw new Error('mock: the current session is revoked by logout')
  }
  mockDeviceSessions = sessions.filter((item) => item.id !== session.id)
}

/** 이 기기를 뺀 모든 기기를 로그아웃한다 */
export async function revokeOtherSessions(source: DataSource): Promise<void> {
  if (source === 'api') {
    try {
      await apiRequest<null>(SESSIONS_PATH, { method: 'DELETE' })
    } catch (error) {
      // 이 기기 세션이 서버에 없다 — 로그인이 끝났으니 만료로 비운다(다시 로그인 안내). 재발급이 이미 비웠으면 다시 비우지 않는다
      if (errorCodeOf(error) === SESSION_UNKNOWN && getSessionSnapshot().status === 'member') {
        clearSession('expired')
      }
      throw error
    }
    return
  }
  const failure = rejectIfProfileEmail(MOCK_SESSION_REVOKE_FAIL_EMAIL, 'revoke other sessions')
  if (failure) return failure
  mockDeviceSessions = deviceSessions().filter((session) => session.current)
}

/* ── 비밀번호 변경 (S10 Settings-password) ───────────────────────────────────────────────
 *
 * 실데이터: `POST /api/v1/members/me/password {currentPassword, newPassword}`(access 필요, 백엔드 #58 — docs/api-contract-draft.md "회원").
 * 성공하면 **이 기기는 로그인 상태로 남고 다른 기기는 모두 로그아웃된다** — 세션 저장소는 건드리지 않는다.
 * 규칙(8~20자 · 영문과 숫자 함께 · 공백 없이)은 가입과 같고 서버가 다시 검사한다. 새 비밀번호가 현재와 같아도 막지 않는다(계약에 없음).
 *
 * 오류 코드 → 결과 (backend `MemberErrorCode` · `MemberValidationMessage`):
 * - `MEMBER_005`(현재 비밀번호 불일치) → `wrong-current`. 검증 `MEMBER_103` · `104`(현재 비밀번호 없음 · 100자 초과)도 맞을 수 없는
 *   현재 비밀번호라 `wrong-current` 다(로그인의 `AUTH_113` → `wrong` 과 같은 판단)
 * - `MEMBER_006`(현재 비밀번호 확인 잠금, 429 — 잠금 시간은 서버 설정이라 못 박지 않는다) → `locked`
 * - 검증 `MEMBER_105~107`(새 비밀번호 없음 · 길이 · 구성) → `invalid-password`(화면 규칙과 어긋났을 때만 온다)
 * - `MEMBER_007`(비밀번호가 없는 카카오 계정) → `no-password`. 내 정보의 `hasPassword` 와 어긋난 호출이라 내 정보를 다시 읽는다
 *   (`reloadMemberInfo`) — 다시 읽은 값이 false 면 비밀번호 화면이 내 정보로 돌려보낸다
 * - 그 밖(`MEMBER_009` 세션 저장소 장애 503 · 일시 장애 · 분류 밖 오류)은 거부한다 — 비밀번호는 바뀌지 않았고, 화면은
 *   "바꾸지 못했어요 · 잠시 뒤 다시" 로 알린다
 *
 * 비밀번호 최초 설정(`POST /me/password/setup`)은 백엔드 #61 에서 없앴다 — 카카오만 쓰는 회원(`hasPassword` false)에게는 비밀번호
 * 메뉴를 숨긴다(#166). 목도 같은 동작이다(설정 함수 · 화면 없음).
 * 비밀번호는 어디에도 남기지 않는다(로그 · 저장소 · 주소 금지). 요청 시간 제한 · 네트워크 실패는 API 계층이 맡고 실패하면 거부한다.
 *
 * 목: 성공하면 서버처럼 목 서버 기기 목록에서 이 기기를 뺀 기기를 지운다. 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다):
 * - 현재 비밀번호 `wrong` → `wrong-current`(로그인 목과 같은 값)
 * - 프로필 이메일 `password-fail@example.com` 이거나 새 비밀번호 `fail2026` → 거부
 */

export const MOCK_PASSWORD_FAIL_EMAIL = 'password-fail@example.com'
export const MOCK_PASSWORD_FAIL_NEW = 'fail2026'

export type ChangePasswordResult =
  | { status: 'ok' }
  /** 현재 비밀번호가 맞지 않다 */
  | { status: 'wrong-current' }
  /** 현재 비밀번호를 여러 번 틀려 잠시 막혔다. 남은 시간은 모른다 */
  | { status: 'locked' }
  /** 새 비밀번호가 서버 규칙에 맞지 않는다 */
  | { status: 'invalid-password' }
  /** 비밀번호가 없는 계정이다(카카오만 씀). 내 정보를 다시 읽는다 */
  | { status: 'no-password' }

const CHANGE_PASSWORD_FAILURES: Readonly<
  Record<string, Exclude<ChangePasswordResult['status'], 'ok'>>
> = {
  MEMBER_005: 'wrong-current',
  MEMBER_103: 'wrong-current',
  MEMBER_104: 'wrong-current',
  MEMBER_006: 'locked',
  MEMBER_105: 'invalid-password',
  MEMBER_106: 'invalid-password',
  MEMBER_107: 'invalid-password',
  MEMBER_007: 'no-password',
}

function rejectPasswordFailure(newPassword: string): Promise<never> | null {
  if (mockProfile?.email === MOCK_PASSWORD_FAIL_EMAIL || newPassword === MOCK_PASSWORD_FAIL_NEW) {
    return Promise.reject(new Error('mock password failure'))
  }
  return null
}

export async function changePassword(
  currentPassword: string,
  newPassword: string,
  source: DataSource,
): Promise<ChangePasswordResult> {
  if (source === 'api') {
    try {
      await apiRequest<null>(CHANGE_PASSWORD_PATH, {
        method: 'POST',
        body: { currentPassword, newPassword },
      })
    } catch (error) {
      const failure = mapError(error, CHANGE_PASSWORD_FAILURES)
      if (!failure) throw error
      if (failure === 'no-password') reloadMemberInfo()
      return { status: failure }
    }
    return { status: 'ok' }
  }
  const failure = rejectPasswordFailure(newPassword)
  if (failure) return failure
  if (currentPassword === MOCK_WRONG_PASSWORD) return { status: 'wrong-current' }
  // 서버처럼 다른 기기를 로그아웃한다(이 기기는 남는다)
  mockDeviceSessions = deviceSessions().filter((session) => session.current)
  return { status: 'ok' }
}

/* ── 닉네임 바꾸기 (S10 `/me/nickname`, #192) ───────────────────────────────────────────────────
 *
 * 실데이터: `PATCH /api/v1/members/me {nickname}`(access 필요 — docs/api-contract-draft.md "회원"). 규칙은 가입과 같다 — 앞뒤 공백을
 * 지운 뒤 2~10자(코드포인트 기준, `signup-rules` 의 `nicknameProblem` 과 같은 판정). 앞뒤 공백을 지워 보낸다(서버도 지우고 저장한다).
 * 응답은 내 정보 조회와 같은 모양이라 회원 정보 저장소의 내 정보로 바로 넣는다(`setMemberInfo` — 다시 읽지 않는다). 보낼 때의 회원과
 * 저장소의 회원이 다르면(그사이 로그아웃 · 다른 회원) 넣지 않는다. 세션 저장소가 회원이 아니면 요청 없이 거부한다(`saveRegion` 과 같다).
 *
 * 오류 코드 → 결과:
 * - 검증 `MEMBER_101`(없음 · 공백만) · `102`(길이) → `invalid`(화면 규칙과 어긋났을 때만 온다 — 화면은 닉네임 칸 아래 규칙 문구)
 * - 회원 상태 오류(`MEMBER_004` 회원 없음 · `002` 탈퇴 · `003` 정지)는 내 정보 조회와 같은 판단이 필요하다. 거부하면서 내 정보를 다시
 *   읽게 해(`reloadMemberInfo`) 회원 정보 저장소가 처음 읽을 때처럼 세션을 끝내게 한다
 * - 그 밖(일시 장애 · 분류 밖 오류)은 거부한다 — 닉네임은 바뀌지 않았고, 화면은 "바꾸지 못했어요 · 잠시 뒤 다시" 로 알린다
 *
 * 목: 성공하면 화면과 무관하게 목 프로필의 닉네임을 바꾸고(구독자에게 알림) 그 이메일의 목 서버 닉네임도 바꾼다 — 다시 이메일 로그인해도
 * 바꾼 닉네임이다. 프로필이 없으면(`?mock-auth=` 덮어쓰기만 있음) 세션은 그대로다. 목은 `invalid` 를 돌려주지 않는다.
 * 목 재현 (docs/design/SCREENS.md 에도 적어 둔다): 프로필 이메일 `nickname-fail@example.com` 이면 거부한다.
 */

export const MOCK_NICKNAME_FAIL_EMAIL = 'nickname-fail@example.com'

/**
 * - `ok`: 바꿨다
 * - `invalid`: 서버가 닉네임을 규칙 위반으로 거절했다(`MEMBER_101` · `102`)
 */
export type UpdateNicknameResult = { status: 'ok' } | { status: 'invalid' }

const UPDATE_NICKNAME_FAILURES: Readonly<Record<string, 'invalid'>> = {
  MEMBER_101: 'invalid',
  MEMBER_102: 'invalid',
}

export async function updateNickname(
  nickname: string,
  source: DataSource,
): Promise<UpdateNicknameResult> {
  const trimmed = nickname.trim()
  if (source === 'api') {
    const session = getSessionSnapshot()
    // 실데이터 세션이 없으면 보내지 않고 거부한다 — 토큰 없이 보내 401(SECURITY_001)을 받으러 가지 않는다
    if (session.status !== 'member') throw new Error('updateNickname: no member session')
    const { memberId } = session.summary
    let info: MyInfo
    try {
      info = await patchMyNickname(trimmed)
    } catch (error) {
      const failure = mapError(error, UPDATE_NICKNAME_FAILURES)
      if (failure) return { status: failure }
      // 회원 상태 오류 판정은 회원 정보 저장소가 정한다(`endsMemberSession`)
      if (endsMemberSession(errorCodeOf(error))) reloadMemberInfo()
      throw error
    }
    setMemberInfo(memberId, info)
    return { status: 'ok' }
  }
  const failure = rejectIfProfileEmail(MOCK_NICKNAME_FAIL_EMAIL, 'update nickname')
  if (failure) return failure
  if (mockProfile) {
    mockNicknames.set(mockProfile.email, trimmed)
    setMockSession(mockSession, { ...mockProfile, nickname: trimmed })
  }
  return { status: 'ok' }
}

/* ── 비밀번호 재설정 (S13-6) ────────────────────────────────────────────────────────
 *
 * 실데이터(인증 불필요 — 모두 `auth: false`, 백엔드 #58 · docs/api-contract-draft.md "인증"): 코드 받기
 * `POST /api/v1/auth/password/reset/send-code {email}` → 코드 확인 `POST /api/v1/auth/password/reset/verify-code {email, code}` →
 * `{ resetToken }`(**일회용 재설정 토큰**, 15분) → 재설정 `POST /api/v1/auth/password/reset {resetToken, newPassword}`(토큰을 소비한다).
 *
 * 가입처럼 서버가 이메일별 인증 표시를 들고 재설정이 이메일 + 새 비밀번호만 받는 방식은 **쓰지 않는다** — 인증 표시가
 * 살아 있는 동안 코드를 모르고 이메일만 아는 사람이 비밀번호를 바꿀 수 있다. 재설정 권한은 코드를 맞힌 쪽이 받은 토큰에 묶는다.
 *
 * - 코드 받기 · 확인은 **가입 여부와 무관하게 같은 응답**이다(계정 열거 방지 — 가입 인증과 같다). 화면은 늘 코드 단계로 간다
 * - 코드 받기 · 확인의 한도 · 오류 코드는 가입 인증과 같다(서버가 같은 처리기를 쓴다): 코드 받기 `AUTH_001` · `002` → `limit`,
 *   코드 확인 `AUTH_003` → `wrong` · `004` → `expired` · `005` · `010` → `locked`. 남은 시도 횟수도 가입처럼 이 모듈이 이메일별 실패 수를
 *   세어 채운다 — **세는 곳은 가입과 따로다**(`passwordResetCodeFailures`, 서버의 Redis 키도 따로다)
 * - 재설정 오류: `AUTH_018`(토큰 없음 · 만료 · 이미 씀) · 검증 `AUTH_115` · `116`(토큰 없음 · 100자 초과 — 받은 토큰이 쓸 수 없다)
 *   → `verification-expired`(이메일 단계부터 다시). `AUTH_019`(IP 상한, 429 — 토큰은 그대로) → `limited`. 검증 `AUTH_105~107`
 *   (새 비밀번호) → `invalid-password`. 그 밖(`AUTH_017` 503 — 비밀번호는 그대로이고 토큰은 이미 소비됐다 · 일시 장애)은 거부한다.
 *   `AUTH_017` 뒤 다시 누르면 `AUTH_018` 로 이메일 단계로 간다
 * - **재설정에 성공하면 서버가 그 계정의 모든 기기를 로그아웃한다.** 이 탭이 회원이면 재설정한 이메일이 이 탭의 계정인지 보고
 *   맞출 때만 비운다(`endSessionAfterReset` — 화면이 재설정한 이메일을 넘긴다. 메모리에서만 쓰고 주소 · 로그에 남기지 않는다).
 *   같은 계정이면 `clearSession('logout')`(만료 안내 없이 — 화면이 이메일 로그인으로 스스로 간다), 다른 계정이면 이 탭 세션을
 *   건드리지 않는다(서버는 그 계정의 세션만 끊었다), 이 탭 계정의 이메일을 모르면(내 정보를 읽는 중 · 실패) 로그아웃한다
 * - **재설정 토큰은 메모리에만 둔다.** 화면은 Provider 메모리에만 들고 주소 · 로그 · 브라우저 저장소에 남기지 않는다. 요청 본문으로만 보낸다
 * - 새 비밀번호도 로그나 저장소에 남기지 않는다. 요청 시간 제한 · 네트워크 실패는 API 계층이 맡고 실패하면 Promise 를 거부한다
 *
 * 목에서 상태를 재현하는 입력 (docs/design/SCREENS.md 에도 적어 둔다). 코드는 가입 인증과 같은 입력을 쓰고 저장소는 따로다:
 * - 코드 받기: 이메일 `limit@example.com` → `limit`, 그 밖 → 보냄
 * - 코드 확인: `999999` → `locked`, `000000` → `wrong`(5번째에 `locked`), 5분이 지났거나 보낸 코드가 없으면 `expired`, 그 밖 6자리 → 토큰
 * - 재설정: 이메일 `verify-expired@example.com` 로 받은 토큰은 늘 `verification-expired`, `reset-fail@example.com` 로 받은 토큰이면
 *   응답을 받지 못한다(거부, 토큰은 남는다). 그 밖에도 토큰이 없거나 15분이 지났거나 이미 썼으면 `verification-expired` 다.
 *   성공하면 서버처럼 그 계정의 목 세션이 로그아웃된다(목 프로필 이메일이 토큰을 받은 이메일과 같을 때)
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

/** 실데이터의 이메일별 재설정 코드 확인 실패 수. 가입(`signupCodeFailures`)과 따로 센다. 메모리에만 둔다 */
const passwordResetCodeFailures = new Map<string, number>()

export async function sendPasswordResetCode(
  email: string,
  source: DataSource,
): Promise<SendCodeResult> {
  if (source === 'mock') return passwordResetCodes.send(email)
  try {
    await apiRequest<null>(PASSWORD_RESET_SEND_CODE_PATH, {
      method: 'POST',
      body: { email },
      auth: false,
    })
  } catch (error) {
    const failure = mapError(error, SEND_CODE_FAILURES)
    if (failure) return { status: failure }
    throw error
  }
  // 새 코드를 받으면 서버의 실패 수도 0 이다
  passwordResetCodeFailures.delete(normalizeEmail(email))
  return { status: 'sent' }
}

export async function verifyPasswordResetCode(
  email: string,
  code: string,
  source: DataSource,
): Promise<PasswordResetVerifyResult> {
  if (source === 'mock') {
    const result = passwordResetCodes.verify(email, code)
    if (result.status !== 'ok') return result
    // 목 토큰은 맞히기 쉬운 순번이다. 서버는 추측할 수 없는 값을 준다
    mockResetTokenSeq += 1
    const resetToken = `mock-reset-${mockResetTokenSeq}`
    mockResetTokens.set(resetToken, { email: normalizeEmail(email), issuedAt: Date.now() })
    return { status: 'ok', resetToken }
  }
  const key = normalizeEmail(email)
  let response: { resetToken?: unknown } | null
  try {
    response = await apiRequest<{ resetToken?: unknown } | null>(PASSWORD_RESET_VERIFY_CODE_PATH, {
      method: 'POST',
      body: { email, code },
      auth: false,
    })
  } catch (error) {
    const failure = verifyFailureOf(error, key, passwordResetCodeFailures)
    if (failure) return failure
    throw error
  }
  passwordResetCodeFailures.delete(key)
  const resetToken = response?.resetToken
  // 토큰이 없는 성공 응답은 계약 밖이다. 다음 단계로 보내지 않고 거부한다(토큰 값은 오류에 싣지 않는다)
  if (typeof resetToken !== 'string' || resetToken === '') {
    throw new Error('verifyPasswordResetCode: no reset token in the response')
  }
  return { status: 'ok', resetToken }
}

/**
 * 재설정이 성공한 뒤 이 탭 세션을 맞춘다. 서버는 재설정한 계정의 모든 기기를 로그아웃했다.
 * - 비회원이면 아무 일도 없다
 * - 이 탭 계정의 이메일(회원 정보 저장소의 `MyInfo.email`)을 알면 서버와 같은 정규화(앞뒤 공백 제거 · 소문자)로 비교한다.
 *   같으면 이 탭 세션도 서버에서 끊겼으니 비우고(`endThisDeviceSession`), 다르면 건드리지 않는다 — 다른 계정의 세션만 끊겼다
 * - 이메일을 모르면(내 정보를 읽는 중 · 실패) 로그아웃(`logout('api')`)해 서버 세션까지 끊는다. 로그인한 회원이 재설정에 오는 길은
 *   내 정보의 "비밀번호를 잊었어요" 라 재설정한 계정이 이 계정일 가능성이 높고, 그러면 서버 세션은 이미 끊겨 이 탭만 회원으로 남는다.
 *   다른 계정이었더라도 서버 세션을 남긴 채 화면만 로그아웃되지 않게 로그아웃 API 로 끊는다. 로그아웃이 거부되면(일시 장애) 그래도
 *   이 탭을 비운다 — 같은 계정이었다면 서버 세션은 이미 없다(다른 계정이었다면 그 세션은 쿠키로 남지만 힌트 쿠키를 지워 되살리지 않는다)
 */
async function endSessionAfterReset(email: string): Promise<void> {
  const session = getSessionSnapshot()
  if (session.status !== 'member') return
  const memberInfo = getMemberInfoSnapshot()
  const known =
    memberInfo?.memberId === session.summary.memberId && memberInfo.info.status === 'ready'
      ? memberInfo.info.value.email
      : null
  if (known !== null) {
    if (normalizeEmail(known) === normalizeEmail(email)) endThisDeviceSession()
    return
  }
  try {
    await logout('api')
  } catch {
    endThisDeviceSession()
  }
}

/**
 * - `verification-expired`: 재설정 토큰이 없거나 15분이 지났거나 이미 썼다. 이메일 단계부터 다시 한다
 * - `limited`: 이 기기(IP)의 재설정 시도가 많아 잠시 막혔다. 토큰은 그대로라 잠시 뒤 다시 누를 수 있다. 남은 시간은 모른다
 * - `invalid-password`: 새 비밀번호가 서버 규칙에 맞지 않는다(화면 규칙과 어긋났을 때만 온다)
 */
export type PasswordResetResult =
  | { status: 'ok' }
  | { status: 'verification-expired' }
  | { status: 'limited' }
  | { status: 'invalid-password' }

const RESET_FAILURES: Readonly<Record<string, Exclude<PasswordResetResult['status'], 'ok'>>> = {
  AUTH_018: 'verification-expired',
  AUTH_115: 'verification-expired',
  AUTH_116: 'verification-expired',
  AUTH_019: 'limited',
  AUTH_105: 'invalid-password',
  AUTH_106: 'invalid-password',
  AUTH_107: 'invalid-password',
}

/**
 * 비밀번호 재설정. `email` 은 재설정한(코드를 받은) 이메일이다 — 성공 뒤 이 탭의 회원이 그 계정인지 가리는 데만 쓰고 서버에는
 * 보내지 않는다(서버는 토큰으로 계정을 안다)
 */
export async function resetPassword(
  resetToken: string,
  newPassword: string,
  email: string,
  source: DataSource,
): Promise<PasswordResetResult> {
  if (source === 'api') {
    try {
      await apiRequest<null>(PASSWORD_RESET_PATH, {
        method: 'POST',
        body: { resetToken, newPassword },
        auth: false,
      })
    } catch (error) {
      const failure = mapError(error, RESET_FAILURES)
      if (failure) return { status: failure }
      throw error
    }
    // 서버가 그 계정의 모든 기기를 로그아웃했다(access 도 바로 폐기). 이 탭이 그 계정이면 이 탭 세션도 쓸 수 없다
    await endSessionAfterReset(email)
    return { status: 'ok' }
  }
  // 목은 새 비밀번호를 쓰지 않는다. 규칙(8~20자 · 영문 · 숫자 · 공백 금지)은 서버가 다시 검사한다
  void newPassword
  const issued = mockResetTokens.get(resetToken)
  if (!issued) return { status: 'verification-expired' }
  // 응답을 받지 못한 경우다. 서버가 바꿨는지 모르므로 토큰은 남겨 다시 누를 수 있게 한다
  if (issued.email === MOCK_RESET_FAIL_EMAIL) throw new Error('mock password reset failure')
  if (
    issued.email === MOCK_VERIFY_EXPIRED_EMAIL ||
    Date.now() - issued.issuedAt > PASSWORD_RESET_TOKEN_TTL_SECONDS * 1000
  ) {
    mockResetTokens.delete(resetToken)
    return { status: 'verification-expired' }
  }
  // 서버처럼 쓴 토큰은 지운다 — 같은 토큰으로 두 번 바꾸지 못한다
  mockResetTokens.delete(resetToken)
  // 서버처럼 그 계정의 모든 기기가 로그아웃된다. 목 세션이 그 계정일 때만 비회원이 된다(다른 계정이면 그대로)
  if (mockProfile?.email === issued.email) setMockSession('guest')
  return { status: 'ok' }
}
