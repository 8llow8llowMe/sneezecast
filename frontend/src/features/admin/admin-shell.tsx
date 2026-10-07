'use client'

import type { ReactNode } from 'react'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'

import clsx from 'clsx'

import { MOCK_AUTH_PARAM, MOCK_ROLE_PARAM } from '@/features/auth/mock-params'
import { HOME_PATH } from '@/features/onboarding/paths'
import { APP_NAME } from '@/lib/app-info'
import { navHref } from '@/lib/nav'

import { ADMIN_REVIEW_PATH } from './types'

/** 메뉴 사이를 오갈 때 남기는 QA 덮어쓰기(목데이터 모드에서만 듣는다). 빠지면 비회원 · 일반 회원으로 보여 가드가 막는다 */
function adminSearch(searchParams: Pick<URLSearchParams, 'get'>): string {
  const params = new URLSearchParams()
  for (const name of [MOCK_AUTH_PARAM, MOCK_ROLE_PARAM]) {
    const value = searchParams.get(name)
    if (value) params.set(name, value)
  }
  return params.toString()
}

const MENU_ITEM = 'flex h-11 items-center justify-between gap-2 rounded-button px-3 text-body'

/**
 * 운영자 화면 틀 (시안 Admin 의 머리줄 · 왼쪽 메뉴). 데스크톱 시안만 있다.
 *
 * - 머리줄: 서비스명(홈 링크) · `운영자` 배지 · 오른쪽 기준 주. 시안의 기준 주는 고르는 버튼이지만 지금은 이번 주만 보여(다른 주 조회는
 *   #214 계약에 없다) 글자로 둔다
 * - 메뉴: `검토 대기`(남은 후보 수) · `발행 이력` · `기준 설정`. 발행 이력(A03)은 #220, 기준 설정은 시안 · 화면이 없어 **누를 수 없는
 *   글자**로 둔다(링크로 두면 없는 화면 404 로 간다). #220 에서 발행 이력을 링크로 바꾼다
 * - 좁은 화면(데스크톱 미만)은 메뉴를 머리줄 아래 한 줄로 두고 본문을 한 단으로 쌓는다(시안 없음)
 *
 * 시안은 `box-sizing` 이 content-box 라 메뉴 폭 220 에 안쪽 여백 32 · 테두리 1 이 더해진 253 이다(배지도 24 + 테두리 3). 보이는 크기를 맞춘다.
 */
export function AdminShell({
  weekLabel,
  pendingCount,
  children,
}: {
  /** 머리줄의 기준 주 (`11월 3주`). 모르면 그리지 않는다 */
  weekLabel?: string | null
  /** `검토 대기` 옆 수 — 아직 처리하지 않은 후보(보류 제외). 모르면 그리지 않는다 */
  pendingCount?: number | null
  children: ReactNode
}) {
  const search = adminSearch(useSearchParams())
  return (
    <div className="flex min-h-dvh flex-col bg-bg text-fg">
      <header className="flex h-header-desktop shrink-0 items-center gap-3 border-b border-divider px-page-mobile tablet:px-page-tablet desktop:px-page-desktop">
        <Link
          href={navHref(HOME_PATH, search)}
          // 아주 좁은 화면(320)에서 배지 · 기준 주를 밀어내지 않게 서비스명이 줄어든다(말줄임)
          className="min-w-0 truncate text-screen-title font-extrabold tracking-brand text-brand"
        >
          {APP_NAME}
        </Link>
        <span className="inline-flex h-6.75 shrink-0 items-center rounded-chip border-emphasis border-brand px-2 text-caption font-semibold text-brand">
          운영자
        </span>
        <span className="grow" />
        {weekLabel && (
          <span className="shrink-0 text-body-strong font-semibold whitespace-nowrap text-fg">
            {weekLabel}
          </span>
        )}
      </header>
      <div className="flex grow flex-col desktop:flex-row">
        <nav
          aria-label="운영 메뉴"
          className="flex shrink-0 flex-wrap gap-1 border-b border-divider px-page-mobile py-2 tablet:px-page-tablet desktop:w-63 desktop:flex-col desktop:flex-nowrap desktop:border-r desktop:border-b-0 desktop:px-4 desktop:py-5"
        >
          <Link
            href={navHref(ADMIN_REVIEW_PATH, search)}
            aria-current="page"
            className={clsx(MENU_ITEM, 'bg-section font-bold text-brand')}
          >
            <span>검토 대기</span>
            {pendingCount != null && (
              // 화면 읽기에는 "검토 대기, 남은 후보 4건" 으로 읽힌다(숫자만 붙으면 "검토 대기4")
              <span className="text-sub">
                <span className="sr-only">, 남은 후보 </span>
                {pendingCount}
                <span className="sr-only">건</span>
              </span>
            )}
          </Link>
          <DisabledMenuItem label="발행 이력" />
          <DisabledMenuItem label="기준 설정" />
        </nav>
        <main className="flex min-w-0 grow flex-col gap-5 px-page-mobile py-6 tablet:px-page-tablet desktop:px-page-desktop">
          {children}
        </main>
      </div>
    </div>
  )
}

/**
 * 아직 화면이 없는 메뉴. 링크가 아니라 꺼진 모양의 글자이고, 화면 읽기에는 `(준비 중)` 이 붙어 읽힌다.
 * `aria-disabled` 는 두지 않는다 — 역할이 없는 글자(span)에는 뜻이 없다
 */
function DisabledMenuItem({ label }: { label: string }) {
  return (
    <span
      className={clsx(MENU_ITEM, 'cursor-not-allowed font-medium text-fg-sub opacity-disabled')}
    >
      {label}
      <span className="sr-only"> (준비 중)</span>
    </span>
  )
}
