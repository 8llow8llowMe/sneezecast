'use client'

import { useSyncExternalStore } from 'react'

import {
  getSessionSnapshot,
  IDLE_SESSION,
  type SessionSnapshot,
  subscribeSession,
} from './session-store'

/** 서버는 세션을 모른다. 서버 그림과 하이드레이션 첫 그림은 늘 `idle` 이다(같은 객체 — 하이드레이션이 어긋나지 않게) */
const serverSnapshot = (): SessionSnapshot => IDLE_SESSION

/** 클라이언트 컴포넌트에서 지금 세션(실데이터 모드). 토큰은 없고 회원 요약만 있다 */
export function useSession(): SessionSnapshot {
  return useSyncExternalStore(subscribeSession, getSessionSnapshot, serverSnapshot)
}
