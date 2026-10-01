import clsx from 'clsx'

import type { MainNavKey } from '@/lib/nav'

import { Skeleton } from './skeleton'
import { StateHeader } from './state-header'
import { TabBar } from './tab-bar'

/**
 * 불러오는 중 (State-loading · -T · -D). 홈 모양의 회색 막대만 그린다 — 숫자 · 상태색 · 문구를 미리 보이지 않는다.
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 동네 · 주 막대, 상태 카드, 버튼, 목록 막대 + 하단 버튼 자리 + 탭바 |
 * | 태블릿 | 머리줄(동네 · 보고 버튼 자리) + 2열 격자(상태 카드 · 목록) + 지도 자리 + 탭바 |
 * | 데스크톱 | 머리줄(서비스명 · 메뉴) + 왼쪽 지도 자리 + 오른쪽 420 패널 |
 *
 * `nav` 는 지금 메뉴다. 기다리는 동안에도 탭바 · 머리줄로 다른 메뉴로 갈 수 있다. 하단 버튼 자리는 홈에만 둔다(홈만 하단 보고 버튼이 있다).
 * 로딩 경계는 주요 메뉴 화면에만 둔다(`app/(home)/loading.tsx` · `app/me/loading.tsx`) — 루트에 두면 `notFound()` 가 200 이 된다.
 *
 * 감싸는 영역이 `role="status"` 라 스크린리더가 "불러오고 있어요" 를 읽는다. 막대는 장식이라 숨긴다.
 */
export function LoadingState({ nav }: { nav: MainNavKey }) {
  return (
    <div className="flex min-h-dvh flex-col bg-bg">
      <StateHeader current={nav} skeleton />

      <main role="status" aria-label="불러오는 중" className="flex grow flex-col">
        <span className="sr-only">불러오고 있어요</span>
        <MobileBody />
        <TabletBody />
        <DesktopBody />
      </main>

      <div className="sticky bottom-0 bg-bg">
        {nav === 'home' && (
          <div className="border-t border-divider px-page-mobile py-3 tablet:hidden">
            <Skeleton shape="button" className="h-button" />
          </div>
        )}
        <TabBar current={nav} />
      </div>
    </div>
  )
}

/** 상태 카드 자리. 회색 카드 안에 제목 · 상태 · 게이지 · 설명 막대 */
function CardSkeleton({
  wordWidth,
  withMeta,
  className,
}: {
  wordWidth: string
  withMeta: boolean
  className: string
}) {
  return (
    <div className={clsx('flex flex-col gap-3.5 rounded-card bg-section', className)}>
      <Skeleton className="h-3.5 w-30" />
      <Skeleton className={clsx('h-7.5', wordWidth)} />
      <Skeleton className="h-4.5 w-full" />
      {withMeta && <Skeleton className="h-3.5 w-7/10" />}
    </div>
  )
}

/** State-loading (390) */
function MobileBody() {
  return (
    <div className="flex flex-col gap-3 p-5 tablet:hidden">
      <Skeleton className="h-6 w-24" />
      <Skeleton className="h-3.5 w-35" />
      <CardSkeleton wordWidth="w-40" withMeta className="mt-2 p-5" />
      <Skeleton shape="button" className="h-14 w-full" />
      <div className="mt-3 flex flex-col gap-3.5">
        <Skeleton className="h-4.5 w-22.5" />
        <Skeleton className="h-11 w-full" />
        <Skeleton className="h-11 w-full" />
      </div>
    </div>
  )
}

/** State-loading-T (834) */
function TabletBody() {
  return (
    <div className="hidden grow flex-col gap-6 p-6 tablet:flex desktop:hidden">
      <div className="grid grid-cols-2 gap-7">
        <CardSkeleton wordWidth="w-45" withMeta={false} className="p-6" />
        <div className="flex flex-col gap-3.5">
          <Skeleton className="h-4.5 w-22.5" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      </div>
      <div aria-hidden="true" className="grow rounded-card bg-section" />
    </div>
  )
}

/** State-loading-D (1440) */
function DesktopBody() {
  return (
    <div className="hidden grow gap-8 px-8 py-6 desktop:flex">
      <div aria-hidden="true" className="grow rounded-card bg-section" />
      <div className="flex w-105 shrink-0 flex-col gap-3.5">
        <CardSkeleton wordWidth="w-45" withMeta className="p-5" />
        <Skeleton className="h-4.5 w-22.5" />
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-4.5 w-22.5" />
        <Skeleton className="h-12 w-full" />
      </div>
    </div>
  )
}
