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
