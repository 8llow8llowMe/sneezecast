import { MOCK_AUTH_PARAM } from '@/features/auth/mock-params'
import { MOCK_PUSH_PARAM } from '@/lib/push-support'

/** S12 홈 화면 추가 안내 (docs/design/SCREENS.md) */
export const INSTALL_PATH = '/install'

/** 둘러보기 동네 (`?region=<행정동 코드>`). 홈 · 내 정보와 같은 쿼리다 */
const REGION_PARAM = 'region'

/**
 * 설치 안내로 갈 때와 거기서 홈으로 돌아갈 때 남기는 쿼리(앞 `?` 없이). 둘러보기 동네(`region`, 화면이 확인한 코드)와
 * QA 용 목 덮어쓰기(`mock-auth` · `mock-push`)만 남긴다 — 홈으로 돌아가도 같은 회원 · 푸시 상태로 보이게 한다.
 * 덮어쓰기는 실제 세션 · 푸시 연동 때 지운다.
 */
export function installSearch(
  regionCode: string | null,
  searchParams: Pick<URLSearchParams, 'get'>,
): string {
  const params = new URLSearchParams()
  if (regionCode) params.set(REGION_PARAM, regionCode)
  for (const key of [MOCK_AUTH_PARAM, MOCK_PUSH_PARAM]) {
    const value = searchParams.get(key)
    if (value !== null) params.set(key, value)
  }
  return params.toString()
}
