import type { ReactNode } from 'react'
import Link from 'next/link'

import clsx from 'clsx'

import { MAIN_NAV, type MainNavKey, navHref } from '@/lib/nav'

import { HomeIcon, MapIcon, UserIcon } from './icons'

const NAV_ICON: Record<MainNavKey, ReactNode> = {
  home: <HomeIcon />,
  map: <MapIcon />,
  me: <UserIcon />,
}

/**
 * 하단 탭바 (Home · Tablet 시안). 모바일 60 · 태블릿 64, 데스크톱에서는 숨기고 헤더 메뉴를 쓴다.
 *
 * 현재 메뉴는 색 · 굵기와 함께 `aria-current="page"` 로 알린다.
 * 아래 홈 인디케이터 영역만큼 높이를 늘린다(`pb-safe`). 홈 화면에 추가한 PWA 에서 아이콘이 가려지지 않는다.
 * `navSearch` 는 모든 링크 뒤에 붙일 쿼리다(앞 `?` 없이, 예: 둘러보기 동네 `region=<코드>`).
 */
export function TabBar({
  current,
  navSearch,
  className,
}: {
  /** 지금 메뉴. 메뉴 밖 화면(없는 화면 404)이면 null 이다 — 표시하지 않는다 */
  current: MainNavKey | null
  navSearch?: string | undefined
  className?: string
}) {
  return (
    <nav
      aria-label="주요 메뉴"
      className={clsx(
        'flex shrink-0 border-t border-divider bg-bg pb-safe desktop:hidden',
        className,
      )}
    >
      {MAIN_NAV.map((item) => {
        const active = item.key === current
        return (
          <Link
            key={item.key}
            href={navHref(item.href, navSearch)}
            aria-current={active ? 'page' : undefined}
            className={clsx(
              'flex h-tab-bar flex-1 basis-0 flex-col items-center justify-center gap-0.75 text-tab tablet:h-tab-bar-tablet',
              active ? 'font-bold text-brand' : 'font-medium text-fg-muted',
            )}
          >
            {NAV_ICON[item.key]}
            <span>{item.label}</span>
          </Link>
        )
      })}
    </nav>
  )
}
