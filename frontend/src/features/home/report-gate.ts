import type { MockAuthState } from '@/features/auth/auth-client'
import { REPORT_STEPS } from '@/features/report/types'

/**
 * 홈 보고 진입. 보고 흐름(`?report=start`)은 건강정보 동의를 한 회원만 연다. 그 앞에 서는 시트 두 개도
 * 같은 쿼리 `?report=` 의 값으로 둔다 — 시트가 보고 흐름의 앞 단계라, 동의한 뒤 값만 `start` 로 바꾸면(replace)
 * 뒤로 가기가 시트를 건너뛰어 홈으로 간다. 쿼리를 따로 두면 두 쿼리가 함께 있는 주소(예: 로그인 안내 + 보고 시작)를
 * 따로 막아야 한다.
 *
 * | 값 | 시트 | 보이는 사람 |
 * | --- | --- | --- |
 * | `login` | 로그인 안내 (Login-sheet) | 비회원 |
 * | `health-consent` | 증상 보고 동의 (Consent-health-sheet) | 동의하지 않은 회원 |
 * | `start` … `done` | 보고 흐름 (S05 · S06) | 동의한 회원 |
 */
export const REPORT_GATE = { login: 'login', healthConsent: 'health-consent' } as const

/** 보고 버튼을 눌렀을 때 열 값. 머리줄 · 하단 · 둘러보기 홈의 버튼이 모두 이 값을 연다 */
export function reportEntryFor(auth: MockAuthState): string {
  if (auth === 'guest') return REPORT_GATE.login
  if (auth === 'member-no-consent') return REPORT_GATE.healthConsent
  return 'start'
}

/**
 * 보고 버튼 글자. 홈 하단 버튼과 모든 머리줄(홈 · 지도 · 동네 안내 · 공식 정보 · 내 정보 · 계정 화면)이 이 값을 쓴다.
 *
 * | 회원 상태 | 보낸 보고 | 글자 |
 * | --- | --- | --- |
 * | 비회원 | - | 로그인하고 보고하기 (Home-guest) |
 * | 회원 | 없음 | 이번 주 건강 보고하기 |
 * | 동의한 회원 | 있음 | 이번 주 보고 완료 · 수정하기 (Flow 의 reported) |
 *
 * 보낸 보고는 동의한 회원에게만 보인다 — 미동의 회원의 버튼은 동의 시트를 열므로(덮어쓰기 `?mock-auth=` 로 보낸 보고가 남아 있어도)
 * 완료로 보이지 않는다. 누르면 갈 곳은 그대로다(`reportEntryFor` — 보낸 뒤면 보고 흐름이 수정으로 열린다).
 */
export function reportButtonLabel(auth: MockAuthState, submitted: boolean): string {
  if (auth === 'guest') return '로그인하고 보고하기'
  if (reportDone(auth, submitted)) return '이번 주 보고 완료 · 수정하기'
  return '이번 주 건강 보고하기'
}

/** 보고 버튼이 "보낸 뒤" 모양인지. 홈 하단 버튼은 이때 회색 보조 버튼이다(Flow 의 reported) */
export function reportDone(auth: MockAuthState, submitted: boolean): boolean {
  return auth === 'member' && submitted
}

/** `?report=` 값이 홈이 아는 보고 진입(시트 · 보고 흐름 단계)인지. 모르는 값이면 아무 시트도 열리지 않는다 */
export function isReportEntryValue(value: string | null): boolean {
  return (
    value === REPORT_GATE.login ||
    value === REPORT_GATE.healthConsent ||
    REPORT_STEPS.some((step) => step === value)
  )
}

/**
 * 주소의 `?report=` 값이 지금 회원 상태에 맞지 않으면 바꿀 값을 돌려준다. 맞으면 null 이다.
 * 주소로 바로 들어온 비회원 · 미동의 회원에게 보고 흐름을 열지 않고 맞는 시트로 바꾼다.
 * 모르는 값은 그대로 둔다(아무 것도 열리지 않는다).
 */
export function guardReportEntry(value: string | null, auth: MockAuthState): string | null {
  if (!isReportEntryValue(value)) return null
  const isStep = REPORT_STEPS.some((step) => step === value)
  const entry = reportEntryFor(auth)
  // 동의한 회원은 보고 흐름의 어느 단계든 그대로 둔다. 시트 값이면 보고 시작으로 바꾼다
  if (auth === 'member') return isStep ? null : entry
  return value === entry ? null : entry
}
