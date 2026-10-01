import Link from 'next/link'

import clsx from 'clsx'

import { MAIN_NAV, type MainNavKey } from '@/lib/nav'

import { Skeleton } from './skeleton'

/**
 * 불러오는 중 · 오류 화면의 머리줄 (State-loading-T · -D, State-error-D). 동네 이름 · 보고 버튼은 아직 모르거나 쓸 수 없다.
 *
 * - `skeleton`(불러오는 중): 태블릿 · 데스크톱에 보인다. 동네 이름 · 보고 버튼 자리를 회색 막대로 둔다
 * - 아니면(오류): 데스크톱에만 보인다. 데스크톱은 탭바가 없어 다른 화면으로 갈 길(서비스명 · 메뉴)만 둔다
 *
 * 메뉴 링크는 `AppHeader` 의 데스크톱 메뉴와 같은 모양이다. 둘러보기 동네(`?region=`)는 이 화면에서 모르므로 붙이지 않는다.
 */
export function StateHeader({ current, skeleton }: { current: MainNavKey; skeleton: boolean }) {
  return (
    <header
      className={clsx(
        skeleton ? 'hidden tablet:flex' : 'hidden desktop:flex',
        'shrink-0 items-center border-b border-divider',
        'tablet:h-header-tablet tablet:gap-2 tablet:pr-5 tablet:pl-3',
        'desktop:h-header-desktop desktop:gap-6 desktop:px-8',
      )}
    >
      <span className="hidden text-screen-title font-extrabold tracking-brand text-brand desktop:inline">
        우리동네체온계
      </span>

      {/* 동네 이름 자리. 모바일 본문 첫 막대(State-loading 96×24)와 같은 크기 */}
      {skeleton && <Skeleton className="mx-2 h-6 w-24" />}

      <nav aria-label="주요 메뉴" className="hidden grow gap-1 desktop:flex">
        {MAIN_NAV.map((item) => {
          const active = item.key === current
          return (
            <Link
              key={item.key}
              href={item.href}
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

      {skeleton && (
        <>
          <span className="grow desktop:hidden" />
          <Skeleton shape="button" className="h-11 w-45" />
        </>
      )}
    </header>
  )
}
