'use client'

import { useSyncExternalStore } from 'react'

/** 브라우저의 연결 상태가 바뀔 때 부른다 (`useSyncExternalStore` 의 subscribe). 돌려준 함수로 구독을 끊는다 */
export function subscribeOnline(listener: () => void): () => void {
  window.addEventListener('online', listener)
  window.addEventListener('offline', listener)
  return () => {
    window.removeEventListener('online', listener)
    window.removeEventListener('offline', listener)
  }
}

/** 지금 브라우저가 연결돼 있다고 보는지 (`navigator.onLine`) */
export function getOnlineSnapshot(): boolean {
  return navigator.onLine
}

// 서버는 브라우저의 연결 상태를 모른다. 서버와 하이드레이션 첫 그림은 온라인으로 그린다
const serverSnapshot = (): boolean => true

/**
 * 브라우저가 연결돼 있는지 (State-offline). online · offline 이벤트로 바로 따라간다.
 *
 * **하이드레이션 전에는 온라인으로 본다.** 서버 그림과 하이드레이션 첫 그림을 맞춰 SSR 불일치를 막고, 오프라인이면 그다음 그림에서
 * 오프라인 안내를 띄운다. `navigator.onLine` 이 true 여도 실제 서버에 닿는다는 보장은 없다(사내망 · 캡티브 포털) —
 * 요청 실패는 API 계층이 따로 알린다. 이 값은 "확실히 끊겼다" 를 알리는 데만 쓴다.
 */
export function useOnline(): boolean {
  return useSyncExternalStore(subscribeOnline, getOnlineSnapshot, serverSnapshot)
}
