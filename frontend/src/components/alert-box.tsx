import type { ReactNode } from 'react'

import clsx from 'clsx'

import { InfoIcon } from './icons'

/**
 * 알림 상자 (docs/design/auth 의 Login · Login-email · Signup-code …). 모서리 12, 글자 14 중간 굵기.
 * - danger: 연한 빨강 바탕 · 빨강 아이콘 — 실패를 알린다(`role="alert"`, 나타나는 즉시 읽는다)
 * - info: 연한 파랑 바탕 · 네이비 글자 — 안내(`role="status"`)
 * - neutral: 회색 바탕 · 회색 아이콘 — 잠시 막힘 같은 상태(`role="status"`)
 *
 * 홈 상단의 한 줄 안내는 `Callout` 이다. 이 상자는 여러 줄 문장과 아래 동작 버튼(`action`)을 담는다.
 */
export type AlertBoxTone = 'danger' | 'info' | 'neutral'

const TONE_CLASS: Record<AlertBoxTone, { box: string; icon: string }> = {
  danger: { box: 'bg-danger-bg text-fg', icon: 'text-danger' },
  info: { box: 'bg-info-bg text-brand', icon: 'text-brand' },
  neutral: { box: 'bg-section text-fg', icon: 'text-fg-sub' },
}

export function AlertBox({
  tone,
  children,
  action,
  role,
  className,
}: {
  tone: AlertBoxTone
  /**
   * 읽는 방식을 바꾼다. 기본은 danger 만 alert 다. 새로 나타나는 상자는 status 영역이면 첫 알림을 놓칠 수 있어
   * (docs/conventions.md "토스트 영역") 나타날 때 꼭 읽혀야 하는 neutral · info 상자는 `alert` 로 준다
   */
  role?: 'alert' | 'status'
  children: ReactNode
  /** 문장 아래 동작 (예: kakao-exists 의 "이메일로 로그인") */
  action?: ReactNode
  className?: string
}) {
  return (
    <div
      role={role ?? (tone === 'danger' ? 'alert' : 'status')}
      className={clsx(
        'flex flex-col gap-3 rounded-button px-4 py-3.5',
        TONE_CLASS[tone].box,
        className,
      )}
    >
      <div className="flex gap-2 text-body-strong leading-[1.55] font-medium">
        <InfoIcon className={clsx('mt-px shrink-0', TONE_CLASS[tone].icon)} />
        <span>{children}</span>
      </div>
      {action}
    </div>
  )
}
