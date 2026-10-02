import { cookies } from 'next/headers'

import {
  DATA_SOURCE_COOKIE,
  type DataSource,
  isDataSourceSwitchable,
  resolveDataSource,
} from './data-source'

/**
 * **서버 컴포넌트 전용**이다(`next/headers`). 클라이언트 컴포넌트는 `src/lib/use-data-source.ts` 를 쓴다.
 * 요청 쿠키 `sc_data_source` 로 지금 데이터 출처를 읽는다(규칙은 `data-source.ts`). 전환할 수 없는 사이트(운영 등, `isDataSourceSwitchable`)면 쿠키를 보지 않고 `api` 다.
 *
 * 쿠키를 읽으면 그 라우트가 동적 렌더링이 된다. 그래서 **루트 레이아웃처럼 모든 라우트가 지나는 곳에서는 부르지 않고**,
 * 이미 동적인(`searchParams` 를 await 하는) 페이지에서만 부른다.
 */
export async function readServerDataSource(): Promise<DataSource> {
  if (!isDataSourceSwitchable()) return 'api'
  const store = await cookies()
  return resolveDataSource(store.get(DATA_SOURCE_COOKIE)?.value)
}
