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
 * 아이콘 색은 글자색을 따른다. 기본은 `text-fg` 다. `aria-disabled` 면 흐리게 보인다.
 */
export function IconButton({ label, icon, type = 'button', className, ...rest }: IconButtonProps) {
  return (
    <button
      type={type}
      aria-label={label}
      className={clsx(
        'inline-flex size-touch shrink-0 cursor-pointer items-center justify-center text-fg',
        // aria-disabled: 포커스를 잃지 않게 꺼진 모양만 보인다(Button 과 같음). 누름은 부르는 쪽이 막는다
        'aria-disabled:cursor-not-allowed aria-disabled:opacity-disabled',
        className,
      )}
      {...rest}
    >
      {icon}
    </button>
  )
}
