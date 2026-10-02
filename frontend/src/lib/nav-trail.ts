/**
 * 앱 안에서 오간 경로 기록. 브라우저 기록 중 이 문서(탭)가 앱 안 이동으로 쌓은 부분을 따라간다
 * (docs/conventions.md "화면의 뒤로"). 루트 레이아웃의 `NavTrailProvider`(`use-nav-trail.tsx`)가 주소가 바뀔 때마다 부른다.
 *
 * 머리줄 "뒤로" 가 앱 안 이동으로 왔으면 기록을 되돌리고(`router.back`), 주소로 바로 들어와 앞 기록이 없으면
 * 기록을 바꿔(`router.replace`) 가게 하려고 쓴다. 기록을 쌓기만 하면 뒤로 가기를 누를 때마다 앞 화면이 다시 나온다.
 *
 * 경로만 본다. 쿼리는 보지 않는다(시트 · 대화상자의 `?…` 는 같은 경로라 기록에 들지 않는다).
 * - 같은 주소: 그대로
 * - 기록을 바꾼 이동(`replaced`): 맨 끝을 바꾼다
 * - 바로 앞 주소로 돌아옴: 뒤로 간 것으로 보고 맨 끝을 뺀다
 * - 그 밖: 새로 쌓는다
 *
 * 한계: 브라우저 뒤로 · 앞으로와 링크 이동을 구분하지 않는다. 바로 앞앞 주소로 링크를 따라가도 뒤로 간 것으로 보고,
 * 앞으로 가기는 새로 쌓은 것으로 본다. 이 기록을 거치지 않은 `router.replace` 는 쌓은 것으로 보아 기록이 실제보다 길어지므로
 * 앱 안 replace 는 모두 `useNavTrail().replace` 로 한다(ESLint 가 막는다).
 */
export function nextTrail(trail: readonly string[], pathname: string, replaced: boolean): string[] {
  if (trail.at(-1) === pathname) return [...trail]
  if (replaced) return [...trail.slice(0, -1), pathname]
  if (trail.at(-2) === pathname) return trail.slice(0, -1)
  return [...trail, pathname]
}

/**
 * 되돌려 갈 바로 앞 화면의 조건. 후보 경로 목록이거나, 후보가 정해지지 않은 화면이면 바로 앞 경로를 받아 판단하는 함수다
 * (예: 로그인 방법 고르기 — 앞 화면은 아무 화면이나 되지만 그만둔 가입 · 재설정 단계로는 되돌리지 않는다)
 */
export type PreviousPaths = readonly string[] | ((previous: string) => boolean)

/**
 * 지금 화면 바로 앞에 앱 안 기록이 있어 기록을 되돌려 갈 수 있는지.
 * `previousPaths` 를 주면 바로 앞이 그 후보 중 하나일 때만(함수면 그 함수가 참일 때만), 생략하면 바로 앞이 앱 안 화면이기만 하면 참이다.
 */
export function canGoBackTo(trail: readonly string[], previousPaths?: PreviousPaths): boolean {
  const before = trail.at(-2)
  if (before === undefined || trail.length < 2) return false
  if (previousPaths === undefined) return true
  return typeof previousPaths === 'function'
    ? previousPaths(before)
    : previousPaths.includes(before)
}

/**
 * 기록을 바꾸는 이동(`useNavTrail().replace`)을 걸어 둔 것. `from` 은 걸 때 서 있던 경로, `to` 는 바꿔 갈 경로다(쿼리 없이).
 * 같은 경로로의 replace(쿼리만 바뀜)는 경로 기록이 바뀌지 않아 걸지 않는다.
 */
export type PendingReplace = { readonly from: string; readonly to: string }

/**
 * 주소가 `pathname` 으로 바뀌었을 때, 걸어 둔 replace 가 이 이동인지 정한다.
 *
 * 불리언 하나로 "다음 이동은 replace" 라고 두면 틀린 이동에 쓰인다. 회원 가드처럼 **앱 안 이동으로 도착한 커밋의 자식 effect** 에서
 * replace 하면, 자식 effect 가 기록을 고치는 Provider effect 보다 먼저 돌아 표시가 "도착" 이동에 소비된다 — 그러면 도착한 화면은
 * 맨 끝을 바꾸고 replace 로 간 화면은 쌓아, 기록이 브라우저 기록보다 한 칸 길어진다(유령 항목). 그 뒤 휴대폰 뒤로로 바로 연 첫 화면에
 * 돌아와도 앞 기록이 있다고 보아 `router.back()` 이 사이트 밖으로 나간다. 그래서 바꿔 갈 경로를 들고 있다가 그 경로에 닿았을 때만 쓴다.
 * - 바꿔 갈 경로에 닿음: replace 다. 다 썼으므로 비운다
 * - 건 화면에 닿음(그 화면에 도착한 커밋에서 걸었다): 아직 기다린다
 * - 다른 곳에 닿음(Next 가 이동을 버렸거나 다른 이동이 앞섰다): 버린다
 */
export function settleReplace(
  pending: PendingReplace | null,
  pathname: string,
): { replaced: boolean; pending: PendingReplace | null } {
  if (pending === null) return { replaced: false, pending: null }
  if (pending.to === pathname) return { replaced: true, pending: null }
  if (pending.from === pathname) return { replaced: false, pending }
  return { replaced: false, pending: null }
}
