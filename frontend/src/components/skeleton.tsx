import clsx from 'clsx'

/**
 * 불러오는 중 자리 표시 막대 (State-loading). 회색(`bg-skeleton`) 장식이라 보조기술에는 숨긴다 —
 * "불러오는 중" 은 감싸는 영역(`role="status"`)이 알린다.
 *
 * - bar: 글자 · 목록 자리 (모서리 6)
 * - button: 버튼 자리 (모서리 12)
 *
 * 크기(폭 · 높이)와 바깥 여백은 `className` 으로 준다. 시안의 막대마다 크기가 달라서다.
 */
export function Skeleton({
  shape = 'bar',
  className,
}: {
  shape?: 'bar' | 'button'
  className?: string
}) {
  return (
    <div
      aria-hidden="true"
      className={clsx(
        'shrink-0 bg-skeleton',
        shape === 'bar' ? 'rounded-skeleton' : 'rounded-button',
        className,
      )}
    />
  )
}
