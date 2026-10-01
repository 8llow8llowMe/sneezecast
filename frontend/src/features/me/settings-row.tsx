'use client'

import { type ReactNode, useId } from 'react'
import Link from 'next/link'

import { ChevronRightIcon } from '@/components/icons'
import { ListRow } from '@/components/list-row'

/**
 * 내 정보의 섹션 (Settings 시안). 제목 17 굵게 · 아래 4, 섹션 사이 간격은 부모가 둔다.
 * `id` 는 데스크톱 왼쪽 설정 메뉴의 바로가기 대상이다. 바로가기가 제목(`<id>-title`)으로 포커스를 옮기므로 제목은
 * `tabIndex={-1}` 이다(탭 순서에는 들지 않는다). 제목이 없는 섹션(로그아웃 · 탈퇴)은 `label` 로 이름을 준다.
 */
export function SettingsSection({
  id,
  title,
  label,
  children,
}: {
  id?: string
  title?: string
  label?: string
  children: ReactNode
}) {
  return (
    <section id={id} aria-label={title ? undefined : label} className="flex scroll-mt-8 flex-col">
      {title && (
        <h2
          id={id && sectionTitleId(id)}
          tabIndex={id ? -1 : undefined}
          className="mb-1 text-section-title font-bold text-fg focus:outline-none"
        >
          {title}
        </h2>
      )}
      {children}
    </section>
  )
}

/** 섹션 제목의 id. 설정 메뉴 바로가기가 포커스를 옮길 곳이다 */
export function sectionTitleId(sectionId: string): string {
  return `${sectionId}-title`
}

type MenuRowProps = {
  title: ReactNode
  description?: string | undefined
  /** 오른쪽 값 (닉네임 · 동네 이름 · 보관 기간). 예시 수치(기기 3대 · 관심 동네 2곳)는 지어내지 않고 비운다 */
  value?: string | undefined
  /** 누르면 부른다. `href` 와 함께 쓰지 않는다 */
  onClick?: () => void
  /** 다른 화면으로 가는 링크 */
  href?: string
  /** 오른쪽 화살표. 대화상자를 여는 행(로그아웃 · 탈퇴 · 동의 철회)은 시안대로 없다 */
  chevron?: boolean
  /** 꺼진 버튼 행(`aria-disabled` — 포커스를 지킨다). 눌러도 `onClick` 을 부르지 않는다 */
  disabled?: boolean
}

/**
 * 설정 메뉴 행. 최소 높이 56 · 아래 구분선. `onClick` 이면 버튼, `href` 면 링크, 둘 다 없으면 보기만 하는 행(로그인 방법)이다.
 *
 * 값이 길면(긴 이메일) 제목은 줄이지 않고(`ListRow` menu 의 `shrink-0`) 값을 한 줄로 자른다(`truncate`).
 * 잘려도 글자는 DOM 에 다 있어 보조기술은 전체를 읽고, 마우스를 올리면 `title` 로 전체가 보인다.
 */
export function MenuRow({
  title,
  description,
  value,
  onClick,
  href,
  chevron = true,
  disabled = false,
}: MenuRowProps) {
  const interactive = onClick !== undefined || href !== undefined
  const row = (
    <ListRow
      kind="menu"
      divider
      title={title}
      description={description}
      trailing={
        (value !== undefined || (interactive && chevron)) && (
          <span className="flex min-w-0 items-center gap-1 text-body-strong text-fg-sub">
            {value !== undefined && (
              <span className="truncate" title={value}>
                {value}
              </span>
            )}
            {interactive && chevron && <ChevronRightIcon className="shrink-0 text-fg-muted" />}
          </span>
        )
      }
    />
  )
  if (href !== undefined) {
    return (
      <Link href={href} className="block">
        {row}
      </Link>
    )
  }
  if (onClick !== undefined) {
    return (
      <button
        type="button"
        aria-disabled={disabled || undefined}
        onClick={() => {
          if (!disabled) onClick()
        }}
        className="block w-full cursor-pointer text-left aria-disabled:cursor-not-allowed aria-disabled:opacity-disabled"
      >
        {row}
      </button>
    )
  }
  return row
}

/**
 * 알림 켜기 행 (Settings 시안의 스위치, 높이 64).
 *
 * **PWA 푸시는 2단계 기능이라 아직 구독하지 않는다.** 시안은 켜진 모양이지만 실제로 받지 않는 알림을 켜진 것처럼 보이지 않게
 * 꺼진 모양(`aria-checked=false` · `aria-disabled`, Settings-nopush 의 회색)으로 그리고, 누르면 `onClick` 으로 준비 중을 알린다.
 * 이 기기에서 알림을 받을 수 없으면(Settings-nopush) `onClick` 을 넘기지 않는다 — 눌러도 아무 일이 없다.
 * 설명(월요일 아침 등)은 `aria-describedby` 로 스위치에 잇는다.
 */
export function SwitchRow({
  title,
  description,
  onClick,
}: {
  title: string
  description: string
  /** 누르면 부른다. 없으면 눌러도 아무 일이 없다(알림 미지원) */
  onClick?: (() => void) | undefined
}) {
  const descriptionId = useId()
  return (
    <div className="flex min-h-16 items-center justify-between gap-3 border-b border-divider">
      <span className="flex flex-col gap-0.5">
        <span className="text-body font-medium text-fg">{title}</span>
        <span id={descriptionId} className="text-sub text-fg-sub">
          {description}
        </span>
      </span>
      <button
        type="button"
        role="switch"
        aria-checked={false}
        aria-disabled="true"
        aria-label={title}
        aria-describedby={descriptionId}
        onClick={onClick}
        className="relative h-7 w-12 shrink-0 cursor-not-allowed rounded-chip bg-inactive-bar opacity-disabled"
      >
        <span className="absolute top-0.5 left-0.5 size-6 rounded-chip bg-bg" />
      </button>
    </div>
  )
}
