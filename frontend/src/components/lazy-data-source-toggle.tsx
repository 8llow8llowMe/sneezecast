'use client'

import { lazyComponent, useLazyComponent } from '@/lib/lazy-component'

const dataSourceToggle = lazyComponent(() =>
  import('./data-source-toggle').then((mod) => mod.DataSourceToggle),
)

/** 개발용 토글이라 받지 못하면 그리지 않고 따로 알리지 않는다 */
function ignore() {}

/**
 * 데이터 출처 토글(`data-source-toggle.tsx`)을 따로 받는 자리(#184). 루트 레이아웃은 전환할 수 있는 사이트(dev 웹 · 로컬)에서만 이것을 그린다.
 *
 * - 레이아웃이 토글을 정적으로 부르면 그리지 않는 운영 빌드에서도 토글 코드가 레이아웃 청크(모든 화면이 받음)에 들어간다.
 * - 서버 컴포넌트(레이아웃)에서 `next/dynamic` 을 불러서는 청크가 나뉘지 않고, 여기서 `next/dynamic` 을 쓰면 그 실행 코드(약 4.6 KB)가
 *   레이아웃 청크에 더해져 토글(약 1 KB)보다 크다(빌드 산출물로 확인). 그래서 홈 시트와 같은 `lazyComponent` 로 받는다.
 * - 서버 그림 · 하이드레이션 첫 그림에는 없다. 토글은 원래 하이드레이션 뒤에만 그려(서버가 쿠키의 출처를 모른다) 화면은 같다.
 */
export function LazyDataSourceToggle() {
  const Toggle = useLazyComponent(dataSourceToggle, true, ignore)
  return Toggle ? <Toggle /> : null
}
