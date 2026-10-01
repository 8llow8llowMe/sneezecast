import type { ReactNode } from 'react'

import clsx from 'clsx'

/**
 * - data: 데이터 행 (증상별 변화) — 제목 15px 굵게, 보조 문구 13px
 * - link: 이동 행 (공식 정보) — 제목 15px 보통, 보조 문구 12px
 */
export type ListRowKind = 'data' | 'link'

export type ListRowProps = {
  kind?: ListRowKind
  /** 앞쪽 요소 (배지 등) */
  leading?: ReactNode
  title: ReactNode
  /** 제목 아래 보조 문구 (회색). 상태색이 필요하면 색 클래스를 단 요소로 감싸 넘긴다 */
  description?: ReactNode
  /** 뒤쪽 요소 (작은 막대 그래프 · 화살표 등) */
  trailing?: ReactNode
  /** 아래 1px 구분선. 섹션 안 목록은 켜고, 회색 띠 사이 단독 행은 끈다 */
  divider?: boolean
  className?: string
}

const TEXT_CLASS: Record<ListRowKind, { box: string; title: string; description: string }> = {
  data: { box: 'gap-0.5', title: 'font-semibold', description: 'text-sub font-medium' },
  link: { box: 'gap-px', title: 'font-medium', description: 'text-caption' },
}

/**
 * 리스트 행 (Home 증상별 변화 · 공식 정보 행). 최소 높이 56px.
 *
 * 카드는 홈 상태 카드 하나뿐이다. 나머지 목록은 이 행 + 1px 구분선으로 만든다 (design-guide.md).
 * 좌우 여백은 부모(섹션)가 정한다. 링크·버튼으로 감쌀 때는 이 행을 그 안에 넣는다.
 */
export function ListRow({
  kind = 'data',
  leading,
  title,
  description,
  trailing,
  divider = false,
  className,
}: ListRowProps) {
  return (
    <div
      className={clsx(
        'flex min-h-14 items-center justify-between gap-2.5',
        divider && 'border-b border-divider',
        className,
      )}
    >
      {leading}
      <span className={clsx('flex min-w-0 grow flex-col', TEXT_CLASS[kind].box)}>
        <span className={clsx('text-body text-fg', TEXT_CLASS[kind].title)}>{title}</span>
        {description != null && (
          <span className={clsx('text-fg-sub', TEXT_CLASS[kind].description)}>{description}</span>
        )}
      </span>
      {trailing}
    </div>
  )
}
