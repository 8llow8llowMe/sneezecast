import type { MockAuthState } from '@/features/auth/auth-client'

/**
 * 내 정보(S10)의 확인 대화상자 쿼리 (`?confirm=logout|consent-withdraw|withdraw`).
 * 시트 · 대화상자의 열림은 주소 쿼리에 둔다(docs/conventions.md) — 메뉴 행으로 열 때는 기록을 쌓는다.
 */
export const CONFIRM_PARAM = 'confirm'

export const CONFIRM_KINDS = ['logout', 'consent-withdraw', 'withdraw'] as const

export type ConfirmKind = (typeof CONFIRM_KINDS)[number]

/** 쿼리 값을 대화상자 종류로. 없거나 모르는 값이면 null 이다(아무 것도 열지 않는다) */
export function parseConfirm(value: string | null): ConfirmKind | null {
  return CONFIRM_KINDS.find((kind) => kind === value) ?? null
}

/**
 * 보내지 못했을 때 문구 (시안에 없어 더했다). 대화상자가 열려 있으면 그 안 빨강 상자, 보내는 중에 뒤로 가기로
 * 대화상자를 닫았으면 내 정보의 알림(토스트)으로 같은 문구를 띄운다.
 */
export const CONFIRM_FAILURE: Record<ConfirmKind, string> = {
  logout: '로그아웃하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
  'consent-withdraw': '동의를 철회하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
  withdraw: '탈퇴하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
}

/**
 * 지금 회원 상태에서 열 수 있는 대화상자인지. 주소로 바로 들어온 값이 맞지 않으면 열지 않는다.
 *
 * | 대화상자 | 여는 사람 |
 * | --- | --- |
 * | `logout` · `withdraw` | 회원 (동의 여부 무관) |
 * | `consent-withdraw` | 건강정보 동의를 한 회원 |
 */
export function confirmAllowed(kind: ConfirmKind, auth: MockAuthState): boolean {
  if (kind === 'consent-withdraw') return auth === 'member'
  return auth !== 'guest'
}
