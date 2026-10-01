/**
 * 첫 진입 화면 주소 (docs/design/SCREENS.md S01 · S02 · S13). 화면 사이 이동은 페이지 이동이라 주소 쿼리 모달이 아니다.
 *
 * 흐름: 시작(S01) → 로그인(S13-1) → (카카오 신규 · 이메일 가입 S13-2~4) → 동네 선택(S02-1) → 성인 확인(S02-2) → …
 *
 * 로그인 · 가입 경로도 auth 가 아니라 여기 둔다. 첫 진입 흐름(시작 · 로그인 · 가입 · 동네)의 화면이 서로 앞뒤 단계로
 * 이어져 있어, 경로를 한곳에 두어야 흐름을 한눈에 보고 순환 import 없이 서로 가리킬 수 있다.
 */
export const START_PATH = '/start'
export const HOME_PATH = '/'
/** 로그인 방법 고르기 (S13-1). `?error=kakao-fail|kakao-exists` · `?reason=expired` 로 상태를 받는다 */
export const LOGIN_PATH = '/login'
/** 이메일 로그인 (S13-5). `?reason=reset-done` 이면 비밀번호를 바꿨다는 안내를 띄운다 */
export const LOGIN_EMAIL_PATH = '/login/email'
/** 이메일 가입: 이메일 (S13-2) → 인증 코드 (S13-3) → 비밀번호 · 닉네임 (S13-4) → 동네 선택 */
export const SIGNUP_EMAIL_PATH = '/signup/email'
export const SIGNUP_CODE_PATH = '/signup/code'
export const SIGNUP_ACCOUNT_PATH = '/signup/account'
/** 비밀번호 재설정 (S13-6). 다음 단계에서 만든다 */
export const PASSWORD_RESET_PATH = '/password/reset'
export const SETUP_REGION_PATH = '/setup/region'
/** 보고 없이 둘러보기. 동네 선택을 단계 없이 쓴다 (docs/design/auth/README.md 제안 라우트) */
export const BROWSE_REGION_PATH = '/browse/region'
export const SETUP_ADULT_PATH = '/setup/adult'
/** 가입 동의 (S02-3, 3 / 4). 여기서 회원 가입 요청을 보낸다 */
export const SETUP_TERMS_PATH = '/setup/terms'
/** 증상 보고 동의 (S02-4, 4 / 4). 가입을 마친 뒤 건강정보 동의를 따로 받는다 */
export const SETUP_HEALTH_CONSENT_PATH = '/setup/health-consent'

/** 둘러보기에서 고른 동네의 홈 */
export function browseHomePath(code: string): string {
  return `/?region=${encodeURIComponent(code)}`
}
