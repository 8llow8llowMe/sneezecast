import type { ComponentPropsWithoutRef } from 'react'

import clsx from 'clsx'

/*
  카카오 로그인 버튼 색. 서비스 토큰이 아니라 카카오 로그인 디자인 가이드가 정한 값이라 토큰으로 올리지 않고
  이 파일에만 둔다 (docs/design/auth/README.md "새 색은 카카오 버튼 하나뿐"). 바탕 · 심볼 · 글자 색을 바꾸지 않는다.
*/
/* eslint-disable no-restricted-syntax -- 카카오 브랜드 색. 토큰 밖 값이지만 카카오 가이드라 예외 */
const KAKAO_CONTAINER = '#FEE500'
const KAKAO_SYMBOL = '#000000'
/* eslint-enable no-restricted-syntax */
const KAKAO_LABEL = 'rgba(0, 0, 0, 0.85)'

export type KakaoButtonProps = Omit<ComponentPropsWithoutRef<'button'>, 'style'>

/**
 * 카카오로 계속하기 (Login · Login-sheet). 높이 56 · 모서리 12 · 글자 17 — 크기는 `Button` 큰 크기와 같다.
 * 폭은 부모를 꽉 채운다.
 */
export function KakaoButton({ type = 'button', className, children, ...rest }: KakaoButtonProps) {
  return (
    <button
      type={type}
      style={{ backgroundColor: KAKAO_CONTAINER, color: KAKAO_LABEL }}
      className={clsx(
        'flex h-button w-full cursor-pointer items-center justify-center gap-2 rounded-button px-5 text-section-title font-semibold',
        'disabled:cursor-not-allowed disabled:opacity-disabled',
        className,
      )}
      {...rest}
    >
      <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path
          d="M12 3C6.48 3 2 6.58 2 11c0 2.83 1.84 5.31 4.6 6.72l-1.17 4.3c-.1.37.32.67.64.45l5.05-3.35c.29.02.58.03.88.03 5.52 0 10-3.58 10-8s-4.48-8-10-8z"
          fill={KAKAO_SYMBOL}
        />
      </svg>
      <span>{children}</span>
    </button>
  )
}
