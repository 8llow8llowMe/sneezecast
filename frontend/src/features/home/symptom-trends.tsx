import clsx from 'clsx'

import { ListRow } from '@/components/list-row'
import { Section } from '@/components/section'

import {
  barHeights,
  GROUP_LABEL,
  scaleMax,
  TREND_LABEL,
  TREND_LAST_BAR_CLASS,
  TREND_TEXT_CLASS,
} from './symptom'
import type { HomeWeekly, SymptomGroup } from './types'

/**
 * 증상별 변화 섹션. 증상군마다 변화 문구와 최근 주별 작은 막대 그래프를 보인다.
 * 자료 부족이면 막대 대신 "보고가 충분히 모이면 보여드려요" 안내만 둔다.
 */
export function SymptomTrends({ week }: { week: HomeWeekly }) {
  return (
    <Section title="증상별 변화" layout="panel">
      {week.status === 'insufficient' ? (
        <p className="mt-2 rounded-button border border-dashed border-inactive-bar p-4 text-body-strong leading-normal text-fg-sub">
          보고가 충분히 모이면 증상별 변화를 보여드려요. 표본이 적을 때는 수치를 표시하지 않아요.
        </p>
      ) : (
        week.groups.map((group) => (
          <ListRow
            key={group.key}
            title={GROUP_LABEL[group.key]}
            description={
              <span className={TREND_TEXT_CLASS[group.trend]}>{TREND_LABEL[group.trend]}</span>
            }
            trailing={<TrendBars group={group} max={scaleMax(week.groups)} />}
            divider
          />
        ))
      )}
    </Section>
  )
}

/**
 * 작은 막대 그래프. 마지막 막대가 이번 주이고, 늘어난 경우만 상태색으로 칠한다.
 * 막대만으로는 값을 알 수 없으므로 보조기술에는 주별 비율을 글로 읽어 준다.
 */
export function TrendBars({ group, max }: { group: SymptomGroup; max: number }) {
  const heights = barHeights(group.series, max)
  const last = heights.length - 1

  return (
    <span
      role="img"
      aria-label={`최근 ${group.series.length}주 증상 보고 비율 ${group.series.join('%, ')}%`}
      className="flex h-6 shrink-0 items-end gap-0.75"
    >
      {heights.map((height, index) => (
        <span
          // 주 순서가 바뀌지 않는 고정 길이 목록이라 위치를 키로 쓴다
          key={index}
          className={clsx(
            'block w-1.5 rounded-bar',
            index === last ? TREND_LAST_BAR_CLASS[group.trend] : 'bg-inactive-bar',
          )}
          style={{ height }}
        />
      ))}
    </span>
  )
}
