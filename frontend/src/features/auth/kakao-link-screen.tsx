'use client'

import { useEffect, useState } from 'react'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { KakaoButton } from '@/components/kakao-button'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { LOGIN_PATH } from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import { linkKakaoAccount } from './kakao-client'
import { kakaoFailPath } from './login-notice'
import { afterKakaoLoginPath } from './login-return'
import { KAKAO_START_FAILURE_TEXT, type KakaoStartFailure, useKakaoStart } from './use-kakao-start'

type Failure = 'link' | KakaoStartFailure | null

/**
 * 카카오 계정 연결 확인 (`/login/kakao/link`, #167). 카카오 콜백이 `LINK_REQUIRED`(카카오 이메일로 가입한 이메일 계정이 있음)를 받으면 온다.
 * 시안 Login-kakao-exists 자리이고, 백엔드 #61 화면 계약대로 문구 · 버튼만 바꿨다 — 파랑 상자 안에 연결을 묻는 문장 · 가린 이메일 ·
 * `연결하고 계속하기`, 아래에 `다른 카카오 계정으로 계속하기`.
 *
 * - `연결하고 계속하기`: `POST /api/v1/auth/kakao/link`(연결 확인표 쿠키, 10분). 성공하면 로그인된 채 홈(`afterKakaoLoginPath`)으로
 *   기록을 바꿔 간다. 확인표가 지났거나(`AUTH_026`) 연결할 수 없는 계정이면(`AUTH_027`) 카카오 로그인부터 다시 하게
 *   `/login?error=kakao-fail` 로 간다. 서비스가 그 밖의 업무 오류로 답해도(확인표를 이미 잃음) 같다. 응답을 받지 못한 실패
 *   (네트워크 · 타임아웃 · 게이트웨이)만 빨강 상자로 알리고 다시 누를 수 있다(`kakaoTicketLost`)
 * - `다른 카카오 계정으로 계속하기`: 카카오 계정을 고르게 하고 처음부터 다시 한다(`switchAccount`). 백엔드가 이 요청에서 확인표를 지운다
 * - 가린 이메일은 첫 진입 Provider 메모리로만 받는다. 없으면(새로고침 · 바로 들어옴) 로그인 방법 선택으로 기록을 바꿔 간다
 * - 연결해도 이메일 · 비밀번호 로그인은 그대로 된다. 회원이 닿으면 첫 진입 가드가 홈으로 보낸다(`GUEST_ONLY_PATHS`)
 *
 * 시안: docs/design/auth/screens/ 의 Login-kakao-exists (+ -T · -D) — 상자 문장 · 버튼은 시안 없음(백엔드 계약 문장)
 */
export function KakaoLinkScreen() {
  const { kakaoLinkEmail, goBack, replace } = useOnboarding()
  const source = useDataSource()
  const active = useActiveRef()
  const kakao = useKakaoStart()
  const [linking, setLinking] = useState(false)
  const [failure, setFailure] = useState<Failure>(null)
  const pending = linking || kakao.pending

  const missing = kakaoLinkEmail === null
  useEffect(() => {
    if (missing) replace(LOGIN_PATH)
  }, [missing, replace])

  if (kakaoLinkEmail === null) return null

  async function link() {
    if (pending) return
    setLinking(true)
    setFailure(null)
    try {
      const result = await linkKakaoAccount(source)
      if (!active.current) return
      // 이동하는 동안은 보내는 중으로 둔다 — 다시 눌러 두 번 연결하지 않게 한다
      replace(result.status === 'ok' ? afterKakaoLoginPath() : kakaoFailPath(result.reason))
    } catch {
      if (!active.current) return
      setFailure('link')
      setLinking(false)
    }
  }

  async function switchAccount() {
    if (pending) return
    setFailure(null)
    const failed = await kakao.start({ switchAccount: true })
    if (failed) setFailure(failed)
  }

  return (
    <OnboardingLayout
      onBack={() => {
        if (!pending) goBack(LOGIN_PATH)
      }}
      backDisabled={pending}
      panelTitle={
        <>
          보고는 회원만
          <br />할 수 있어요
        </>
      }
      footer={
        <>
          <KakaoButton disabled={pending} onClick={() => void switchAccount()}>
            다른 카카오 계정으로 계속하기
          </KakaoButton>
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

        <AlertBox
          tone="info"
          role="alert"
          action={
            <Button fullWidth aria-disabled={pending || undefined} onClick={() => void link()}>
              연결하고 계속하기
            </Button>
          }
        >
          이 이메일로 가입된 계정이 있어요. 카카오 로그인을 연결할까요?
          <span className="mt-1 block font-semibold">{kakaoLinkEmail}</span>
        </AlertBox>
        <p className="text-sub leading-[1.6] text-fg-sub">
          연결해도 이메일과 비밀번호로 계속 로그인할 수 있어요.
        </p>

        {failure === 'link' && (
          <AlertBox tone="danger">연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
        {failure !== null && failure !== 'link' && (
          <AlertBox tone="danger">{KAKAO_START_FAILURE_TEXT[failure]}</AlertBox>
        )}
      </div>
    </OnboardingLayout>
  )
}
