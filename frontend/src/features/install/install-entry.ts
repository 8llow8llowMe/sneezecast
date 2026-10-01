import { MOCK_AUTH_PARAM } from '@/features/auth/use-mock-auth'
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

/*
 * 앱 안 링크로 설치 안내에 들어왔는지. 모듈 메모리라 새로고침하면 사라진다(브라우저 저장소에 남기지 않는다).
 *
 * 설치 안내는 홈 · 내 정보와 레이아웃을 같이 쓰지 않는 단독 화면이라 레이아웃 Provider 로 앞 화면을 셀 수 없다.
 * 진입 링크가 누를 때 표시를 남기고(`markInstallEntry`), 화면이 마운트 때 한 번 읽어 상태에 둔 뒤 표시를 비운다
 * (`enteredInstallInApp` → `clearInstallEntry`, 동네 안내 `notice-entry.ts` 와 같다). 표시가 남지 않아 새 탭으로 연 링크 ·
 * 나중에 다른 길로 들어온 설치 안내가 앞 기록이 있다고 잘못 보지 않는다.
 * 주소로 바로 열었거나 새로고침 · 새 탭 · 앞으로 가기로 다시 들어왔으면 표시가 없다 — 그때는 홈으로 기록을 바꿔 간다.
 */
let enteredInApp = false

/** 설치 안내로 가는 앱 안 링크를 누를 때 부른다. 진입 링크마다 불러야 한다 */
export function markInstallEntry(): void {
  enteredInApp = true
}

/** 앱 안 링크로 들어왔으면 true. 화면이 마운트 때 한 번 읽는다(`useState` 초기값) */
export function enteredInstallInApp(): boolean {
  return enteredInApp
}

/** 표시를 비운다. 화면이 읽은 뒤(마운트 effect)와 테스트에서 부른다 */
export function clearInstallEntry(): void {
  enteredInApp = false
}
