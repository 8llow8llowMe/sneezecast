/**
 * 내 정보에서 홈으로 기록을 바꿔 가는 동작이 홈에 남기는 알림 한 번 (건강정보 동의 철회 → 로그아웃).
 *
 * 홈은 내 정보 레이아웃(`MeTrailProvider`) 밖이라 그 메모리를 쓸 수 없고, 주소 쿼리는 새로고침 · 공유로 알림이 되살아난다.
 * 그래서 모듈 메모리에 한 번 쓰고 홈이 마운트 때 꺼내 비운다. 새로고침하면 사라진다 — 알림은 그래도 된다.
 */
export const CONSENT_WITHDRAWN_NOTICE = '건강정보 동의를 철회하고 로그아웃했어요'

let pendingNotice: string | null = null

/** 홈이 띄울 알림을 남긴다 */
export function leaveHomeNotice(message: string): void {
  pendingNotice = message
}

/** 남긴 알림을 꺼내고 비운다. 없으면 null 이다 */
export function takeHomeNotice(): string | null {
  const taken = pendingNotice
  pendingNotice = null
  return taken
}
