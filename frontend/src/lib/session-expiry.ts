/**
 * 로그인 만료(State-session-expired)를 알리는 단일 진입점.
 *
 * 만료를 알아챈 쪽은 `notifySessionExpired()` 만 부른다. 받는 쪽(`features/auth/session-expiry-watcher.tsx`, 루트 레이아웃에
 * 하나)이 세션을 비우고 로그인 화면(`/login?reason=expired`)으로 보내며, 로그인 화면(S13-1)이 "다시 로그인해 주세요" 토스트를 띄운다.
 *
 * - 지금(API 연동 전): QA 용 목 재현 입력 `?mock-session=expired` 가 부른다
 * - 연동 때: `src/lib/api/` 의 401 처리(토큰 갱신까지 실패했을 때)가 부른다. 화면 코드는 401 을 따로 다루지 않는다
 *
 * lib 는 features 를 모르므로 세션을 비우는 일 · 이동은 받는 쪽이 맡는다. 여러 요청이 한꺼번에 401 을 받아 여러 번 불려도
 * 받는 쪽은 같은 주소로 바꿀 뿐이라 괜찮다.
 */

const listeners = new Set<() => void>()
/** 만료를 알린 뒤 로그인 화면에 닿기 전까지 true 다 (`isSessionExpiring`) */
let expiring = false

/** 로그인이 만료됐다고 알린다. 받는 쪽을 부르기 전에 만료 진행 표시를 켠다 */
export function notifySessionExpired(): void {
  expiring = true
  listeners.forEach((listener) => listener())
}

/** 로그인 만료를 받는다. 돌려준 함수로 구독을 끊는다 */
export function onSessionExpired(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * 만료를 알린 뒤 로그인 화면으로 가는 중인지.
 *
 * 만료되면 세션이 먼저 비회원이 되어, 회원만 쓰는 화면의 가드(`features/me/member-gate.ts` · 재동의 · 동네 다시 고르기)가
 * 같은 때 다른 곳(`/login` · 홈)으로 `replace` 한다. 그 이동이 만료 이동보다 나중이면 토스트 없는 화면에 닿는다 —
 * 가드는 이 값이 true 면 만료 주소로 보내거나 이동하지 않는다. 이동 시점과 무관하게 같은 곳에 닿게 하는 표시다.
 */
export function isSessionExpiring(): boolean {
  return expiring
}

/**
 * 만료 진행 표시를 끈다. 받는 쪽(`session-expiry-watcher.tsx`)이 로그인 화면에 닿으면 부르고, 사용자가 고른 로그아웃
 * (`features/auth/auth-client.ts` 의 `logout`)이 끝났을 때도 부른다 — 로그아웃 중 재발급이 만료를 알렸어도 로그아웃 이동이 이긴다
 */
export function clearSessionExpiring(): void {
  expiring = false
}
