import { type ComponentPropsWithoutRef, type ReactNode, useId } from 'react'

import clsx from 'clsx'

import { CheckIcon } from './icons'

export type ChoiceButtonProps = Omit<ComponentPropsWithoutRef<'button'>, 'children'> & {
  label: ReactNode
  /**
   * 선택지 아래 13px 보조 문구. 예: "두통, 근육통 등 · 위 두 가지와 함께 고를 수 없어요"
   * 버튼 이름이 아니라 설명(`aria-describedby`)으로 읽힌다 — 이름은 짧게, 조건은 덧붙여 읽는다.
   */
  hint?: ReactNode
  /**
   * 여러 개 고르는 선택지면 선택 여부를 준다 — `aria-pressed` 로 알리고, 켜지면 2px 네이비 테두리와 체크를 그린다.
   * 누르면 바로 다음으로 넘어가는 선택지(증상 없었어요 · 증상이 있었어요)는 주지 않는다.
   */
  selected?: boolean
  /** lg: 64px (보고 첫 단계) · md: 56px (증상 고르기) */
  size?: 'lg' | 'md'
}

/**
 * 보고 선택지 버튼 (Report-start · Report-symptom 시안). 흰 바탕, 모서리 12, 글자 17px.
 *
 * 테두리 두께가 바뀌어도(1px → 2px) 글자가 밀리지 않게 왼쪽 여백을 1px 줄여 맞춘다.
 */
export function ChoiceButton({
  label,
  hint,
  selected,
  size = 'lg',
  type = 'button',
  className,
  ...rest
}: ChoiceButtonProps) {
  const toggle = selected !== undefined
  const hintId = useId()

  return (
    <button
      type={type}
      aria-pressed={toggle ? selected : undefined}
      aria-describedby={hint != null ? hintId : undefined}
      className={clsx(
        'flex w-full cursor-pointer items-center justify-between gap-3 rounded-button bg-bg text-left text-section-title font-semibold text-fg',
        size === 'lg' ? 'min-h-16' : 'min-h-14',
        selected
          ? 'border-selected border-brand px-4.25'
          : 'border-hairline border-inactive-bar px-4.5',
        className,
      )}
      {...rest}
    >
      <span className="flex flex-col gap-0.5">
        <span>{label}</span>
        {hint != null && (
          <span id={hintId} className="text-sub font-medium text-fg-sub">
            {hint}
          </span>
        )}
      </span>
      {selected && <CheckIcon className="shrink-0 text-brand" />}
    </button>
  )
}
