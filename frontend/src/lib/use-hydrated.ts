'use client'

import { useSyncExternalStore } from 'react'

const noopSubscribe = () => () => {}

/**
 * 하이드레이션을 마쳤는지. 서버 그림과 하이드레이션 첫 그림은 false, 그 뒤 다시 그릴 때부터 true 다.
 * 앱 안 이동으로 처음 그리는 화면(하이드레이션이 아님)은 처음부터 true 다.
 *
 * 서버 스냅숏으로 그리는 상태(목 회원 상태 `useMockAuth` 는 서버에서 늘 `guest`)로 이동을 정하면 안 될 때 쓴다.
 * 하이드레이션 첫 그림의 `guest` 는 실제 상태가 아니다 — 그 값으로 로그인 화면에 보내면 회원도 튕긴다.
 * true 가 된 그림에서는 같은 그림 안의 다른 `useSyncExternalStore` 도 브라우저 쪽 값을 읽는다.
 */
export function useHydrated(): boolean {
  return useSyncExternalStore(
    noopSubscribe,
    () => true,
    () => false,
  )
}
