/**
 * QA 용 목 회원 덮어쓰기 쿼리 이름. **`'use client'` 를 두지 않는다** — 서버 페이지(둘러볼 동네 고르기 · 지도)가 쓰는
 * `carriedParams`(`required-steps.ts`)도 이 이름을 읽는다. `'use client'` 모듈의 값은 서버에서 문자열이 아니라 클라이언트
 * 참조가 되어 `searchParams.get()` 이 늘 null 이 된다(#206 — 동네 고르기에서 돌아오면 덮어쓰기가 빠졌다).
 * 훅(`use-auth.ts` · `use-mock-auth.ts`)은 여기서 가져와 옛 자리에서 다시 내보낸다.
 */

/** QA 용 목 회원 상태 덮어쓰기 쿼리 (`?mock-auth=guest|member|member-no-consent`). 목데이터 모드에서만 듣는다 */
export const MOCK_AUTH_PARAM = 'mock-auth'

/** QA 용 목 로그인 방법 덮어쓰기 쿼리 (`?mock-provider=email|kakao`). 내 정보(S10)의 이메일 · 카카오 화면을 고른다. 목데이터 모드에서만 듣는다 */
export const MOCK_PROVIDER_PARAM = 'mock-provider'
