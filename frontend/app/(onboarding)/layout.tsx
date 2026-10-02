import { type ReactNode, Suspense } from 'react'

import { GuestOnlyGate } from '@/features/auth/guest-only-gate'
import { OnboardingProvider } from '@/features/onboarding/onboarding-context'

/**
 * 첫 진입(/start · /login* · /signup/* · /password/reset* · /setup/* · /browse/region)과 약관 재동의(/terms/*)가
 * 고른 동네 · 성인 확인 · 가입 초안을 함께 쓰게 감싼다("뒤로" 판단은 루트의 앱 안 이동 기록, `useNavTrail`).
 * 괄호 폴더(route group)라 주소에는 나타나지 않는다. 새로고침하면 값이 사라진다.
 *
 * 회원이 시작 · 로그인 · 가입 화면을 열면 홈(또는 `?next=`)으로 보낸다(`GuestOnlyGate`, #127). 가드는 주소 쿼리를 읽어
 * 정적 생성이 멈추지 않게 Suspense 로 감싼다. 화면 뒤에 두어 화면의 돌려보내기(값이 없어 앞 단계로)보다 나중에 보낸다.
 */
export default function OnboardingGroupLayout({ children }: { children: ReactNode }) {
  return (
    <OnboardingProvider>
      {children}
      <Suspense fallback={null}>
        <GuestOnlyGate />
      </Suspense>
    </OnboardingProvider>
  )
}
