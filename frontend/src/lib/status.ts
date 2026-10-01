/**
 * 동네 상태 단계. 라벨과 색 클래스를 여기 한 곳에 둔다 — 화면마다 따로 적지 않는다.
 *
 * 정본은 docs/design/tokens.json 의 `status` 다. 라벨이 어긋나면 status.test.ts 가 실패한다.
 * 기준값(공개 기준 100명, 3%p · 8%p)은 백엔드가 판정해 `status` 로 내려 준다. 프론트는 다시 계산하지 않는다.
 */

export const REGION_STATUSES = ['normal', 'slight', 'high', 'insufficient'] as const

export type RegionStatus = (typeof REGION_STATUSES)[number]

/** 수치·상태색을 보여도 되는 단계 */
export type MeasuredStatus = Exclude<RegionStatus, 'insufficient'>

export const STATUS_LABEL: Record<RegionStatus, string> = {
  normal: '평소 수준',
  slight: '조금 늘었어요',
  high: '많이 늘었어요',
  insufficient: '자료 부족',
}

/**
 * 상태 글자색. `자료 부족` 은 상태색이 아니라 보조 회색을 쓴다 — 색으로 위험을 암시하지 않는다.
 *
 * Tailwind 는 소스에서 **완성된 클래스 문자열**만 찾으므로 조합해서 만들지 않고 그대로 적는다.
 */
export const STATUS_TEXT_CLASS: Record<RegionStatus, string> = {
  normal: 'text-status-normal-text',
  slight: 'text-status-slight-text',
  high: 'text-status-high-text',
  insufficient: 'text-status-insufficient-text',
}

/** 수치·상태색을 보여도 되는가. `자료 부족` 이면 증상 비율·상태색을 숨긴다 (루트 CLAUDE.md) */
export function isMeasured(status: RegionStatus): status is MeasuredStatus {
  return status !== 'insufficient'
}
