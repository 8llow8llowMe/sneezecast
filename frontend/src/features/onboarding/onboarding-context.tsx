'use client'

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { usePathname, useRouter } from 'next/navigation'

import type { District } from '@/features/region/types'

import { canGoBackTo, nextTrail } from './onboarding-trail'

/**
 * 첫 진입(S01 · S02) 화면이 같이 쓰는 값과 이동. `app/(onboarding)/layout.tsx` 가 /start · /setup/* 를 감싼다.
 * 레이아웃은 이 화면들 사이를 오가도 다시 그려지지 않아 값이 남는다.
 *
 * **브라우저 저장소에 남기지 않는다** (docs/conventions.md "데이터와 환경변수"). 새로고침하면 사라지고,
 * 다음 단계는 값이 없으면 동네 선택으로 돌려보낸다. 서버 저장은 동의(S02-3) 뒤 연동 이슈에서 붙인다.
 */
type OnboardingState = {
  district: District | null
  /** 고른 동네. 검색어를 바꾸면 null 로 지운다 */
  setDistrict: (district: District | null) => void
  adultConfirmed: boolean
  setAdultConfirmed: (confirmed: boolean) => void
  /**
   * 앞 단계로. 앱 안에서 그 단계를 거쳐 왔으면 기록을 되돌리고(휴대폰 뒤로 가기와 같다),
   * 주소로 바로 들어와 앞 단계 기록이 없으면 기록을 쌓지 않고 바꿔 간다.
   */
  goBack: (previousPath: string) => void
  /** 기록을 쌓지 않고 간다 (값이 없어 앞 단계로 돌려보낼 때) */
  replace: (path: string) => void
}

const OnboardingContext = createContext<OnboardingState | null>(null)

export function OnboardingProvider({
  children,
  initialDistrict = null,
}: {
  children: ReactNode
  /** 처음 고른 동네. 테스트에서 다음 단계부터 그릴 때 쓴다 — 화면은 늘 비워 시작한다 */
  initialDistrict?: District | null
}) {
  const router = useRouter()
  const pathname = usePathname()
  const [district, setDistrict] = useState<District | null>(initialDistrict)
  const [adultConfirmed, setAdultConfirmed] = useState(false)

  // 렌더에 쓰지 않는 이동 기록이라 ref 에 둔다. 주소가 바뀐 뒤(effect)에 갱신한다
  const trail = useRef<string[]>([])
  const replacing = useRef(false)

  useEffect(() => {
    trail.current = nextTrail(trail.current, pathname, replacing.current)
    replacing.current = false
  }, [pathname])

  const replace = useCallback(
    (path: string) => {
      replacing.current = true
      router.replace(path)
    },
    [router],
  )

  const goBack = useCallback(
    (previousPath: string) => {
      if (canGoBackTo(trail.current, previousPath)) router.back()
      else replace(previousPath)
    },
    [router, replace],
  )

  const value = useMemo(
    () => ({ district, setDistrict, adultConfirmed, setAdultConfirmed, goBack, replace }),
    [district, adultConfirmed, goBack, replace],
  )

  return <OnboardingContext value={value}>{children}</OnboardingContext>
}

export function useOnboarding(): OnboardingState {
  const state = useContext(OnboardingContext)
  if (!state) {
    throw new Error(
      'useOnboarding 은 OnboardingProvider 안에서만 쓴다 (app/(onboarding)/layout.tsx)',
    )
  }
  return state
}
