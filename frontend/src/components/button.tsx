import type { ComponentPropsWithoutRef } from 'react'

import clsx from 'clsx'

export type ButtonVariant = 'primary' | 'secondary' | 'text' | 'subtle'
export type ButtonSize = 'lg' | 'sm'

export type ButtonProps = ComponentPropsWithoutRef<'button'> & {
  /**
   * primary: 네이비 주요 동작 · secondary: 회색 보조 동작 · text: 글자만 있는 낮은 강조 ·
   * subtle: 회색 글자만 있는 가장 낮은 강조 (Start "보고 없이 둘러보기")
   */
  variant?: ButtonVariant
  /** lg: 56px (하단 고정 · 시트 안 주요 동작) · sm: 44px (헤더 · 줄 안 동작) */
  size?: ButtonSize
  /** 부모 폭을 꽉 채운다 */
  fullWidth?: boolean
}

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: 'bg-brand text-bg rounded-button',
  secondary: 'bg-section text-fg rounded-button',
  text: 'bg-transparent text-brand',
  subtle: 'bg-transparent text-fg-sub',
}

/** 글자 버튼은 높이만 터치 영역(44)을 지키고 큰/작은 구분 없이 15px 이다 (Report-done-ok 시안) */
const SIZE_CLASS: Record<ButtonVariant, Record<ButtonSize, string>> = {
  primary: { lg: 'h-button px-5 text-section-title', sm: 'h-button-sm px-5 text-body' },
  secondary: { lg: 'h-button px-5 text-section-title', sm: 'h-button-sm px-5 text-body' },
  text: { lg: 'min-h-touch px-3 text-body', sm: 'min-h-touch px-3 text-body' },
  subtle: { lg: 'min-h-touch px-3 text-body', sm: 'min-h-touch px-3 text-body' },
}

/**
 * 시안의 버튼 3종 (Report-done-ok · Desktop 헤더).
 *
 * `className` 은 바깥 배치(여백 · 정렬)에만 쓴다. 색 · 크기 · 모서리는 variant · size 로 고른다.
 */
export function Button({
  variant = 'primary',
  size = 'lg',
  fullWidth = false,
  type = 'button',
  className,
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      className={clsx(
        'inline-flex shrink-0 cursor-pointer items-center justify-center font-semibold',
        'disabled:cursor-not-allowed disabled:opacity-disabled',
        VARIANT_CLASS[variant],
        SIZE_CLASS[variant][size],
        fullWidth && 'w-full',
        className,
      )}
      {...rest}
    />
  )
}
