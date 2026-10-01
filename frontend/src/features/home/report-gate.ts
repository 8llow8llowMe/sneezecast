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
 * 주소의 `?report=` 값이 지금 회원 상태에 맞지 않으면 바꿀 값을 돌려준다. 맞으면 null 이다.
 * 주소로 바로 들어온 비회원 · 미동의 회원에게 보고 흐름을 열지 않고 맞는 시트로 바꾼다.
 * 모르는 값은 그대로 둔다(아무 것도 열리지 않는다).
 */
export function guardReportEntry(value: string | null, auth: MockAuthState): string | null {
  const isGate = value === REPORT_GATE.login || value === REPORT_GATE.healthConsent
  const isStep = REPORT_STEPS.some((step) => step === value)
  if (!isGate && !isStep) return null
  const entry = reportEntryFor(auth)
  // 동의한 회원은 보고 흐름의 어느 단계든 그대로 둔다. 시트 값이면 보고 시작으로 바꾼다
  if (auth === 'member') return isStep ? null : entry
  return value === entry ? null : entry
}
