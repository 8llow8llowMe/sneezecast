/** 사람 수 · 건수. 천 단위 쉼표를 넣는다 (예: 1,280) */
export function formatCount(value: number): string {
  return value.toLocaleString('ko-KR')
}
