import type { Metadata } from 'next'
import { notFound } from 'next/navigation'

import { MAIN_NAV, type MainNavKey } from '@/lib/nav'

import { type PreviewState, StatesPreview } from './states-preview'

export const metadata: Metadata = {
  title: '공통 상태',
  robots: { index: false, follow: false },
}

const STATES: readonly PreviewState[] = ['loading', 'error', 'offline']

/**
 * 공통 상태 미리보기 (State-loading · State-error · State-offline). 시안과 나란히 놓고 비교하는 용도다.
 *
 * `?show=loading|error|offline`(기본 loading) · `?nav=home|map|me|none`(기본 home, none 은 오류 화면만 — 메뉴 없이) · `?force=1`(오프라인 띠를 늘 보임).
 * `nav=home|map|me` 는 각 경계(`app/(home)` · `app/map` · `app/me` 의 `loading.tsx`)와 같은 그림이다.
 * 실제 라우트 오류 화면(`app/error.tsx`)은 `/dev/states/throw` 에서 본다. **개발 서버에서만 열린다.**
 */
export default async function StatesPreviewPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  if (process.env.NODE_ENV === 'production') notFound()

  const { show, nav, force } = await searchParams
  const state = STATES.find((value) => value === show) ?? 'loading'
  const navKey: MainNavKey | null =
    nav === 'none' ? null : (MAIN_NAV.find((item) => item.key === nav)?.key ?? 'home')
  return <StatesPreview state={state} nav={navKey} force={force === '1'} />
}
