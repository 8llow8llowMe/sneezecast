'use client'

import { type ComponentProps, type ReactNode, useId, useState } from 'react'

import clsx from 'clsx'

export type TextFieldProps = Omit<ComponentProps<'input'>, 'id' | 'className' | 'children'> & {
  /** 칸 위 라벨 (14 굵게). 입력칸 이름으로 읽힌다 */
  label: ReactNode
  /** 칸 아래 한 줄 도움말 (13). 오류가 있으면 오류가 대신 보인다. 빈 문자열은 없는 것과 같다 */
  hint?: string | undefined
  /**
   * 칸 아래 한 줄 오류 (13 · danger). 있으면 테두리가 2px danger 가 되고 `aria-invalid` 가 켜진다.
   * 문자열만 받는다 — `false` · `0` 같은 값이 오류로 그려지지 않게 하려는 것이다. 빈 문자열은 오류가 없는 것이다
   */
  error?: string | undefined
  className?: string
}

/**
 * 글자 입력칸 (docs/design/auth/README.md "입력칸"). 높이 52 · 모서리 12.
 *
 * | 상태 | 테두리 |
 * | --- | --- |
 * | 기본 | 1px 회색(`inactive-bar`) |
 * | 포커스 | 2px 네이비 |
 * | 오류 | 2px danger |
 *
 * 테두리가 굵어져도 글자가 밀리지 않게 안쪽 여백을 1px 씩 줄여 맞춘다.
 * `type="password"` 면 오른쪽에 "보기" 버튼을 두어 글자를 보이거나 숨긴다.
 */
export function TextField({
  label,
  hint,
  error,
  type = 'text',
  className,
  'aria-describedby': describedBy,
  ...rest
}: TextFieldProps) {
  const id = useId()
  const messageId = useId()
  const [revealed, setRevealed] = useState(false)
  const password = type === 'password'
  const invalid = Boolean(error)
  const message = invalid ? error : hint || undefined
  // 부르는 쪽이 준 설명을 덮어쓰지 않고 칸 아래 문구를 덧붙인다
  const describedByIds =
    [describedBy, message !== undefined ? messageId : undefined].filter(Boolean).join(' ') ||
    undefined

  return (
    <div className={clsx('flex flex-col gap-2', className)}>
      <label htmlFor={id} className="text-body-strong font-semibold text-fg">
        {label}
      </label>
      <div className="relative">
        <input
          {...rest}
          id={id}
          type={password && revealed ? 'text' : type}
          aria-invalid={invalid || undefined}
          aria-describedby={describedByIds}
          className={clsx(
            'h-13 w-full rounded-button bg-bg text-section-title text-fg outline-none placeholder:text-fg-muted',
            invalid
              ? ['border-selected border-danger pl-4', password ? 'pr-16' : 'pr-4']
              : [
                  'border-hairline border-inactive-bar pl-4.25 focus:border-selected focus:border-brand focus:pl-4',
                  password ? 'pr-16.25 focus:pr-16' : 'pr-4.25 focus:pr-4',
                ],
          )}
        />
        {password && (
          <span className="absolute top-1 right-1 flex h-11 items-center">
            <button
              type="button"
              aria-label={revealed ? '비밀번호 숨기기' : '비밀번호 보기'}
              onClick={() => setRevealed((value) => !value)}
              className="h-11 min-w-11 cursor-pointer px-3 text-body-strong font-semibold text-fg-sub"
            >
              {revealed ? '숨기기' : '보기'}
            </button>
          </span>
        )}
      </div>
      {message !== undefined && (
        <p
          id={messageId}
          role={invalid ? 'alert' : undefined}
          className={clsx('text-sub', invalid ? 'text-danger' : 'text-fg-sub')}
        >
          {message}
        </p>
      )}
    </div>
  )
}
