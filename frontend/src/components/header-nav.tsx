import Link from 'next/link'

import clsx from 'clsx'

import { HEADER_CENTER_NAV, HEADER_ME_NAV, type MainNavKey, navHref } from '@/lib/nav'

/**
 * 데스크톱 머리줄 조각. 앱 헤더(`AppHeader`)와 불러오는 중 · 오류 머리줄(`StateHeader`)이 같이 쓴다.
 * 모두 데스크톱에서만 보인다(모바일 · 태블릿 시안의 머리줄에는 서비스명 · 메뉴가 없고 탭바를 쓴다).
 * `navSearch` 는 링크 뒤에 붙일 쿼리다(앞 `?` 없이, 예: 둘러보기 동네 `region=<코드>`).
 */

const NAV_LINK_CLASS = 'flex h-11 items-center px-3.5 text-body'

function navLinkClass(active: boolean) {
  return clsx(NAV_LINK_CLASS, active ? 'font-bold text-brand' : 'font-medium text-fg-sub')
}

/** 서비스명. 누르면 홈 `/` 으로 간다(둘러보기 동네는 남긴다) */
export function BrandLink({ navSearch }: { navSearch?: string | undefined }) {
  return (
    <Link
      href={navHref('/', navSearch)}
      className="hidden h-11 items-center text-screen-title font-extrabold tracking-brand text-brand desktop:flex"
    >
      우리동네체온계
    </Link>
  )
}

/** 가운데 주요 메뉴(홈 · 지도). 자리를 늘려 오른쪽 묶음을 끝으로 민다 */
export function HeaderCenterNav({
  current,
  navSearch,
}: {
  /** 지금 메뉴. 메뉴 밖 화면(없는 화면 404)이면 null 이다 */
  current: MainNavKey | null
  navSearch?: string | undefined
}) {
  return (
    <nav aria-label="주요 메뉴" className="hidden grow gap-1 desktop:flex">
      {HEADER_CENTER_NAV.map((item) => {
        const active = item.key === current
        return (
          <Link
            key={item.key}
            href={navHref(item.href, navSearch)}
            aria-current={active ? 'page' : undefined}
            className={navLinkClass(active)}
          >
            {item.label}
          </Link>
        )
      })}
    </nav>
  )
}

/**
 * 머리줄 오른쪽 끝의 `내 정보`. 링크는 회원 상태와 무관하게 `/me` 다 — 비회원은 내 정보 화면의 가드가 로그인(`?next=/me`)으로 보낸다
 * (`features/me/member-gate.ts`). 이름은 가운데 메뉴와 구분되는 `계정 메뉴` 다
 */
export function HeaderMeNav({
  current,
  navSearch,
}: {
  /** 지금 메뉴. 메뉴 밖 화면(없는 화면 404)이면 null 이다 */
  current: MainNavKey | null
  navSearch?: string | undefined
}) {
  const active = current === HEADER_ME_NAV.key
  return (
    <nav aria-label="계정 메뉴" className="hidden desktop:flex">
      <Link
        href={navHref(HEADER_ME_NAV.href, navSearch)}
        aria-current={active ? 'page' : undefined}
        className={navLinkClass(active)}
      >
        {HEADER_ME_NAV.label}
      </Link>
    </nav>
  )
}
