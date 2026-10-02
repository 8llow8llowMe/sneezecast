'use client'

import { useRouter } from 'next/navigation'

import clsx from 'clsx'

import { type DataSource, writeBrowserDataSource } from '@/lib/data-source'
import { useDataSource } from '@/lib/use-data-source'
import { useHydrated } from '@/lib/use-hydrated'

const LABEL: Record<DataSource, string> = { api: '실데이터', mock: '목데이터' }

/**
 * 데이터 출처 토글 (개발용, #135). 실데이터(api) · 목데이터(mock) 중 지금 출처를 보이고, 누르면 바꾼다.
 * 규칙은 `src/lib/data-source.ts` 다. 루트 레이아웃이 **출처를 바꿀 수 있는 사이트(dev 웹 · 로컬, `isDataSourceSwitchable`)에서만** 그린다.
 *
 * - 고른 값은 쿠키에 쓰고 `router.refresh()` 로 서버 컴포넌트를 다시 그려 바로 반영한다(클라이언트 화면은 `useDataSource` 로 따라간다).
 * - 서버는 루트 레이아웃에서 쿠키를 읽지 않아 지금 출처를 모른다 — 하이드레이션을 마친 뒤에만 그려 첫 그림이 어긋나지 않게 한다.
 * - 화면 오른쪽 아래에 고정하되 탭바 · 화면 아래 버튼 묶음보다 위에 띄운다(`bottom-dev-toggle`).
 * - z-index 를 주지 않는다. z-index 가 있는 시트 · 버튼 묶음 · 가림막(z-10 · z-20)은 DOM 순서와 무관하게 토글 위에 온다.
 *   레이아웃은 토글을 body 마지막에 둔다 — 맨 앞이면 Tab 첫 포커스가 토글이 된다.
 */
export function DataSourceToggle() {
  const router = useRouter()
  const hydrated = useHydrated()
  const source = useDataSource()
  if (!hydrated) return null

  return (
    <button
      type="button"
      role="switch"
      aria-checked={source === 'api'}
      aria-label="실데이터 사용 (개발용)"
      title="개발용: 데이터 출처 바꾸기"
      onClick={() => {
        writeBrowserDataSource(source === 'api' ? 'mock' : 'api')
        router.refresh()
      }}
      className="fixed right-page-mobile bottom-dev-toggle flex min-h-touch cursor-pointer items-center tablet:right-page-tablet tablet:bottom-24 desktop:right-page-desktop desktop:bottom-6"
    >
      <span className="flex rounded-chip border-hairline border-divider bg-bg p-0.5 text-caption">
        {(['api', 'mock'] as const).map((option) => (
          <span
            key={option}
            className={clsx(
              'rounded-chip px-2 py-1',
              option === source ? 'bg-fg font-bold text-bg' : 'text-fg-muted',
            )}
          >
            {LABEL[option]}
          </span>
        ))}
      </span>
    </button>
  )
}
