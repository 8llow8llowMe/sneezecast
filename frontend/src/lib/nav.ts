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
 * 데스크톱 머리줄은 주요 메뉴를 둘로 나눈다(#123). 가운데 메뉴는 홈 · 지도이고, `내 정보` 는 머리줄 오른쪽 끝(보고 버튼 뒤)에 따로 둔다 —
 * 모바일 · 태블릿 탭바에서 `내 정보` 가 오른쪽 끝인 것과 같은 자리다. 탭바는 세 메뉴를 그대로 쓴다(`MAIN_NAV`).
 */
export const HEADER_CENTER_NAV = MAIN_NAV.filter((item) => item.key !== 'me')
export const HEADER_ME_NAV = MAIN_NAV[2]

/**
 * 주요 메뉴 링크 주소. `search`(앞 `?` 없이)가 있으면 붙인다 — 둘러보기에서 고른 동네(`region=<코드>`)처럼
 * 로그인 없이 주소에만 있는 값을 메뉴를 옮겨도 잃지 않게 한다.
 */
export function navHref(href: string, search?: string): string {
  return search ? `${href}?${search}` : href
}

/**
 * 경로가 어느 주요 메뉴 화면인지. 그 메뉴 아래 경로(`/me/devices`)도 그 메뉴다. 주요 메뉴 밖(로그인 · 첫 진입 등)이면 null 이다.
 * 루트 오류 화면(`app/error.tsx`)이 탭바 · 헤더 메뉴를 그릴지와 현재 메뉴를 고를 때 쓴다. 불러오는 중은 경계가 화면마다 있어
 * (`app/(home)/loading.tsx` · `app/map/loading.tsx` · `app/me/loading.tsx`) 메뉴를 바로 넘기므로 쓰지 않는다.
 */
export function mainNavKeyFor(pathname: string | null): MainNavKey | null {
  if (pathname === null) return null
  if (pathname === '/') return 'home'
  const item = MAIN_NAV.find(
    ({ href }) => href !== '/' && (pathname === href || pathname.startsWith(`${href}/`)),
  )
  return item?.key ?? null
}
