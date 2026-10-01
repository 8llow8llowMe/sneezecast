import clsx from 'clsx'

export type ProgressBarProps = {
  value: number
  max: number
  /** 스크린리더가 읽을 이름. 예: "우리 동네 참여 인원" */
  label: string
  /** 스크린리더가 읽을 현재 값. 없으면 "64 / 100" 형식 */
  valueText?: string
  className?: string
}

/**
 * 진행 막대 (Home `자료 부족` 상태의 참여 인원). 높이 8px, 네이비 채움.
 *
 * `자료 부족` 에서 보이는 유일한 수치 요소다. 증상 비율이 아니라 **참여 인원**에만 쓴다.
 * 채움 폭은 0~100% 로 자른다. 공개 기준을 넘긴 값이 와도 막대가 밖으로 나가지 않는다.
 */
export function ProgressBar({ value, max, label, valueText, className }: ProgressBarProps) {
  const ratio = max > 0 ? Math.min(Math.max(value / max, 0), 1) : 0

  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
      aria-valuetext={valueText ?? `${value} / ${max}`}
      className={clsx('h-2 overflow-hidden rounded-progress bg-divider', className)}
    >
      {/* 폭은 값에 따라 바뀌므로 클래스가 아니라 인라인 스타일로 준다 */}
      <div className="h-2 rounded-progress bg-brand" style={{ width: `${ratio * 100}%` }} />
    </div>
  )
}
