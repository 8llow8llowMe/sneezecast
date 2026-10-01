import type { ReactNode } from 'react'

import clsx from 'clsx'

/**
 * 여백 배치.
 * - page: 화면 폭 섹션. 좌우 여백이 화면 여백 토큰(모바일 20 · 태블릿 24 · 데스크톱 32)을 따른다
 * - panel: 모바일에서는 page 와 같고, 태블릿부터는 격자 · 옆 패널 안에 들어가 여백이 없다 (Tablet · Desktop 시안의 홈)
 *
 * 같은 속성(padding)을 className 으로 덮어쓰지 않고 이 값으로 고른다 — 덮어쓰면 어느 쪽이 이길지 장담할 수 없다.
 */
export type SectionLayout = 'page' | 'panel'

const LAYOUT_CLASS: Record<SectionLayout, string> = {
  page: 'px-page-mobile pt-5 pb-4 tablet:px-page-tablet desktop:px-page-desktop',
  panel: 'px-page-mobile pt-5 pb-4 tablet:p-0 desktop:pt-1',
}

/**
 * 섹션 (Home 증상별 변화 · 우리 동네 안내). 제목 17px 굵게, 위 20 · 아래 16 여백.
 */
export function Section({
  title,
  layout = 'page',
  children,
  className,
}: {
  title: ReactNode
  layout?: SectionLayout
  children: ReactNode
  className?: string
}) {
  return (
    <section className={clsx('flex flex-col', LAYOUT_CLASS[layout], className)}>
      <h2 className="mb-1 text-section-title font-bold text-fg">{title}</h2>
      {children}
    </section>
  )
}

/** 섹션 사이 8px 회색 띠. 장식이라 보조기술에 읽히지 않는다 */
export function SectionBand({ className }: { className?: string }) {
  return <div aria-hidden="true" className={clsx('h-band shrink-0 bg-section', className)} />
}
