/**
 * 가입 동의(S02-3) 체크 규칙.
 *
 * 시안(Setup-3 default)은 [선택] 알림이 꺼져 있어도 "전체 동의" 가 켜져 있다. 그래서
 * - "전체 동의" 표시는 **필수 항목이 모두 켜졌는지**를 따른다
 * - "전체 동의" 를 켜면 선택 항목까지 모두 켜고, 끄면 모두 끈다
 * - 필수 항목 하나라도 끄면 "전체 동의" 도 꺼진다
 */
export type TermsChecks = { terms: boolean; privacy: boolean; notification: boolean }

export const NO_CHECKS: TermsChecks = { terms: false, privacy: false, notification: false }

export function requiredAgreed(checks: TermsChecks): boolean {
  return checks.terms && checks.privacy
}

export function setAll(on: boolean): TermsChecks {
  return { terms: on, privacy: on, notification: on }
}
