/**
 * 첫 진입 화면 주소 (docs/design/SCREENS.md S01 · S02 · S13). 화면 사이 이동은 페이지 이동이라 주소 쿼리 모달이 아니다.
 *
 * 흐름: 시작(S01) → 로그인(S13-1) → (카카오 신규 · 이메일 가입 S13-2~4) → 동네 선택(S02-1) → 성인 확인(S02-2) → …
 * 비밀번호 재설정(S13-6)은 이메일 로그인(S13-5)에서 갈라졌다가 이메일 로그인으로 돌아온다.
 *
 * 로그인 · 가입 경로도 auth 가 아니라 여기 둔다. 첫 진입 흐름(시작 · 로그인 · 가입 · 동네)의 화면이 서로 앞뒤 단계로
 * 이어져 있어, 경로를 한곳에 두어야 흐름을 한눈에 보고 순환 import 없이 서로 가리킬 수 있다.
 */
export const START_PATH = '/start'
export const HOME_PATH = '/'
/** 로그인 방법 고르기 (S13-1). `?error=kakao-fail|kakao-exists` · `?reason=expired` 로 상태를 받는다 */
export const LOGIN_PATH = '/login'
/** 로그인이 만료돼 돌아온 로그인 화면 — "다시 로그인해 주세요" 토스트 (State-session-expired) */
export const LOGIN_EXPIRED_PATH = `${LOGIN_PATH}?reason=expired`
/** 이메일 로그인 (S13-5). `?reason=reset-done` 이면 비밀번호를 바꿨다는 안내를 띄운다 */
export const LOGIN_EMAIL_PATH = '/login/email'
/**
 * 이메일 가입: 이메일 (S13-2) → 인증 코드 (S13-3) → 비밀번호 · 닉네임 (S13-4) → 동네 선택.
 * 이메일 화면은 `?reason=verification-expired` 면 인증 시간이 지났다는 안내를 띄운다
 */
export const SIGNUP_EMAIL_PATH = '/signup/email'
/** 가입 요청이 인증 만료(`AUTH_007`)로 돌아왔을 때 S02-3 이 보내는 곳 */
export const SIGNUP_EMAIL_VERIFICATION_EXPIRED_PATH = `${SIGNUP_EMAIL_PATH}?reason=verification-expired`
export const SIGNUP_CODE_PATH = '/signup/code'
export const SIGNUP_ACCOUNT_PATH = '/signup/account'
/** 비밀번호를 바꾼 뒤 이메일 로그인 */
export const LOGIN_EMAIL_RESET_DONE_PATH = `${LOGIN_EMAIL_PATH}?reason=reset-done`
/**
 * 비밀번호 재설정 (S13-6): 이메일 → 인증 코드 → 새 비밀번호 → 이메일 로그인(`?reason=reset-done`).
 * 이메일 로그인(S13-5)의 "비밀번호를 잊었어요" 가 들어오는 곳이다. 단계 표시는 없다.
 * 이메일 화면은 `?reason=verification-expired` 면 인증 시간이 지났다는 안내를 띄운다
 */
export const PASSWORD_RESET_PATH = '/password/reset'
/** 재설정 요청이 인증 만료로 돌아왔을 때 새 비밀번호 화면이 보내는 곳 */
export const PASSWORD_RESET_VERIFICATION_EXPIRED_PATH = `${PASSWORD_RESET_PATH}?reason=verification-expired`
export const PASSWORD_RESET_CODE_PATH = '/password/reset/code'
export const PASSWORD_RESET_NEW_PATH = '/password/reset/new'
export const SETUP_REGION_PATH = '/setup/region'
/**
 * 카카오 로그인에서 돌아온 동네 선택. 첫 진입 Provider 밖(홈의 로그인 안내 시트)에서 카카오로 시작해도 S02-1 이 가입 종류를
 * 카카오로 둘 수 있게 쿼리로 알린다. 목은 `startKakaoLogin` 이 이 주소를 돌려주고, 연동 때는 카카오 신규 회원 콜백이 이 주소로 돌아온다
 */
export const FROM_PARAM = 'from'
export const FROM_KAKAO = 'kakao'
export const SETUP_REGION_FROM_KAKAO_PATH = `${SETUP_REGION_PATH}?${FROM_PARAM}=${FROM_KAKAO}`
/**
 * 폐지된 동네 다시 고르기 (Setup-1-reselect). 동네 선택과 같은 주소에 `?reselect=1` 을 붙인다.
 * 홈 · 내 정보에 들어온 회원의 동네가 행정구역 개편으로 없어졌으면 보낸다(`features/auth/required-steps.ts`)
 */
export const RESELECT_PARAM = 'reselect'
export const RESELECT_VALUE = '1'
/** 필수 약관 재동의 (Setup-3-reconsent). 이용약관이 개정됐으면 동네 다시 고르기보다 먼저 보낸다 */
export const TERMS_RECONSENT_PATH = '/terms/reconsent'
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
