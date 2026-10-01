import type { ReactNode } from 'react'

import { OnboardingProvider } from '@/features/onboarding/onboarding-context'

/**
 * 첫 진입(/start · /login* · /signup/* · /password/reset* · /setup/* · /browse/region)이 고른 동네 · 성인 확인 · 이동 기록을 함께 쓰게 감싼다.
 * 괄호 폴더(route group)라 주소에는 나타나지 않는다. 새로고침하면 값이 사라진다.
 */
export default function OnboardingGroupLayout({ children }: { children: ReactNode }) {
  return <OnboardingProvider>{children}</OnboardingProvider>
}
