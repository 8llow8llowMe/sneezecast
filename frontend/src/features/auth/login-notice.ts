/**
 * 로그인 화면(S13-1)에 무엇을 알릴지. 카카오 콜백 · 세션 만료가 같은 주소 쿼리로 돌려보낸다.
 *
 * | 쿼리 | 알림 |
 * | --- | --- |
 * | `?error=kakao-fail` | 카카오 로그인을 마치지 못함 (빨강 상자) |
 * | `?error=kakao-exists` | 이메일 회원과 겹침 (파랑 상자 + 이메일로 로그인) |
 * | `?reason=expired` | 로그인 만료 (토스트) |
 *
 * 모르는 값이면 알리지 않는다. 둘이 함께 오면 오류를 먼저 보인다.
 */
export type LoginNotice = 'kakao-fail' | 'kakao-exists' | 'expired'

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
  const errorValue = first(error)
  if (errorValue === 'kakao-fail' || errorValue === 'kakao-exists') return errorValue
  return first(reason) === 'expired' ? 'expired' : null
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
