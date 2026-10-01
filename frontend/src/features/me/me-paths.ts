import type { MockAuthState } from '@/features/auth/auth-client'
import { MOCK_AUTH_PARAM, MOCK_PROVIDER_PARAM } from '@/features/auth/use-mock-auth'
import { reportEntryFor } from '@/features/home/report-gate'
import { REPORT_PARAM } from '@/features/report/report-flow'

/** 내 정보(S10)와 그 아래 계정 화면 주소 (docs/design/SCREENS.md) */
export const ME_PATH = '/me'
/** 로그인한 기기 (Settings-devices) */
export const ME_DEVICES_PATH = '/me/devices'
/** 비밀번호 변경 · 설정 (Settings-password). 카카오 회원이 아직 비밀번호가 없으면 설정 화면이다 */
export const ME_PASSWORD_PATH = '/me/password'

/** 둘러보기 동네 (`?region=<행정동 코드>`). 홈 · 내 정보와 같은 쿼리다 */
const REGION_PARAM = 'region'

/**
 * 내 정보 화면 사이를 오갈 때 남기는 쿼리(앞 `?` 없이). 둘러보기 동네(`region`, 화면이 확인한 코드)와
 * QA 용 목 덮어쓰기(`mock-auth` · `mock-provider`)를 남긴다 — 덮어쓰기가 빠지면 하위 화면에서 비회원으로 보여 로그인으로 튕긴다.
 * 덮어쓰기는 실제 세션 연동 때 지운다(SCREENS.md "목 회원 상태" 연동 요구사항). `extra` 는 덧붙일 쿼리다.
 */
export function meSearch(
  regionCode: string | null,
  searchParams: Pick<URLSearchParams, 'get'>,
  extra: Record<string, string> = {},
): string {
  const params = new URLSearchParams()
  if (regionCode) params.set(REGION_PARAM, regionCode)
  for (const key of [MOCK_AUTH_PARAM, MOCK_PROVIDER_PARAM]) {
    const value = searchParams.get(key)
    if (value !== null) params.set(key, value)
  }
  for (const [key, value] of Object.entries(extra)) params.set(key, value)
  return params.toString()
}

/** 탭바 · 데스크톱 메뉴 링크 뒤 쿼리. 홈과 같이 동네만 남긴다 */
export function regionSearch(regionCode: string | null): string | undefined {
  return regionCode ? new URLSearchParams({ [REGION_PARAM]: regionCode }).toString() : undefined
}

/**
 * 머리줄 보고 버튼이 갈 곳. 비회원은 로그인, 회원은 홈의 보고 진입(미동의면 동의 시트, 동의했으면 보고 흐름)이다.
 * 동네(`region`)는 남긴다.
 */
export function reportHrefFor(auth: MockAuthState, regionCode: string | null): string {
  if (auth === 'guest') return '/login'
  const params = new URLSearchParams(regionCode ? { [REGION_PARAM]: regionCode } : {})
  params.set(REPORT_PARAM, reportEntryFor(auth))
  return `/?${params.toString()}`
}

/**
 * 계정 화면에서 일을 마치고 내 정보로 돌아와 띄울 알림. 주소가 아니라 내 정보 레이아웃(`MeTrailProvider`)에 한 번 쓰는 값으로 넘긴다.
 * 비밀번호 같은 값은 넣지 않는다 — 무슨 일을 마쳤는지만 넣는다.
 */
export const ME_NOTICES = {
  'password-changed': '비밀번호를 바꿨어요',
  'password-set': '비밀번호를 설정했어요. 이제 이메일로도 로그인할 수 있어요',
} as const

export type MeNotice = keyof typeof ME_NOTICES
