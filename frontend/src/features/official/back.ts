/**
 * 공식 정보의 "뒤로" 를 정하는 판단. 앱 안에서 다른 화면(홈 등)을 거쳐 왔으면 기록을 되돌리고(`router.back`),
 * 주소로 바로 들어왔으면 홈으로 기록을 바꿔(`router.replace`) 간다 — docs/conventions.md "단계 화면의 뒤로" 와 같은 결과다.
 *
 * 첫 진입 · 내 정보처럼 레이아웃 Provider 로 이동 기록을 셀 수 없다(홈과 묶는 레이아웃이 없다 — 루트 레이아웃에 두면
 * 모든 화면에 걸린다). 대신 **이 문서를 처음 연 주소**(Navigation Timing)를 본다. 앱 안 이동은 문서를 다시 열지 않으므로
 * 처음 연 주소가 이 화면이 아니면 앞에 앱 안 기록이 있다. 바깥 사이트에서 링크로 바로 들어왔으면 처음 연 주소가 이 화면이라
 * 뒤로가 사이트 밖으로 나가지 않는다.
 *
 * 한계: 아래처럼 처음 연 주소가 이 화면이 되면 앞에 홈 기록이 있어도 홈으로 replace 한다(기록에 홈이 두 번 남음).
 * 내 정보 레이아웃이 다시 마운트될 때와 같은 성질이라 그대로 둔다.
 * - 이 화면을 바로 열고 → 홈 → 다시 이 화면으로 왔을 때
 * - 앱 안에서 온 뒤 이 화면을 새로고침했을 때
 * - 다른 곳에 나갔다가 브라우저 뒤로로 돌아올 때 bfcache 없이 문서를 다시 열었을 때
 *
 * 주의: 다른 문서에서 이 화면으로 `router.replace` 하는 경로(예: 가드의 돌아갈 곳 `NEXT_PATHS` 에 `/official` 을 더함)를 만들면
 * 처음 연 주소는 다른 화면인데 앞 기록은 사이트 밖일 수 있다 — 그때 `router.back()` 이 사이트 밖으로 나간다.
 * 그런 경로를 만들면 이 판단을 함께 바꾼다.
 */

/** 이 문서를 처음 연 주소의 경로. 브라우저가 알려 주지 않으면(서버 · 미지원) null 이다 */
export function documentEntryPath(): string | null {
  if (typeof performance === 'undefined' || typeof performance.getEntriesByType !== 'function') {
    return null
  }
  const [entry] = performance.getEntriesByType('navigation')
  if (!entry) return null
  try {
    return new URL(entry.name).pathname
  } catch {
    return null
  }
}

/** 처음 연 주소가 지금 화면이 아니면 앱 안에서 거쳐 온 기록이 있다. 모르면(null) 바로 들어온 것으로 본다 */
export function cameInApp(entryPath: string | null, pathname: string): boolean {
  return entryPath !== null && entryPath !== pathname
}
