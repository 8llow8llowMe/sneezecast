import { LOGIN_PATH } from '@/features/onboarding/paths'

/**
 * 로그인 화면(S13-1)에 무엇을 알릴지. 카카오 콜백 · 세션 만료가 같은 주소 쿼리로 돌려보낸다.
 *
 * | 쿼리 | 알림 |
 * | --- | --- |
 * | `?error=kakao-fail` | 카카오 로그인을 마치지 못함 (빨강 상자). `&kakao=<사유>` 가 있으면 사유별 문장 |
 * | `?reason=expired` | 로그인 만료 (토스트) |
 *
 * 모르는 값이면 알리지 않는다. 둘이 함께 오면 오류를 먼저 보인다.
 * `?error=kakao-exists`(이메일 회원과 겹침)는 #167 에서 없앴다 — 백엔드가 겹치면 연결 확인(`LINK_REQUIRED`)을 돌려주고
 * 화면은 계정 연결 확인(`/login/kakao/link`)으로 간다. 옛 주소로 와도 알리지 않는다.
 */
export type LoginNotice = 'kakao-fail' | 'expired'

type Param = string | string[] | undefined

function first(value: Param): string | undefined {
  return Array.isArray(value) ? value[0] : value
}

export function loginNoticeFrom({
  error,
  reason,
}: {
  error?: Param
  reason?: Param
}): LoginNotice | null {
  if (first(error) === 'kakao-fail') return 'kakao-fail'
  return first(reason) === 'expired' ? 'expired' : null
}

/**
 * 카카오 로그인을 마치지 못한 사유 중 화면이 따로 알리는 것 (`?error=kakao-fail&kakao=<사유>`, #167).
 * 허용 목록의 짧은 값만 주소에 싣는다 — 서버 문구 · 오류 코드 · 이메일은 싣지 않는다. 목록에 없으면 사유 없이 알린다.
 *
 * | 값 | 서버 오류 |
 * | --- | --- |
 * | `email-required` | `AUTH_023` 카카오 이메일 제공 동의 안 함 |
 * | `email-unverified` | `AUTH_024` 카카오 이메일 미인증 |
 * | `expired` | `AUTH_025` 가입표 · `026` 연결 확인표 시간 지남 |
 * | `suspended` | `MEMBER_003` 이용 정지 |
 *
 * 탈퇴(`MEMBER_002`)는 사유 없이 알린다 — 이메일 로그인(#163)처럼 탈퇴 회원을 따로 드러내지 않는다.
 */
export const KAKAO_FAIL_REASONS = [
  'email-required',
  'email-unverified',
  'expired',
  'suspended',
] as const

export type KakaoFailReason = (typeof KAKAO_FAIL_REASONS)[number]

/** 사유를 싣는 쿼리 이름. `reason`(로그인 만료)과 겹치지 않게 따로 둔다 */
export const KAKAO_REASON_PARAM = 'kakao'

export function kakaoFailReasonFrom(value: Param): KakaoFailReason | null {
  const reason = first(value)
  return KAKAO_FAIL_REASONS.find((known) => known === reason) ?? null
}

/** 카카오 로그인을 마치지 못했을 때 보내는 로그인 화면 주소 */
export function kakaoFailPath(reason: KakaoFailReason | null): string {
  const query = new URLSearchParams({ error: 'kakao-fail' })
  if (reason) query.set(KAKAO_REASON_PARAM, reason)
  return `${LOGIN_PATH}?${query.toString()}`
}

/** 이메일 로그인(S13-5)이 비밀번호를 바꾼 뒤 돌아왔는지 (`?reason=reset-done`) */
export function isResetDone(reason: Param): boolean {
  return first(reason) === 'reset-done'
}

/**
 * 이메일 가입(S13-2) · 비밀번호 재설정(S13-6) 이메일 단계가 인증 시간이 지나 돌아왔는지 (`?reason=verification-expired`).
 * 가입은 S02-3 가입 요청의 `AUTH_007`, 재설정은 새 비밀번호 요청의 인증 만료다
 */
export function isVerificationExpired(reason: Param): boolean {
  return first(reason) === 'verification-expired'
}
