import type { ComponentPropsWithoutRef, ReactNode } from 'react'

import clsx from 'clsx'

export type IconButtonProps = Omit<
  ComponentPropsWithoutRef<'button'>,
  'aria-label' | 'children'
> & {
  /**
   * 버튼 이름. 아이콘만 있어 보이는 글자가 없으므로 **필수**다 — 빠뜨리면 스크린리더가
   * "버튼" 이라고만 읽는다. 타입으로 강제한다.
   */
  label: string
  icon: ReactNode
}

/**
 * 아이콘만 있는 버튼 (알림 설정 · 닫기 · 이전 단계). 터치 영역 44×44.
 *
 * 아이콘 색은 글자색을 따른다. 기본은 `text-fg` 다.
 */
export function IconButton({ label, icon, type = 'button', className, ...rest }: IconButtonProps) {
  return (
    <button
      type={type}
      aria-label={label}
      className={clsx(
        'inline-flex size-touch shrink-0 cursor-pointer items-center justify-center text-fg',
        className,
      )}
      {...rest}
    >
      {icon}
    </button>
  )
}
