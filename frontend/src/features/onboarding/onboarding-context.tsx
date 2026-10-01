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
 * 첫 진입(S01 · S02 · S13) 화면이 같이 쓰는 값과 이동. `app/(onboarding)/layout.tsx` 가
 * /start · /login* · /signup/* · /setup/* · /browse/region 을 감싼다.
 * 레이아웃은 이 화면들 사이를 오가도 다시 그려지지 않아 값이 남는다.
 *
 * **브라우저 저장소에 남기지 않는다** (docs/conventions.md "데이터와 환경변수"). 새로고침하면 사라지고,
 * 다음 단계는 값이 없으면 동네 선택으로 돌려보낸다. 서버 저장은 동의(S02-3) 뒤 연동 이슈에서 붙인다.
 */
/**
 * 이메일 가입(S13-2 ~ S13-4) 중에 모으는 값. **메모리에만 둔다** — 브라우저 저장소 · 주소 · 로그에 남기지 않는다.
 * 비밀번호는 가입 요청(S02-3 동의 뒤)에 한 번 보내고 버린다. 그 전에 새로고침하면 처음부터 다시 한다.
 *
 * 그만둔 초안은 지운다: 로그인 방법 선택(`/login`)에 들어오면 이메일만 남기고, 카카오로 시작하면 전부 비운다.
 * S02-3 에서 가입 종류는 `verificationToken` 유무로 가린다 — 있으면 이메일 가입, 없으면 카카오 가입이다.
 * 위 규칙 덕분에 이메일 가입을 하다 카카오로 바꾸면 토큰이 남지 않는다. 초안을 지우는 곳을 바꿀 때 이 판단이 깨지지 않게 한다.
 */
export type SignupDraft = {
  email: string
  /** 인증 코드를 보낸 시각(ms). 남은 시간 · 다시 받기 대기를 이 시각으로 계산한다. 보내기 전이면 null */
  codeSentAt: number | null
  /** 인증을 마쳤다는 값(목은 아무 뜻 없는 문자열). 마치기 전이면 null */
  verificationToken: string | null
  password: string
  nickname: string
}

export const EMPTY_SIGNUP: SignupDraft = {
  email: '',
  codeSentAt: null,
  verificationToken: null,
  password: '',
  nickname: '',
}

type OnboardingState = {
  district: District | null
  /** 고른 동네. 검색어를 바꾸면 null 로 지운다 */
  setDistrict: (district: District | null) => void
  adultConfirmed: boolean
  setAdultConfirmed: (confirmed: boolean) => void
  signup: SignupDraft
  /** 바꿀 값만 넘긴다 */
  updateSignup: (patch: Partial<SignupDraft>) => void
  /** 가입 초안을 비운다. `keepEmail` 이면 쓴 이메일만 남긴다(비밀번호 · 토큰 · 보낸 시각은 늘 지운다) */
  resetSignup: (options?: { keepEmail?: boolean }) => void
  /**
   * 앞 단계로. 앱 안에서 앞 단계 후보 중 하나를 거쳐 왔으면 기록을 되돌리고(휴대폰 뒤로 가기와 같다),
   * 주소로 바로 들어와 앞 단계 기록이 없으면 첫 후보로 기록을 쌓지 않고 바꿔 간다.
   * 후보가 여럿인 것은 같은 화면에 여러 길로 오기 때문이다 (동네 선택 ← 로그인 · 이메일 가입).
   */
  goBack: (previousPaths: string | readonly [string, ...string[]]) => void
  /** 기록을 쌓지 않고 간다 (값이 없어 앞 단계로 돌려보낼 때) */
  replace: (path: string) => void
}

const OnboardingContext = createContext<OnboardingState | null>(null)

export function OnboardingProvider({
  children,
  initialDistrict = null,
  initialSignup = EMPTY_SIGNUP,
}: {
  children: ReactNode
  /** 처음 고른 동네. 테스트에서 다음 단계부터 그릴 때 쓴다 — 화면은 늘 비워 시작한다 */
  initialDistrict?: District | null
  /** 처음 가입 값. 테스트에서 다음 단계부터 그릴 때 쓴다 */
  initialSignup?: SignupDraft
}) {
  const router = useRouter()
  const pathname = usePathname()
  const [district, setDistrict] = useState<District | null>(initialDistrict)
  const [adultConfirmed, setAdultConfirmed] = useState(false)
  const [signup, setSignup] = useState<SignupDraft>(initialSignup)
  const updateSignup = useCallback(
    (patch: Partial<SignupDraft>) => setSignup((current) => ({ ...current, ...patch })),
    [],
  )
  const resetSignup = useCallback(
    ({ keepEmail = false }: { keepEmail?: boolean } = {}) =>
      setSignup((current) => ({ ...EMPTY_SIGNUP, email: keepEmail ? current.email : '' })),
    [],
  )

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
    (previousPaths: string | readonly [string, ...string[]]) => {
      const candidates = typeof previousPaths === 'string' ? [previousPaths] : previousPaths
      if (canGoBackTo(trail.current, candidates)) router.back()
      else replace(candidates[0])
    },
    [router, replace],
  )

  const value = useMemo(
    () => ({
      district,
      setDistrict,
      adultConfirmed,
      setAdultConfirmed,
      signup,
      updateSignup,
      resetSignup,
      goBack,
      replace,
    }),
    [district, adultConfirmed, signup, updateSignup, resetSignup, goBack, replace],
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
