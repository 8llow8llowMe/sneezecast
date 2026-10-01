import { SETUP_REGION_PATH } from '@/features/onboarding/paths'

/**
 * 로그인. **API 연동 전 목 구현이다.** 세션 · 토큰 저장은 연동 이슈 범위라 여기서 하지 않는다 — 성공하면 이동만 한다.
 *
 * 연동 이슈에서 함수 안만 `src/lib/api/` 를 거친 백엔드 호출(백엔드 #56~#61)로 바꾸고 화면 코드는 그대로 둔다.
 * 요청 시간 제한 · 네트워크 실패는 API 계층이 맡고, 실패하면 Promise 를 거부한다 — 화면은 다시 시도하라고 알린다.
 * 비밀번호는 어디에도 남기지 않는다(로그 · 저장소 · 주소 금지).
 * 화면을 떠난 뒤 늦게 온 응답으로 이동하지 않게 요청 취소(AbortController)를 붙이는 일은 연동 이슈에서 한다.
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
