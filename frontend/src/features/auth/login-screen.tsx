'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { KakaoButton } from '@/components/kakao-button'
import { ToastRegion, useToast } from '@/components/toast'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { LOGIN_EMAIL_PATH, SIGNUP_EMAIL_PATH, START_PATH } from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'

import { startKakaoLogin } from './auth-client'
import type { LoginNotice } from './login-notice'

/**
 * S13-1 로그인 방법 고르기. 단계 표시는 없다.
 *
 * | 상태 | 주소 | 보이는 것 |
 * | --- | --- | --- |
 * | 기본 | `/login` | 카카오로 계속하기 · 이메일로 가입하기 · 이메일로 로그인 |
 * | 카카오 실패 | `?error=kakao-fail` | 빨강 상자 + 기본 버튼 |
 * | 이메일 회원과 겹침 | `?error=kakao-exists` | 파랑 상자 안 이메일로 로그인 + 다른 카카오 계정으로 계속하기 |
 * | 로그인 만료 | `?reason=expired` | 기본 + "다시 로그인해 주세요" 토스트 |
 *
 * 시안: docs/design/auth/screens/ 의 Login · Login-kakao-fail · Login-kakao-exists (+ -T · -D)
 */
export function LoginScreen({ notice }: { notice: LoginNotice | null }) {
  const router = useRouter()
  const { goBack, resetSignup, updateSignup } = useOnboarding()
  const active = useActiveRef()
  const { toast, show, dismiss } = useToast()
  const [pending, setPending] = useState(false)
  const exists = notice === 'kakao-exists'

  useEffect(() => {
    if (notice === 'expired') show({ message: '다시 로그인해 주세요' })
  }, [notice, show])

  // 그만둔 가입의 가입 종류 · 인증 · 비밀번호를 지운다. 이메일 가입으로 다시 갈 수 있어 이메일만 남긴다
  useEffect(() => {
    resetSignup({ keepEmail: true })
  }, [resetSignup])

  async function continueWithKakao() {
    setPending(true)
    // 카카오 가입은 이메일 가입 초안을 쓰지 않는다. 비운 뒤 가입 종류를 카카오로 둔다 — S02-3 이 이 값으로 요청을 고른다.
    // 연동 때 카카오 콜백은 페이지를 새로 열어 메모리가 비므로, 콜백이 돌아오는 화면에서 다시 'kakao' 로 둬야 한다
    resetSignup()
    updateSignup({ method: 'kakao' })
    try {
      const { redirectTo } = await startKakaoLogin({ switchAccount: exists })
      if (!active.current) return
      // 이동하는 동안은 꺼진 채로 둔다 — 다시 눌러 두 번 시작하지 않게 한다
      router.push(redirectTo)
    } catch {
      if (!active.current) return
      show({ message: '카카오 로그인을 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.' })
      setPending(false)
    }
  }

  return (
    <OnboardingLayout
      onBack={() => goBack(START_PATH)}
      panelTitle={
        <>
          보고는 회원만
          <br />할 수 있어요
        </>
      }
      footer={
        <>
          <KakaoButton disabled={pending} onClick={() => void continueWithKakao()}>
            {exists ? '다른 카카오 계정으로 계속하기' : '카카오로 계속하기'}
          </KakaoButton>
          {!exists && (
            <>
              <Button variant="secondary" fullWidth onClick={() => router.push(SIGNUP_EMAIL_PATH)}>
                이메일로 가입하기
              </Button>
              <p className="flex items-center justify-center gap-1">
                <span className="text-body-strong text-fg-sub">이메일 계정이 있어요</span>
                <Link
                  href={LOGIN_EMAIL_PATH}
                  className="flex min-h-touch items-center px-1 text-body font-semibold text-brand"
                >
                  이메일로 로그인
                </Link>
              </p>
            </>
          )}
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
          <AlertBox tone="danger">카카오 로그인을 마치지 못했어요. 다시 시도해 주세요.</AlertBox>
        )}
        {exists && (
          <AlertBox
            tone="info"
            action={
              <Button fullWidth onClick={() => router.push(LOGIN_EMAIL_PATH)}>
                이메일로 로그인
              </Button>
            }
          >
            이 카카오 계정의 이메일은 이미 이메일 회원으로 가입돼 있어요. 이메일로 로그인하면 기존
            보고를 그대로 쓸 수 있어요.
          </AlertBox>
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
