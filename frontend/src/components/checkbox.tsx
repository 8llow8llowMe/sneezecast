import type { ComponentPropsWithoutRef, ReactNode } from 'react'

import clsx from 'clsx'

import { CheckIcon } from './icons'

export type CheckboxProps = Omit<
  ComponentPropsWithoutRef<'input'>,
  'type' | 'children' | 'size' | 'checked' | 'className'
> & {
  checked: boolean
  label: ReactNode
  /** lg: 17px 굵게 (성인 확인 · 전체 동의) · md: 15px (동의 항목) */
  size?: 'lg' | 'md'
  className?: string
}

/**
 * 체크 상자 (Setup-2 성인 확인 · Setup-3 동의 시안). 줄 높이 52, 상자 24 · 모서리 6.
 *
 * 네이티브 `<input type="checkbox">` 를 보이지 않게 두고 모양만 그린다 — 스페이스 키 · 스크린리더의
 * "체크 상자, 선택됨" 을 브라우저가 맡는다. 줄 전체가 `<label>` 이라 글자를 눌러도 바뀐다.
 * 상태는 부모가 갖는다(`checked` · `onChange`).
 */
export function Checkbox({ checked, label, size = 'lg', className, ...rest }: CheckboxProps) {
  return (
    <label
      className={clsx(
        'flex min-h-13 cursor-pointer items-center gap-3 text-fg has-disabled:cursor-not-allowed has-disabled:opacity-disabled',
        className,
      )}
    >
      <input type="checkbox" checked={checked} className="peer sr-only" {...rest} />
      <span
        aria-hidden="true"
        className={clsx(
          'flex size-6 shrink-0 items-center justify-center rounded-checkbox border-selected',
          'peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-brand',
          checked ? 'border-brand bg-brand text-bg' : 'border-muted-bar bg-bg',
        )}
      >
        {checked && <CheckIcon size={14} strokeWidth={3.2} />}
      </span>
      <span
        className={clsx(
          'leading-[1.45]',
          size === 'lg' ? 'text-section-title font-bold' : 'text-body font-medium',
        )}
      >
        {label}
      </span>
    </label>
  )
}
