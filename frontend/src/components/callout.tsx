import type { ReactNode } from 'react'

import clsx from 'clsx'

/**
 * 안내 상자.
 * - info: 연한 파랑 바탕 · 네이비 글자 (보고 수정 안내 — Report-edit, 공개 기준 도달 — Flow)
 * - neutral: 회색 바탕 · 기본 글자 (홈 상단 알림 — Home)
 *
 * 상태가 바뀌어 새로 나타나는 안내라 `role="status"` 로 읽힌다. 모서리 12, 글자 14px 중간 굵기.
 */
export type CalloutTone = 'info' | 'neutral'

const TONE_CLASS: Record<CalloutTone, string> = {
  info: 'bg-info-bg text-brand',
  neutral: 'bg-section text-fg',
}

export function Callout({
  tone = 'info',
  icon,
  children,
  className,
}: {
  tone?: CalloutTone
  /** 앞쪽 아이콘 (장식) */
  icon?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <div
      role="status"
      className={clsx(
        'flex items-center gap-2 rounded-button px-3.5 py-3 text-body-strong leading-normal font-medium',
        TONE_CLASS[tone],
        className,
      )}
    >
      {icon}
      <span>{children}</span>
    </div>
  )
}
