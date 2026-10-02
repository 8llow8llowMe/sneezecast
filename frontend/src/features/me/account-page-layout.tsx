'use client'

import type { ReactNode } from 'react'

import { AppHeader } from '@/components/app-header'
import { Button } from '@/components/button'
import { IconButton } from '@/components/icon-button'
import { BellIcon, ChevronLeftIcon } from '@/components/icons'
import { TabBar } from '@/components/tab-bar'

export type AccountPageLayoutProps = {
  /** 화면 제목. 모바일 · 태블릿은 머리줄, 데스크톱은 본문 위 제목이다 — 폭마다 하나만 `h1` 로 읽힌다 */
  title: string
  /** 데스크톱 머리줄의 동네 이름 */
  regionName: string
  /** 탭바 · 데스크톱 메뉴 링크 뒤 쿼리(동네) */
  navSearch?: string | undefined
  /** 머리줄 뒤로(모바일 · 태블릿)와 데스크톱 `내 정보` 돌아가기가 함께 부른다 */
  onBack: () => void
  /** 뒤로를 꺼진 모양으로 둔다(`aria-disabled`, 포커스는 남는다). 누름은 `onBack` 이 막는다 — 보내는 중 */
  backDisabled?: boolean | undefined
  onRegionClick: () => void
  onNotificationClick: () => void
  onReportClick: () => void
  /** 머리줄 보고 버튼 글자 (태블릿 · 데스크톱). 화면이 `reportButtonLabel` 로 회원 상태 · 보낸 보고에 맞춰 넘긴다 */
  reportLabel: string
  /** 화면 아래 버튼 영역. 모바일은 아래 고정, 태블릿 · 데스크톱은 본문 바로 아래(위 8) */
  footer?: ReactNode
  children: ReactNode
}

/**
 * 내 정보 아래 계정 화면(Settings-devices · Settings-password)의 틀. 폭에 따라 구성이 바뀐다.
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 머리줄 56(뒤로 · 제목 17) → 본문(위 8 · 옆 20, 간격 20) → 아래 고정 버튼 영역. 탭바 없음 |
 * | 태블릿 | 머리줄 72(뒤로 · 제목 20 · 알림 · 보고 버튼, 아래 구분선) → 가운데 560 본문(위아래 28) → 탭바 |
 * | 데스크톱 | 홈과 같은 머리줄 → 가운데 560(위아래 24): `내 정보` 돌아가기 → 제목 26 → 본문. 왼쪽 설정 메뉴 없음 |
 *
 * 데스크톱의 `내 정보` 는 시안의 `#me` 링크 대신 뒤로와 같은 버튼이다 — 링크로 가면 기록이 쌓여 뒤로 가기가 이 화면으로 돌아온다.
 */
export function AccountPageLayout({
  title,
  regionName,
  navSearch,
  onBack,
  backDisabled,
  onRegionClick,
  onNotificationClick,
  onReportClick,
  reportLabel,
  footer,
  children,
}: AccountPageLayoutProps) {
  return (
    <div className="flex min-h-dvh flex-col">
      {/* 데스크톱 머리줄은 홈과 같다. AppHeader 의 flex 와 다투지 않게 감싸는 요소로 숨긴다 */}
      <div className="hidden desktop:block">
        <AppHeader
          regionName={regionName}
          current="me"
          onRegionClick={onRegionClick}
          onNotificationClick={onNotificationClick}
          onReportClick={onReportClick}
          reportLabel={reportLabel}
          navSearch={navSearch}
        />
      </div>

      <header className="flex h-14 shrink-0 items-center gap-1 pr-4 pl-2 tablet:h-header-tablet tablet:gap-2 tablet:border-b tablet:border-divider tablet:pr-5 tablet:pl-3 desktop:hidden">
        <IconButton
          label="뒤로"
          icon={<ChevronLeftIcon />}
          onClick={onBack}
          aria-disabled={backDisabled || undefined}
        />
        <h1 className="grow text-section-title font-bold text-fg tablet:grow-0 tablet:text-screen-title">
          {title}
        </h1>
        <span className="hidden grow tablet:block" />
        <span className="hidden tablet:contents">
          <IconButton label="알림 설정" icon={<BellIcon />} onClick={onNotificationClick} />
          <Button size="sm" onClick={onReportClick}>
            {reportLabel}
          </Button>
        </span>
      </header>

      <div className="flex grow flex-col tablet:items-center tablet:px-10 tablet:py-7 desktop:px-8 desktop:py-6">
        <div className="flex w-full grow flex-col tablet:max-w-140 tablet:grow-0 tablet:gap-5">
          <div className="hidden flex-col gap-5 desktop:flex">
            <button
              type="button"
              // 머리줄 메뉴 · 탭바의 "내 정보" 링크와 구분한다. 보이는 글자를 이름 앞에 그대로 둔다
              aria-label="내 정보로 돌아가기"
              onClick={onBack}
              aria-disabled={backDisabled || undefined}
              className="flex min-h-touch cursor-pointer items-center gap-1 self-start text-body font-semibold text-fg-sub aria-disabled:cursor-not-allowed aria-disabled:opacity-disabled"
            >
              <ChevronLeftIcon className="text-fg" />
              <span>내 정보</span>
            </button>
            <h1 className="text-status font-bold text-fg">{title}</h1>
          </div>

          <main className="flex grow flex-col gap-5 px-page-mobile pt-2 tablet:grow-0 tablet:p-0">
            {children}
          </main>

          {footer != null && (
            <div className="sticky bottom-0 flex flex-col gap-1.5 bg-bg px-page-mobile pt-3 pb-sheet tablet:static tablet:mt-2 tablet:p-0">
              {footer}
            </div>
          )}
        </div>
      </div>

      {/* 탭바는 태블릿만. 모바일 시안은 탭바 없이 아래 버튼을 두고, 데스크톱은 머리줄 메뉴를 쓴다 */}
      <div className="sticky bottom-0 hidden tablet:block">
        <TabBar current="me" navSearch={navSearch} />
      </div>
    </div>
  )
}
