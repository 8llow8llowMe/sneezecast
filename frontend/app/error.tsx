'use client'

import { usePathname } from 'next/navigation'

import { ErrorState } from '@/components/error-state'
import { mainNavKeyFor } from '@/lib/nav'

/**
 * 화면을 그리지 못했을 때 (State-error). 루트 레이아웃 아래에서 난 오류를 받는다(루트 레이아웃 자체의 오류는 받지 않는다).
 *
 * `다시 시도` 는 Next 의 `retry` 다 — 서버에서 화면을 다시 받아 그린다. `reset` 은 다시 받지 않고 그리기만 해서
 * 서버 자료를 받지 못한 오류에는 소용이 없다. 오류 내용(`error.message`)은 화면에 보이지 않는다.
 */
export default function RouteError({
  retry,
}: {
  error: Error & { digest?: string }
  retry: () => void
}) {
  return <ErrorState nav={mainNavKeyFor(usePathname())} onRetry={retry} />
}
