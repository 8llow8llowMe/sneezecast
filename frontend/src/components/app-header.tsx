import Link from 'next/link'

import clsx from 'clsx'

import { MAIN_NAV, type MainNavKey, navHref } from '@/lib/nav'

import { Button } from './button'
import { IconButton } from './icon-button'
import { BellIcon, ChevronDownIcon } from './icons'

export type AppHeaderProps = {
  /** 고른 행정동 이름. 예: "○○동" */
  regionName: string
  current: MainNavKey
  onRegionClick: () => void
  onNotificationClick: () => void
  onReportClick: () => void
  /** 보고 버튼 글자. 기본은 "이번 주 건강 보고하기", 비회원 홈은 "로그인하고 보고하기" (Home-guest 시안) */
  reportLabel?: string | undefined
  /** 데스크톱 메뉴 링크 뒤에 붙일 쿼리(앞 `?` 없이). 둘러보기 동네(`region=<코드>`)를 메뉴를 옮겨도 잃지 않게 한다 */
  navSearch?: string | undefined
  className?: string
}

/**
 * 앱 헤더. 폭에 따라 구성이 바뀐다.
 *
 * | 폭 | 높이 | 구성 |
 * | --- | --- | --- |
 * | 모바일 | 52 | 동네 · 알림 (보고 버튼은 화면 아래 고정 버튼) |
 * | 태블릿 | 72 | 동네 · 알림 · 보고 버튼 |
 * | 데스크톱 | 64 | 서비스명 · 동네 · 메뉴 · 알림 · 보고 버튼, 아래 구분선 |
 *
 * 시안: Home(모바일) · Tablet · Desktop, 비회원 Home-guest(-T · -D)
 */
export function AppHeader({
  regionName,
  current,
  onRegionClick,
  onNotificationClick,
  onReportClick,
  reportLabel = '이번 주 건강 보고하기',
  navSearch,
  className,
}: AppHeaderProps) {
  return (
    <header
      className={clsx(
        'flex shrink-0 items-center',
        'pt-2 pr-2 pl-3',
        'tablet:h-header-tablet tablet:gap-2 tablet:pt-0 tablet:pr-5 tablet:pl-4',
        'desktop:h-header-desktop desktop:gap-6 desktop:border-b desktop:border-divider desktop:px-8',
        className,
      )}
    >
      <span className="hidden text-screen-title font-extrabold tracking-brand text-brand desktop:inline">
        우리동네체온계
      </span>

      <button
        type="button"
        aria-label={`동네 바꾸기, 현재 ${regionName}`}
        onClick={onRegionClick}
        className="flex h-11 cursor-pointer items-center gap-1 px-2 text-screen-title font-bold text-fg desktop:text-section-title"
      >
        <span>{regionName}</span>
        <ChevronDownIcon />
      </button>

      <nav aria-label="주요 메뉴" className="hidden grow gap-1 desktop:flex">
        {MAIN_NAV.map((item) => {
          const active = item.key === current
          return (
            <Link
              key={item.key}
              href={navHref(item.href, navSearch)}
              aria-current={active ? 'page' : undefined}
              className={clsx(
                'flex h-11 items-center px-3.5 text-body',
                active ? 'font-bold text-brand' : 'font-medium text-fg-sub',
              )}
            >
              {item.label}
            </Link>
          )
        })}
      </nav>

      {/* 모바일 · 태블릿에서 알림 · 보고 버튼을 오른쪽으로 민다. 데스크톱은 메뉴가 늘어나 자리를 채운다 */}
      <span className="grow desktop:hidden" />

      <IconButton label="알림 설정" icon={<BellIcon />} onClick={onNotificationClick} />

      {/*
        모바일에서는 화면 아래 고정 버튼이 보고를 맡는다.
        Button 의 inline-flex 와 hidden 이 같은 display 를 다투지 않게 감싸는 요소로 숨긴다
        (Tailwind 는 클래스를 적은 순서가 아니라 CSS 생성 순서로 이긴다)
      */}
      <span className="hidden tablet:contents">
        <Button size="sm" onClick={onReportClick}>
          {reportLabel}
        </Button>
      </span>
    </header>
  )
}
