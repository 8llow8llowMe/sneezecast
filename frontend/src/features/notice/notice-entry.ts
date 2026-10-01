/**
 * 앱 안에서 동네 안내로 들어온 표시. 안내 화면의 "뒤로" 가 앱 안에서 왔으면 기록을 되돌리고(`router.back`),
 * 주소로 바로 들어왔으면 홈으로 기록을 바꿔 가게 하려고 쓴다(docs/conventions.md "단계 화면의 뒤로").
 *
 * 안내로 들어가는 링크(`NoticeLink`)가 누를 때 남기고, **안내 화면이 마운트될 때 읽어 자기 상태로 옮긴 뒤 비운다(소비)**.
 * 남겨 두면 그 뒤 휴대폰 뒤로 · 앞으로로 같은 안내에 다시 들어왔을 때(예: 주소로 연 안내 → 홈 → 링크로 같은 안내 → 휴대폰 뒤로 두 번)
 * 앞에 앱 기록이 없는데도 `router.back()` 해 앱 밖으로 나간다.
 *
 * 루트 레이아웃에 이동 기록 Provider 를 두면 모든 화면에 걸려 이번에는 두지 않고 **모듈 메모리**에 둔다.
 * 새로고침 · 새 탭이면 비어 홈으로 replace 한다. 안내 경로가 같을 때만 따른다.
 */
let entryPath: string | null = null

/** 앱 안 링크로 이 경로(쿼리 없이)의 안내에 들어간다 */
export function markNoticeEntry(pathname: string): void {
  entryPath = pathname
}

/** 지금 안내(경로)에 앱 안 링크로 들어왔는지. 비우지 않는다 — 화면은 읽은 뒤 `clearNoticeEntry` 로 비운다 */
export function enteredNoticeInApp(pathname: string): boolean {
  return entryPath === pathname
}

/** 표시를 비운다. 안내 화면이 마운트된 뒤(effect) 부른다 */
export function clearNoticeEntry(): void {
  entryPath = null
}
