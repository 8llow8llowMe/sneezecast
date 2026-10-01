import type { ReactNode } from 'react'
import Link from 'next/link'

import clsx from 'clsx'

import { MAIN_NAV, type MainNavKey } from '@/lib/nav'

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
 */
export function TabBar({ current, className }: { current: MainNavKey; className?: string }) {
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
            href={item.href}
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
