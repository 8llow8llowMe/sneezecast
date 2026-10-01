/**
 * 첫 진입 화면 주소 (docs/design/SCREENS.md S01 · S02). 화면 사이 이동은 페이지 이동이라 주소 쿼리 모달이 아니다.
 *
 * 로그인 단계는 시안을 기다리는 중이다. 시안이 오면 시작(S01)과 동네 선택 사이에 끼운다.
 */
export const START_PATH = '/start'
export const SETUP_REGION_PATH = '/setup/region'
/** 보고 없이 둘러보기. 동네 선택을 단계 없이 쓴다 (docs/design/auth/README.md 제안 라우트) */
export const BROWSE_REGION_PATH = '/browse/region'
export const SETUP_ADULT_PATH = '/setup/adult'
/** 동의 (S02-3). 다음 이슈에서 만든다 */
export const SETUP_CONSENT_PATH = '/setup/consent'

/** 둘러보기에서 고른 동네의 홈 */
export function browseHomePath(code: string): string {
  return `/?region=${encodeURIComponent(code)}`
}
