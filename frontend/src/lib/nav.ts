/**
 * 주요 메뉴. 모바일 · 태블릿 탭바와 데스크톱 헤더가 같은 목록을 쓴다.
 * 라우트는 docs/design/SCREENS.md 의 제안 라우트를 따른다.
 */
export const MAIN_NAV = [
  { key: 'home', href: '/', label: '홈' },
  { key: 'map', href: '/map', label: '지도' },
  { key: 'me', href: '/me', label: '내 정보' },
] as const

export type MainNavKey = (typeof MAIN_NAV)[number]['key']

/**
 * 주요 메뉴 링크 주소. `search`(앞 `?` 없이)가 있으면 붙인다 — 둘러보기에서 고른 동네(`region=<코드>`)처럼
 * 로그인 없이 주소에만 있는 값을 메뉴를 옮겨도 잃지 않게 한다.
 */
export function navHref(href: string, search?: string): string {
  return search ? `${href}?${search}` : href
}
