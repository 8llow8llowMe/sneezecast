'use client'

import {
  createContext,
  Fragment,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react'

import type { District } from '@/features/region/types'
import { useClearOnPageFreeze } from '@/lib/use-clear-on-page-freeze'
import { useNavTrail } from '@/lib/use-nav-trail'

/**
 * 첫 진입(S01 · S02 · S13) 화면이 같이 쓰는 값과 이동. `app/(onboarding)/layout.tsx` 가
 * /start · /login* · /signup/* · /password/reset* · /setup/* · /browse/region · /terms/* 를 감싼다.
 * 레이아웃은 이 화면들 사이를 오가도 다시 그려지지 않아 값이 남는다.
 *
 * **브라우저 저장소에 남기지 않는다** (docs/conventions.md "데이터와 환경변수"). 새로고침하면 사라지고,
 * 다음 단계는 값이 없으면 앞 단계로 돌려보낸다. 서버에는 가입 동의(S02-3)에서 가입 · 로그인 · 내 동네 저장을 잇달아 보낸다.
 *
 * **뒤로 가기 캐시(bfcache)에 들어가기 직전에 가입 초안(비밀번호 · 이메일 · 닉네임 · 가입 종류) · 재설정 초안(토큰) · 연결 확인 이메일을
 * 비운다**(#186, `useClearOnPageFreeze`). 공용 기기에서 다음 사람이 뒤로 가기로 되살린 화면에서 앞 사람의 가입 · 재설정 · 카카오
 * 연결을 이어 가지 못하게 한다. 비우면 각 단계 화면이 값이 없을 때처럼 흐름의 처음(이메일 단계 · 로그인 방법 선택)으로 돌려보낸다.
 * 동네 · 성인 확인 · 가입 마무리 진행(`membership`)은 둔다 — 진행은 이미 만든 계정 · 세션에 딸린 값이고 세션은 세션 저장소가 다시 확인한다
 */
/**
 * 가입(S13-2 ~ S13-4 · S02-3) 중에 모으는 값. **메모리에만 둔다** — 브라우저 저장소 · 주소 · 로그에 남기지 않는다.
 * 비밀번호는 가입 요청(S02-3 동의 뒤)과 이어지는 로그인에 쓰고, 로그인을 마치면 버린다. 그 전에 새로고침하면 처음부터 다시 한다.
 *
 * 가입 종류는 `method` 로 명시한다 — 이메일 화면이 코드를 보내면 `'email'`, 로그인 화면에서 카카오로 시작하면 `'kakao'`.
 * S02-3 은 이 값으로 요청 종류를 가리고, 모르면(`null`) 로그인 방법 선택으로 돌려보낸다.
 * 그만둔 초안은 지운다: 로그인 방법 선택(`/login`)에 들어오면 이메일만 남기고(가입 종류도 지운다),
 * 카카오로 시작하면 전부 비운 뒤 `'kakao'` 로 둔다. 두 경우 모두 `resetSignup` 이 가입 마무리 진행(`Membership`)도 비운다.
 */
export type SignupDraft = {
  /** 가입 종류. 아직 고르지 않았으면 null */
  method: 'email' | 'kakao' | null
  email: string
  /** 인증 코드를 보낸 시각(ms). 남은 시간 · 다시 받기 대기를 이 시각으로 계산한다. 보내기 전이면 null */
  codeSentAt: number | null
  /**
   * 이메일 인증을 마친 시각(ms). 마치기 전이면 null. 서버가 인증 표시를 30분 들고 있어 토큰은 없다 —
   * 지났는지는 가입 요청에 서버가 답한다(`verification-expired`)
   */
  verifiedAt: number | null
  password: string
  nickname: string
}

export const EMPTY_SIGNUP: SignupDraft = {
  method: null,
  email: '',
  codeSentAt: null,
  verifiedAt: null,
  password: '',
  nickname: '',
}

/**
 * 가입 마무리 진행 (S02-3). 가입 → (이메일 가입만) 로그인 → 내 동네 저장 중 하나가 실패해도
 * 다시 누를 때 끝난 단계를 다시 보내지 않게 나눠 둔다(가입을 두 번 보내지 않는다).
 * 카카오 가입은 이미 로그인한 상태라 가입이 되면 `loggedIn` 도 참이다.
 *
 * **가입 시도 하나에만 딸린 값이다.** 새 가입 시도가 시작되면 비운다 — `resetSignup`(로그인 방법 선택 진입 ·
 * 카카오 시작)과 이메일 화면이 코드를 보낼 때. 남아 있으면 다른 계정으로 가입할 때 가입을 건너뛰고
 * 동네만 저장하거나, 없는 계정으로 로그인을 되풀이한다.
 */
export type Membership = { accountCreated: boolean; loggedIn: boolean; regionSaved: boolean }

export const NO_MEMBERSHIP: Membership = {
  accountCreated: false,
  loggedIn: false,
  regionSaved: false,
}

/**
 * 비밀번호 재설정(S13-6) 중에 모으는 값. 가입 초안(`signup`)과 섞지 않는다 — 가입 인증과 재설정 인증은 서버에서 따로라
 * 한쪽 인증으로 다른 쪽 단계를 열면 안 된다. **메모리에만 둔다** — 브라우저 저장소 · 주소 · 로그에 남기지 않는다.
 * 특히 재설정 토큰은 비밀번호를 바꿀 수 있는 값이라 주소 쿼리 · 콘솔 · 저장소 어디에도 쓰지 않는다.
 * 새 비밀번호는 여기 두지 않는다. 새 비밀번호 화면 상태에만 두고 요청에 쓴 뒤 버린다.
 *
 * 비우는 때:
 * - 이메일 단계가 코드를 새로 보내면 세 값을 모두 새로 쓴다(앞선 코드 · 토큰은 무효다)
 * - 코드를 다시 받으면 토큰을 지운다
 * - 재설정을 마치면 보낸 시각 · 토큰을 지운다. 이메일만 남겨 이어지는 이메일 로그인(S13-5)의 이메일 칸을 채운다
 * - 재설정이 인증 만료로 돌아오면 보낸 시각 · 토큰을 지운다(이메일 단계부터 다시 한다)
 * - 이메일 로그인(`/login/email`) · 로그인 방법 선택(`/login`)에 들어오면 이메일만 남기고 지운다 — 그만둔 재설정이다.
 *   공용 기기에서 앞으로 가기로 새 비밀번호 화면에 다시 들어가지 못하게 한다
 */
export type PasswordResetDraft = {
  email: string
  /** 인증 코드를 보낸 시각(ms). 보내기 전이면 null */
  codeSentAt: number | null
  /**
   * 코드를 맞히고 받은 일회용 재설정 토큰(수명 15분). 받기 전이면 null. 새 비밀번호 화면은 이 값이 있어야 열린다.
   * 지났는지 · 썼는지는 재설정 요청에 서버가 답한다(`verification-expired`)
   */
  resetToken: string | null
}

export const EMPTY_PASSWORD_RESET: PasswordResetDraft = {
  email: '',
  codeSentAt: null,
  resetToken: null,
}

type OnboardingState = {
  district: District | null
  /** 고른 동네. 검색어를 바꾸면 null 로 지운다 */
  setDistrict: (district: District | null) => void
  /**
   * 가입 동네 고르기(S02-1)가 넘겨받은 둘러보기 동네(`?region=`, #227)를 이미 처음 선택으로 다뤘는지. **한 번만 채운다** —
   * 채운 선택을 지우고 뒤로 갔다 다시 와도 다시 채우지 않는다. 그때 이미 고른 동네가 있었으면 채우지 않고 다룬 것으로 둔다.
   * 메모리에만 두어 새로고침 · 카카오 왕복(문서를 새로 엶)이면 다시 거짓이다
   */
  regionPresetUsed: boolean
  markRegionPresetUsed: () => void
  adultConfirmed: boolean
  setAdultConfirmed: (confirmed: boolean) => void
  signup: SignupDraft
  /** 바꿀 값만 넘긴다 */
  updateSignup: (patch: Partial<SignupDraft>) => void
  /**
   * 새 가입 시도를 위해 가입 초안과 가입 마무리 진행(`membership`)을 비운다. `keepEmail` 이면 쓴 이메일만 남긴다
   * (가입 종류 · 인증 시각 · 비밀번호 · 보낸 시각 · 닉네임 · 진행은 늘 지운다)
   */
  resetSignup: (options?: { keepEmail?: boolean }) => void
  passwordReset: PasswordResetDraft
  /** 바꿀 값만 넘긴다 */
  updatePasswordReset: (patch: Partial<PasswordResetDraft>) => void
  /** 재설정 초안을 비운다. `keepEmail` 이면 쓴 이메일만 남긴다 */
  clearPasswordReset: (options?: { keepEmail?: boolean }) => void
  /**
   * [선택] 주간 보고 알림 받기 (S02-3). 백엔드 알림 동의(`PUSH_NOTIFICATION`)는 푸시와 함께 2단계라
   * 지금은 여기에만 들고 가입 요청에는 넣지 않는다
   */
  notificationOptIn: boolean
  setNotificationOptIn: (value: boolean) => void
  membership: Membership
  updateMembership: (patch: Partial<Membership>) => void
  /**
   * 카카오 계정 연결 확인(`/login/kakao/link`, #167)에 보일 가린 이메일(`d***@example.com`). 카카오 콜백이 `LINK_REQUIRED` 를 받으면 넣고,
   * 로그인 방법 선택(`/login`)에 들어오면 지운다. **메모리에만 둔다** — 주소 · 브라우저 저장소 · 로그에 남기지 않는다.
   * 없으면(새로고침 · 바로 들어옴) 연결 확인 화면이 로그인 방법 선택으로 돌려보낸다
   */
  kakaoLinkEmail: string | null
  setKakaoLinkEmail: (email: string | null) => void
  /**
   * 앞 단계로. 앱 안에서 앞 단계 후보 중 하나를 거쳐 왔으면 기록을 되돌리고(휴대폰 뒤로 가기와 같다),
   * 주소로 바로 들어와 앞 단계 기록이 없으면 첫 후보로 기록을 쌓지 않고 바꿔 간다.
   * 후보가 여럿인 것은 같은 화면에 여러 길로 오기 때문이다 (동네 선택 ← 로그인 · 이메일 가입).
   * 판단은 루트의 앱 안 이동 기록이 한다(`useNavTrail`, docs/conventions.md "화면의 뒤로").
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
  initialAdultConfirmed = false,
  initialMembership = NO_MEMBERSHIP,
  initialPasswordReset = EMPTY_PASSWORD_RESET,
  initialKakaoLinkEmail = null,
}: {
  children: ReactNode
  /**
   * 처음 고른 동네. 테스트에서 다음 단계부터 그릴 때 쓴다 — 화면은 늘 비워 시작한다
   * (둘러보던 동네를 넘겨받는 것은 레이아웃이 아니라 S02-1 페이지가 한다 — `RegionScreen` 의 `preset`)
   */
  initialDistrict?: District | null
  /** 처음 가입 값. 테스트에서 다음 단계부터 그릴 때 쓴다 */
  initialSignup?: SignupDraft
  /** 테스트에서 다음 단계부터 그릴 때 쓴다 */
  initialAdultConfirmed?: boolean
  initialMembership?: Membership
  /** 테스트에서 다음 단계부터 그릴 때 쓴다 */
  initialPasswordReset?: PasswordResetDraft
  /** 테스트에서 계정 연결 확인부터 그릴 때 쓴다 */
  initialKakaoLinkEmail?: string | null
}) {
  const { replace, goBack: goBackInTrail } = useNavTrail()
  const [district, setDistrict] = useState<District | null>(initialDistrict)
  const [regionPresetUsed, setRegionPresetUsed] = useState(false)
  const markRegionPresetUsed = useCallback(() => setRegionPresetUsed(true), [])
  const [adultConfirmed, setAdultConfirmed] = useState(initialAdultConfirmed)
  const [notificationOptIn, setNotificationOptIn] = useState(false)
  const [kakaoLinkEmail, setKakaoLinkEmail] = useState<string | null>(initialKakaoLinkEmail)
  const [membership, setMembership] = useState<Membership>(initialMembership)
  const updateMembership = useCallback(
    (patch: Partial<Membership>) => setMembership((current) => ({ ...current, ...patch })),
    [],
  )
  const [signup, setSignup] = useState<SignupDraft>(initialSignup)
  const updateSignup = useCallback(
    (patch: Partial<SignupDraft>) => setSignup((current) => ({ ...current, ...patch })),
    [],
  )
  const resetSignup = useCallback(({ keepEmail = false }: { keepEmail?: boolean } = {}) => {
    setSignup((current) => ({ ...EMPTY_SIGNUP, email: keepEmail ? current.email : '' }))
    // 진행은 그 가입 시도에만 딸린 값이다. 남으면 다른 계정의 가입을 건너뛴다
    setMembership(NO_MEMBERSHIP)
  }, [])

  const [passwordReset, setPasswordReset] = useState<PasswordResetDraft>(initialPasswordReset)
  const updatePasswordReset = useCallback(
    (patch: Partial<PasswordResetDraft>) =>
      setPasswordReset((current) => ({ ...current, ...patch })),
    [],
  )
  const clearPasswordReset = useCallback(
    ({ keepEmail = false }: { keepEmail?: boolean } = {}) =>
      setPasswordReset((current) => ({
        ...EMPTY_PASSWORD_RESET,
        email: keepEmail ? current.email : '',
      })),
    [],
  )

  useClearOnPageFreeze(() => {
    setSignup(EMPTY_SIGNUP)
    setPasswordReset(EMPTY_PASSWORD_RESET)
    setKakaoLinkEmail(null)
    // 성인 확인 · 알림 선택도 사람마다 받는다 — 다음 사람의 가입에 미리 체크된 채로 보이지 않게(가입 초안을 비우면 흐름은 어차피 처음부터다)
    setAdultConfirmed(false)
    setNotificationOptIn(false)
  })
  // 비운 뒤 단계 화면이 보내는 이동(값이 없으면 처음으로)은 얼기 직전에 나가 브라우저가 버린다(Chrome 실측 — 빈 화면으로 되살아났다).
  // 되살아나면 화면을 다시 붙여 단계 확인을 다시 돌린다. 화면 상태는 어차피 비웠다
  const [restored, setRestored] = useState(0)
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) setRestored((count) => count + 1)
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [])

  const goBack = useCallback(
    (previousPaths: string | readonly [string, ...string[]]) => {
      const candidates = typeof previousPaths === 'string' ? [previousPaths] : previousPaths
      goBackInTrail(candidates[0], candidates)
    },
    [goBackInTrail],
  )

  const value = useMemo(
    () => ({
      district,
      setDistrict,
      regionPresetUsed,
      markRegionPresetUsed,
      adultConfirmed,
      setAdultConfirmed,
      signup,
      updateSignup,
      resetSignup,
      passwordReset,
      updatePasswordReset,
      clearPasswordReset,
      notificationOptIn,
      setNotificationOptIn,
      membership,
      updateMembership,
      kakaoLinkEmail,
      setKakaoLinkEmail,
      goBack,
      replace,
    }),
    [
      district,
      regionPresetUsed,
      markRegionPresetUsed,
      adultConfirmed,
      signup,
      updateSignup,
      resetSignup,
      passwordReset,
      updatePasswordReset,
      clearPasswordReset,
      notificationOptIn,
      membership,
      updateMembership,
      kakaoLinkEmail,
      goBack,
      replace,
    ],
  )

  return (
    <OnboardingContext value={value}>
      <Fragment key={restored}>{children}</Fragment>
    </OnboardingContext>
  )
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
