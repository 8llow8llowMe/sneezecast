import { type ReactNode, Suspense } from 'react'

import { MeTrailProvider } from '@/features/me/me-trail'
import { MeRequiredStepsGate } from '@/features/me/member-gate'

/**
 * 내 정보(`/me`)와 계정 화면(`/me/devices` · `/me/password` · `/me/region` · `/me/nickname`)을 묶는다. 계정 화면의 "뒤로" 는 내 정보에서 왔으면 기록을 되돌리고,
 * 주소로 바로 들어왔으면 내 정보로 기록을 바꿔 간다(판단은 루트의 앱 안 이동 기록, `MeTrailProvider` → `useNavTrail`).
 * 계정 화면이 일을 마치고 내 정보에 남기는 알림도 이 레이아웃이 든다.
 *
 * 다시 들어온 회원에게 약관 재동의 · 동네 다시 고르기 조건이 있으면 다섯 화면 모두 그 화면으로 먼저 보낸다(`MeRequiredStepsGate`).
 * 가드는 주소 쿼리(`useSearchParams`)를 읽어 빌드의 정적 생성에서 Suspense 경계가 있어야 한다 — 아무 것도 그리지 않으므로 대체 그림도 없다.
 */
export default function MeLayout({ children }: { children: ReactNode }) {
  return (
    <MeTrailProvider>
      <Suspense fallback={null}>
        <MeRequiredStepsGate />
      </Suspense>
      {children}
    </MeTrailProvider>
  )
}
