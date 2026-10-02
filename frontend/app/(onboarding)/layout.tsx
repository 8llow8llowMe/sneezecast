import type { ReactNode } from 'react'

import { OnboardingProvider } from '@/features/onboarding/onboarding-context'

/**
 * 첫 진입(/start · /login* · /signup/* · /password/reset* · /setup/* · /browse/region)과 약관 재동의(/terms/*)가
 * 고른 동네 · 성인 확인 · 가입 초안을 함께 쓰게 감싼다("뒤로" 판단은 루트의 앱 안 이동 기록, `useNavTrail`).
 * 괄호 폴더(route group)라 주소에는 나타나지 않는다. 새로고침하면 값이 사라진다.
 */
export default function OnboardingGroupLayout({ children }: { children: ReactNode }) {
  return <OnboardingProvider>{children}</OnboardingProvider>
}
