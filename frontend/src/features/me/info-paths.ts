/*
 * 서비스 안내 화면(S10, #193)의 종류 · 주소. 문구(`info-pages.ts`)와 나눈 것은 경로만 쓰는 화면(로그인 방법 고르기의 안내 링크 ·
 * 내 정보 행 · 내 정보 조건 가드)이 안내 문구를 번들에 끌어오지 않게 하려서다(#229) — Turbopack 은 같은 모듈의 쓰지 않는 export 를
 * 버리지 않는다. 서버 페이지에서도 쓰므로 `'use client'` 를 두지 않는다.
 */

export const INFO_PAGE_KINDS = ['privacy', 'data-sources', 'ai'] as const

export type InfoPageKind = (typeof INFO_PAGE_KINDS)[number]

/**
 * 안내 화면 주소. 내 정보(`/me`) 아래에 두지만 **회원 가드를 걸지 않는다** — 회원만 보는 화면은 화면마다 `useMemberGate` 를 건다.
 * 내 정보 레이아웃의 조건 가드(`MeRequiredStepsGate` — 재동의 · 동네 다시 고르기)도 이 경로에서는 보내지 않는다(`isInfoPath`).
 * 돌아갈 곳 허용 목록(`NEXT_PATHS` · `BROWSE_NEXT_PATHS`)에는 넣지 않는다 — 로그인으로 보냈다 돌려보낼 일이 없고,
 * 머리줄 동네 이름은 동네 안내처럼 홈을 돌아갈 곳으로 연다(안내 내용은 동네와 무관하다).
 */
export const INFO_PATHS: Record<InfoPageKind, string> = {
  privacy: '/me/privacy',
  'data-sources': '/me/data-sources',
  ai: '/me/ai',
}

/** 안내 화면 경로인지. 쿼리 없는 경로(`usePathname`)로 본다 */
export function isInfoPath(pathname: string | null): boolean {
  return pathname !== null && Object.values(INFO_PATHS).includes(pathname)
}
