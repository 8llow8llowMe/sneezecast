import type { ReactNode } from 'react'

import clsx from 'clsx'

/**
 * 섹션 (Home 증상별 변화 · 우리 동네 안내). 제목 17px 굵게, 위 20 · 아래 16 여백.
 *
 * 좌우 여백은 화면 여백 토큰(모바일 20 · 태블릿 24 · 데스크톱 32)을 따른다.
 */
export function Section({
  title,
  children,
  className,
}: {
  title: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section
      className={clsx(
        'flex flex-col px-page-mobile pt-5 pb-4 tablet:px-page-tablet desktop:px-page-desktop',
        className,
      )}
    >
      <h2 className="mb-1 text-section-title font-bold text-fg">{title}</h2>
      {children}
    </section>
  )
}

/** 섹션 사이 8px 회색 띠. 장식이라 보조기술에 읽히지 않는다 */
export function SectionBand({ className }: { className?: string }) {
  return <div aria-hidden="true" className={clsx('h-band shrink-0 bg-section', className)} />
}
