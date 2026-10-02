'use client'

import { useEffect, useState } from 'react'

import { AlertBox } from '@/components/alert-box'
import { ToastRegion, useToast } from '@/components/toast'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { SETUP_TERMS_PATH } from '@/features/onboarding/paths'
import type { DataSource } from '@/lib/data-source'
import { getSessionSnapshot } from '@/lib/session/session-store'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import { agreeHealthConsent, getMockSession } from './auth-client'
import { LEGAL_TEXT_NOT_READY } from './consent-row'
import { HealthConsentActions, HealthConsentCheck, HealthConsentNotice } from './health-consent'
import { consentFor } from './legal'
import { afterLoginHref } from './login-return'
import { takeLoginReturn } from './login-return-store'
import { authStateOf } from './use-auth'

/**
 * 동의가 회원 상태에 반영돼 홈이 보고 흐름을 열 수 있는지(동의 뒤 보고 진입을 붙일지 정하는 **한 곳**, #140).
 * 홈은 회원 상태로 보고 진입을 고친다(`guardReportEntry`) — 상태가 아직 미동의면 `report=start` 가 동의 시트로 바뀌어, 방금 동의한 사람에게
 * 동의를 다시 묻는다. 그래서 반영되지 않았으면 보고 진입 없이 같은 동네 홈으로만 보낸다.
 * - 목데이터: `agreeHealthConsent` 가 목 세션을 `member` 로 바꿔 늘 반영된다
 * - 실데이터: 세션 요약의 `reportWritable` 이다(`authStateOf`). **#168 전까지 동의 보내기는 목이라 요약이 바뀌지 않아 늘 거짓이다.**
 *   #168 에서 동의를 연동하고 응답 뒤 세션 요약을 다시 받으면(`refreshSession`) 이 판정이 참이 되어 보고 진입이 자연히 붙는다
 */
function healthConsentReflected(source: DataSource): boolean {
  const auth = source === 'api' ? authStateOf(getSessionSnapshot()) : getMockSession()
  return auth === 'member'
}

/**
 * 가입을 마친 뒤 갈 곳(#140). 로그인 화면 · 로그인 안내 시트에서 가입으로 떠날 때 둔 돌아갈 곳(`login-return-store.ts`)을 읽고 지운다.
 * 보고하려던 가입이면 같은 동네 홈의 보고 진입(`/?region=…&report=start`)이다 — `reportEntry` 가 참일 때만(동의했고 회원 상태에 반영됨,
 * `healthConsentReflected`). `나중에 할게요` 는 보고 진입을 붙이지 않는다 — 붙이면 미동의 회원이라 홈이 방금 미룬 동의 시트를 다시 연다.
 * 없으면 홈이다
 */
function afterSignupHref({ reportEntry }: { reportEntry: boolean }): string {
  const loginReturn = takeLoginReturn()
  return afterLoginHref(reportEntry ? loginReturn : { ...loginReturn, intent: null })
}

/**
 * S02-4 증상 보고 동의 (4 / 4). 가입을 마친 뒤 건강정보 동의를 따로 받는다.
 *
 * - "동의하고 시작하기" 는 체크해야 켜지고, 동의를 보낸 뒤 홈(로그인 뒤 돌아갈 곳이 있으면 그곳, `afterSignupHref`)으로 기록을 바꿔 간다
 * - "나중에 할게요" 는 동의 없이 홈(또는 돌아갈 곳 — 보고 진입은 붙이지 않음)으로 간다(가입과 둘러보기는 되고 증상 보고만 막힌다)
 * - **뒤로 버튼이 없다.** 시안에는 있지만 가입을 마친 뒤라 가입 동의로 돌아가면 다시 가입하게 된다.
 *   브라우저 뒤로 가기도 가입 동의가 기록을 바꿔 들어와 이 화면 앞으로는 가입 동의가 없다
 * - 가입을 마치지 않았으면(바로 들어옴 · 새로고침) 가입 동의로 돌려보낸다
 *
 * 시안: docs/design/auth/screens/ 의 Setup-4 (unchecked · checked, + -T · -D)
 */
export function HealthConsentScreen() {
  const { membership, replace } = useOnboarding()
  const active = useActiveRef()
  const source = useDataSource()
  const { toast, show, dismiss } = useToast()
  const [checked, setChecked] = useState(false)
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)

  const registered = membership.regionSaved
  useEffect(() => {
    if (!registered) replace(SETUP_TERMS_PATH)
  }, [registered, replace])

  if (!registered) return null

  async function agree() {
    if (pending) return
    setPending(true)
    setFailed(false)
    try {
      await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
      if (!active.current) return
      replace(afterSignupHref({ reportEntry: healthConsentReflected(source) }))
    } catch {
      if (active.current) {
        setFailed(true)
        setPending(false)
      }
    }
  }

  return (
    <OnboardingLayout
      step={4}
      panelTitle={
        <>
          건강 정보는
          <br />
          따로 동의를 받아요
        </>
      }
      footer={
        <HealthConsentActions
          agreeLabel="동의하고 시작하기"
          checked={checked}
          pending={pending}
          onAgree={() => void agree()}
          onLater={() => {
            if (!pending) replace(afterSignupHref({ reportEntry: false }))
          }}
        />
      }
    >
      <div className="flex flex-col gap-5">
        <div>
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
            증상 보고에 동의해 주세요
          </h1>
          <p className="mt-2 text-body leading-[1.6] text-fg-sub">
            건강 정보라서 가입 약관과 따로 동의를 받아요.
          </p>
        </div>

        <HealthConsentNotice />
        <HealthConsentCheck
          checked={checked}
          onChange={setChecked}
          onView={() => show({ message: LEGAL_TEXT_NOT_READY })}
        />

        {failed && (
          <AlertBox tone="danger">동의를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
      </div>

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-30 z-10 mx-auto max-w-115 px-page-mobile"
      />
    </OnboardingLayout>
  )
}
