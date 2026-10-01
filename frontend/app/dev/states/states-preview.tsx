'use client'

import { ErrorState } from '@/components/error-state'
import { LoadingState } from '@/components/loading-state'
import { OfflineNotice } from '@/components/offline-notice'
import type { MainNavKey } from '@/lib/nav'
import { useOnline } from '@/lib/use-online'

export type PreviewState = 'loading' | 'error' | 'offline'

/**
 * 공통 상태 미리보기. `offline` 은 홈 알림 줄 자리(위 12 · 좌우 20)에 띠만 그린다 —
 * `force` 면 연결과 무관하게 보이고, 아니면 브라우저 연결 상태(`useOnline`)를 따른다(개발자 도구의 오프라인 전환으로 확인).
 */
export function StatesPreview({
  state,
  nav,
  force,
}: {
  state: PreviewState
  nav: MainNavKey | null
  force: boolean
}) {
  const online = useOnline()
  if (state === 'loading') return <LoadingState nav={nav ?? 'home'} />
  if (state === 'error') return <ErrorState nav={nav} onRetry={() => window.location.reload()} />
  return (
    <main className="min-h-dvh bg-bg">
      <p className="px-page-mobile pt-2 text-sub text-fg-sub">11월 3주 · 오늘 09:00 갱신</p>
      <OfflineNotice
        offline={force || !online}
        receivedAt="2026-11-19T00:00:00Z"
        className="mx-5 mt-3"
      />
    </main>
  )
}
