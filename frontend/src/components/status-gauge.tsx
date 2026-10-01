import clsx from 'clsx'

import { isMeasured, type MeasuredStatus, type RegionStatus } from '@/lib/status'

/**
 * 3구간 반원 게이지 (Home 상태 카드 오른쪽). 시안 viewBox 120×70 을 104×61 로 그린다.
 *
 * - 구간은 왼쪽부터 평소 수준(파랑) · 조금 늘었어요(초록) · 많이 늘었어요(주황)
 * - 현재 단계 구간만 진하게, 나머지는 `opacity.gaugeInactive`(0.28)
 * - `자료 부족` 이면 세 구간 모두 회색이고 점이 없다 — 색으로 위험을 암시하지 않는다
 *
 * 장식이다(`aria-hidden`). 상태는 옆의 StatusWord 글자가 전한다.
 */

const SEGMENTS: { status: MeasuredStatus; d: string; stroke: string }[] = [
  { status: 'normal', d: 'M12 62A48 48 0 0 1 36 20.43', stroke: 'stroke-status-normal' },
  { status: 'slight', d: 'M37.5 19.6A48 48 0 0 1 82.5 19.6', stroke: 'stroke-status-slight' },
  { status: 'high', d: 'M84 20.43A48 48 0 0 1 108 62', stroke: 'stroke-status-high' },
]

/** 단계별 점 위치 (시안 renderVals 의 dot) */
const DOT: Record<MeasuredStatus, { cx: number; cy: number; stroke: string }> = {
  normal: { cx: 18.4, cy: 38, stroke: 'stroke-status-normal-text' },
  slight: { cx: 60, cy: 14, stroke: 'stroke-status-slight-text' },
  high: { cx: 101.6, cy: 38, stroke: 'stroke-status-high-text' },
}

export function StatusGauge({ status, className }: { status: RegionStatus; className?: string }) {
  const measured = isMeasured(status)

  return (
    <svg
      width="104"
      height="61"
      viewBox="0 0 120 70"
      fill="none"
      aria-hidden="true"
      className={clsx('shrink-0', className)}
      data-status={status}
    >
      {SEGMENTS.map((segment) => (
        <path
          key={segment.status}
          d={segment.d}
          strokeWidth={10}
          className={clsx(
            measured ? segment.stroke : 'stroke-inactive-bar',
            measured && segment.status !== status && 'stroke-opacity-inactive',
          )}
        />
      ))}
      {measured && (
        <circle
          cx={DOT[status].cx}
          cy={DOT[status].cy}
          r={8}
          strokeWidth={3}
          className={clsx('fill-bg', DOT[status].stroke)}
        />
      )}
    </svg>
  )
}
