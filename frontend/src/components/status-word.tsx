import clsx from 'clsx'

import { type RegionStatus, STATUS_LABEL, STATUS_TEXT_CLASS } from '@/lib/status'

/**
 * 상태 글자 라벨 (Home 상태 카드의 큰 글자). 26px, 데스크톱 28px.
 *
 * 상태는 항상 색과 글자를 함께 보인다 — 색만으로 구분하지 않는다 (design-guide.md "디자인 규칙").
 */
export function StatusWord({ status, className }: { status: RegionStatus; className?: string }) {
  return (
    <span
      className={clsx(
        'text-status leading-tight font-bold desktop:text-status-desktop',
        STATUS_TEXT_CLASS[status],
        className,
      )}
    >
      {STATUS_LABEL[status]}
    </span>
  )
}
