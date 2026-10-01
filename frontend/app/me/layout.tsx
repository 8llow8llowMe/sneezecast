import type { ReactNode } from 'react'

import { MeTrailProvider } from '@/features/me/me-trail'

/**
 * 내 정보(`/me`)와 계정 화면(`/me/devices` · `/me/password`)을 묶는다. 화면 사이 앱 안 이동 기록을 들고 있어
 * 계정 화면의 "뒤로" 가 내 정보에서 왔으면 기록을 되돌리고, 주소로 바로 들어왔으면 내 정보로 기록을 바꿔 간다.
 */
export default function MeLayout({ children }: { children: ReactNode }) {
  return <MeTrailProvider>{children}</MeTrailProvider>
}
