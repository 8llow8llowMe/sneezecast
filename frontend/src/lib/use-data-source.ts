'use client'

import { useSyncExternalStore } from 'react'

import {
  type DataSource,
  readBrowserDataSource,
  resolveDataSource,
  subscribeDataSource,
} from './data-source'

/** 서버는 루트 레이아웃에서 쿠키를 읽지 않으므로(모든 라우트가 동적이 된다) 기본값으로 그린다 */
const serverSnapshot = (): DataSource => resolveDataSource(undefined)

/**
 * 클라이언트 컴포넌트에서 지금 데이터 출처. 토글로 바꾸면(`writeBrowserDataSource`) 바로 따라간다.
 *
 * 서버 그림과 하이드레이션 첫 그림은 기본값이고, 그 뒤 다시 그릴 때부터 쿠키 값이다(SSR 불일치 방지).
 * 그래서 이 값으로 요청할 때는 출처를 effect 의 의존값에 넣는다 — 하이드레이션 뒤 쿠키 값으로 다시 돈다.
 */
export function useDataSource(): DataSource {
  return useSyncExternalStore(subscribeDataSource, readBrowserDataSource, serverSnapshot)
}
