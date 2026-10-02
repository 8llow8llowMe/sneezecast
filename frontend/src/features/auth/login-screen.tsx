'use client'

import { useEffect } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { KakaoButton } from '@/components/kakao-button'
import { ToastRegion, useToast } from '@/components/toast'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { LOGIN_EMAIL_PATH, SIGNUP_EMAIL_PATH, START_PATH } from '@/features/onboarding/paths'
import { useNavTrail } from '@/lib/use-nav-trail'

import type { KakaoFailReason, LoginNotice } from './login-notice'
import { isReturning, loginHref, type LoginReturn, NO_LOGIN_RETURN } from './login-return'
import { KAKAO_START_FAILURE_TEXT, useKakaoStart } from './use-kakao-start'

/**
 * 카카오 로그인을 마치지 못한 사유별 문장(`?error=kakao-fail&kakao=<사유>`, #167). 사유가 없으면 시안 Login-kakao-fail 문장이다.
 * 사유별 문장은 시안에 없어 백엔드 오류 문구를 화면 말투로 옮겼다(docs/design/SCREENS.md "카카오 로그인")
 */
const KAKAO_FAIL_TEXT: Readonly<Record<KakaoFailReason | 'default', string>> = {
  default: '카카오 로그인을 마치지 못했어요. 다시 시도해 주세요.',
  'email-required':
    '카카오 계정의 이메일을 받지 못했어요. 다시 시도할 때 이메일 제공에 동의해 주세요.',
  'email-unverified':
    '카카오 계정의 이메일이 인증되지 않았어요. 카카오에서 이메일을 인증한 뒤 다시 시도해 주세요.',
  expired: '시간이 지나 카카오 로그인을 마치지 못했어요. 카카오 로그인부터 다시 해 주세요.',
  suspended: '이용이 정지된 계정이에요.',
}

/**
 * S13-1 로그인 방법 고르기. 단계 표시는 없다.
 *
 * | 상태 | 주소 | 보이는 것 |
 * | --- | --- | --- |
 * | 기본 | `/login` | 카카오로 계속하기 · 이메일로 가입하기 · 이메일로 로그인 |
 * | 카카오 실패 | `?error=kakao-fail`(+ `&kakao=<사유>`) | 빨강 상자(사유별 문장) + 기본 버튼 |
 * | 로그인 만료 | `?reason=expired` | 기본 + "다시 로그인해 주세요" 토스트 |
 *
 * 이메일 회원과 겹침(시안 Login-kakao-exists)은 #167 에서 이 화면의 상태가 아니라 계정 연결 확인(`/login/kakao/link`)이 됐다.
 *
 * `카카오로 계속하기` 는 실데이터면 카카오 인가 화면으로 문서를 옮기고(돌아오면 콜백 `/login/kakao/callback`), 목이면 신규 회원으로 보아
 * 동네 선택(`?from=kakao`)으로 간다(`useKakaoStart`). 시작하지 못하면 토스트로 알린다(요청이 많음 `AUTH_028` 은 따로).
 *
 * **돌아갈 곳**(`?next=` · `?region=`, #123): 회원만 쓰는 화면(내 정보)의 가드가 붙여 보낸다. 이메일 로그인 링크에 그대로 넘겨
 * 로그인에 성공하면 그곳으로 간다(`login-return.ts`). 이때 뒤로는 앞 화면을 따지지 않고 되돌린다 — 가드가 기록을 바꿔 보내
 * 바로 앞이 내 정보 링크를 누른 화면이다. 돌아갈 곳이 없으면 지금처럼 시작 화면(S01)에서 왔을 때만 되돌린다.
 * 머리줄 보고 버튼이 보낸 보고하려던 로그인(`?intent=report`, #136)도 같다 — 이메일 로그인에 이어 넘기고, 뒤로는 보고를 누른 화면으로 되돌린다.
 * 카카오 · 이메일 가입은 아직 돌아갈 곳을 이어 받지 않는다(가입 · 카카오 로그인을 마치면 홈 — #140, `afterKakaoLoginPath`). 이메일 가입의 뒤로는 기록을 되돌려 이 주소(쿼리 포함)로 온다.
 *
 * 시안: docs/design/auth/screens/ 의 Login · Login-kakao-fail (+ -T · -D)
 */
export function LoginScreen({
  notice,
  kakaoReason = null,
  loginReturn = NO_LOGIN_RETURN,
}: {
  notice: LoginNotice | null
  /** 카카오 실패 사유(`?kakao=`). `kakao-fail` 일 때만 쓴다 */
  kakaoReason?: KakaoFailReason | null
  /** 로그인 뒤 돌아갈 곳. 라우트가 `?next=` · `?region=` · `?intent=` 에서 읽어 넘긴다 */
  loginReturn?: LoginReturn
}) {
  const router = useRouter()
  const navTrail = useNavTrail()
  const { goBack, resetSignup, updateSignup, clearPasswordReset, setKakaoLinkEmail } =
    useOnboarding()
  const { toast, show, dismiss } = useToast()
  const kakao = useKakaoStart()
  const emailLoginHref = loginHref(LOGIN_EMAIL_PATH, loginReturn)

  useEffect(() => {
    if (notice === 'expired') show({ message: '다시 로그인해 주세요' })
  }, [notice, show])

  // 그만둔 가입의 가입 종류 · 인증 · 비밀번호를 지운다. 이메일 가입으로 다시 갈 수 있어 이메일만 남긴다.
  // 그만둔 비밀번호 재설정(S13-6)의 보낸 시각 · 인증 시각, 그만둔 카카오 계정 연결 확인의 가린 이메일도 지운다
  useEffect(() => {
    resetSignup({ keepEmail: true })
    clearPasswordReset({ keepEmail: true })
    setKakaoLinkEmail(null)
  }, [resetSignup, clearPasswordReset, setKakaoLinkEmail])

  async function continueWithKakao() {
    // 카카오 가입은 이메일 가입 초안을 쓰지 않는다. 비운 뒤 가입 종류를 카카오로 둔다 — S02-3 이 이 값으로 요청을 고른다(목).
    // 실데이터는 카카오를 다녀오며 페이지를 새로 열어 메모리가 비므로, 콜백이 보내는 S02-1(`?from=kakao`)이 다시 'kakao' 로 둔다
    resetSignup()
    updateSignup({ method: 'kakao' })
    const failure = await kakao.start()
    if (failure) show({ message: KAKAO_START_FAILURE_TEXT[failure] })
  }

  return (
    <OnboardingLayout
      onBack={() => (isReturning(loginReturn) ? navTrail.goBack(START_PATH) : goBack(START_PATH))}
      panelTitle={
        <>
          보고는 회원만
          <br />할 수 있어요
        </>
      }
      footer={
        <>
          <KakaoButton disabled={kakao.pending} onClick={() => void continueWithKakao()}>
            카카오로 계속하기
          </KakaoButton>
          <Button variant="secondary" fullWidth onClick={() => router.push(SIGNUP_EMAIL_PATH)}>
            이메일로 가입하기
          </Button>
          <p className="flex items-center justify-center gap-1">
            <span className="text-body-strong text-fg-sub">이메일 계정이 있어요</span>
            <Link
              href={emailLoginHref}
              className="flex min-h-touch items-center px-1 text-body font-semibold text-brand"
            >
              이메일로 로그인
            </Link>
          </p>
          <p className="text-center text-sub text-fg-sub">카카오에서는 이메일과 닉네임만 받아요.</p>
        </>
      }
    >
      <div className="flex flex-col gap-5">
        <div>
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
            계정을 만들거나
            <br />
            로그인해 주세요
          </h1>
          <p className="mt-2 text-body leading-[1.6] text-fg-sub">
            증상 보고는 회원만 할 수 있어요. 동네 현황은 로그인 없이도 볼 수 있어요.
          </p>
        </div>

        {notice === 'kakao-fail' && (
          <AlertBox tone="danger">{KAKAO_FAIL_TEXT[kakaoReason ?? 'default']}</AlertBox>
        )}
      </div>

      {/* 시안: 화면 아래에서 120 위, 좌우 20 여백 · 최대 420. 데스크톱은 화면 가운데라 일러스트 패널 위에 걸친다 */}
      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-30 z-10 mx-auto max-w-115 px-page-mobile"
      />
    </OnboardingLayout>
  )
}
