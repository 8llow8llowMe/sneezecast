/**
 * 첫 진입 화면 사이를 앱 안에서 오간 경로 기록. 브라우저 기록 중 이 흐름 안의 부분을 따라간다.
 *
 * 머리줄 "뒤로" 가 앞 단계에서 앱 안 이동으로 왔으면 기록을 되돌리고(`router.back`), 주소로 바로 들어와
 * 앞 단계 기록이 없으면 기록을 바꿔(`router.replace`) 가게 하려고 쓴다. 기록을 쌓기만 하면 뒤로 가기를
 * 누를 때마다 앞 단계가 다시 나온다.
 *
 * 주소가 바뀔 때마다 부른다. 쿼리는 보지 않는다.
 * - 같은 주소: 그대로
 * - 기록을 바꾼 이동(`replaced`): 맨 끝을 바꾼다
 * - 바로 앞 주소로 돌아옴: 뒤로 간 것으로 보고 맨 끝을 뺀다
 * - 그 밖: 새로 쌓는다
 */
export function nextTrail(trail: readonly string[], pathname: string, replaced: boolean): string[] {
  if (trail.at(-1) === pathname) return [...trail]
  if (replaced) return [...trail.slice(0, -1), pathname]
  if (trail.at(-2) === pathname) return trail.slice(0, -1)
  return [...trail, pathname]
}

/** 지금 화면 바로 앞이 앞 단계 후보 중 하나면 기록을 되돌려 갈 수 있다 */
export function canGoBackTo(trail: readonly string[], previousPaths: readonly string[]): boolean {
  const before = trail.at(-2)
  return before !== undefined && trail.length >= 2 && previousPaths.includes(before)
}
