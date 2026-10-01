'use client'

import { useEffect, useState } from 'react'

import { AlertBox } from '@/components/alert-box'
import { ToastRegion, useToast } from '@/components/toast'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { HOME_PATH, SETUP_TERMS_PATH } from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'

import { agreeHealthConsent } from './auth-client'
import { LEGAL_TEXT_NOT_READY } from './consent-row'
import { HealthConsentActions, HealthConsentCheck, HealthConsentNotice } from './health-consent'
import { consentFor } from './legal'

/**
 * S02-4 증상 보고 동의 (4 / 4). 가입을 마친 뒤 건강정보 동의를 따로 받는다.
 *
 * - "동의하고 시작하기" 는 체크해야 켜지고, 동의를 보낸 뒤 홈으로 기록을 바꿔 간다
 * - "나중에 할게요" 는 동의 없이 홈으로 간다(가입과 둘러보기는 되고 증상 보고만 막힌다)
 * - **뒤로 버튼이 없다.** 시안에는 있지만 가입을 마친 뒤라 가입 동의로 돌아가면 다시 가입하게 된다.
 *   브라우저 뒤로 가기도 가입 동의가 기록을 바꿔 들어와 이 화면 앞으로는 가입 동의가 없다
 * - 가입을 마치지 않았으면(바로 들어옴 · 새로고침) 가입 동의로 돌려보낸다
 *
 * 시안: docs/design/auth/screens/ 의 Setup-4 (unchecked · checked, + -T · -D)
 */
export function HealthConsentScreen() {
  const { membership, replace } = useOnboarding()
  const active = useActiveRef()
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
      replace(HOME_PATH)
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
            if (!pending) replace(HOME_PATH)
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
