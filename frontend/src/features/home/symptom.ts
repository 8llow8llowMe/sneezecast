import type { GroupTrend, SymptomGroupKey } from './types'

/** 증상군 이름 (보고 화면의 선택지와 같은 묶음) */
export const GROUP_LABEL: Record<SymptomGroupKey, string> = {
  respiratory: '발열·기침·인후통',
  gastrointestinal: '구토·설사',
}

/**
 * 변화 문구. 시안은 같은 "비슷해요" 를 "지난주와 비슷해요" 로도 적어 두 표현이 섞여 있다.
 * 한 화면 안에서 같은 뜻은 같은 말로 쓰려고 짧은 쪽으로 통일한다.
 */
export const TREND_LABEL: Record<GroupTrend, string> = {
  down: '조금 줄었어요',
  flat: '비슷해요',
  slight: '조금 늘었어요',
  high: '많이 늘었어요',
}

/** 늘어난 경우만 상태색을 쓴다. 줄었거나 비슷하면 회색이다 (시안 renderVals 의 r1Color · bars) */
export const TREND_TEXT_CLASS: Record<GroupTrend, string> = {
  down: 'text-fg-sub',
  flat: 'text-fg-sub',
  slight: 'text-status-slight-text',
  high: 'text-status-high-text',
}

export const TREND_LAST_BAR_CLASS: Record<GroupTrend, string> = {
  down: 'bg-muted-bar',
  flat: 'bg-muted-bar',
  slight: 'bg-status-slight',
  high: 'bg-status-high',
}

/** 작은 막대 그래프 최대 높이 (시안 24px) */
export const BAR_MAX_HEIGHT = 24
/** 값이 아주 작아도 막대가 보이게 하는 최소 높이 */
const BAR_MIN_HEIGHT = 4
/**
 * 세로축의 최소 범위 (%). 시안은 1% 를 1px 로 그렸다 (renderVals 의 h1 · h2).
 * 범위를 이보다 좁히지 않아 비율이 낮은 주에 작은 차이가 크게 부풀어 보이지 않게 한다.
 */
const MIN_SCALE_PERCENT = 24

/**
 * 한 주의 증상군들이 함께 쓸 세로축 범위 (%). **증상군마다 따로 맞추지 않는다** — 그러면
 * 낮은 비율의 증상군도 막대가 꽉 차 보여 두 줄을 비교할 수 없다.
 */
export function scaleMax(groups: readonly { series: readonly number[] }[]): number {
  return Math.max(MIN_SCALE_PERCENT, ...groups.flatMap((group) => group.series))
}

/** 주별 비율(%)을 막대 높이(px)로 바꾼다. 범위를 넘는 값은 최대 높이로 자른다 */
export function barHeights(series: readonly number[], max: number): number[] {
  return series.map((value) => {
    const ratio = max > 0 ? Math.min(Math.max(value, 0) / max, 1) : 0
    return Math.max(BAR_MIN_HEIGHT, Math.round(ratio * BAR_MAX_HEIGHT))
  })
}
