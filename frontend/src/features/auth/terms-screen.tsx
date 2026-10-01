'use client'

import { useEffect, useState } from 'react'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { Checkbox } from '@/components/checkbox'
import { ToastRegion, useToast } from '@/components/toast'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import {
  SETUP_ADULT_PATH,
  SETUP_HEALTH_CONSENT_PATH,
  SETUP_REGION_PATH,
} from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'

import { saveRegion, signup, type SignupRequest } from './auth-client'
import { ConsentRow, LEGAL_TEXT_NOT_READY } from './consent-row'
import { type Consent, consentFor } from './legal'
import { NO_CHECKS, requiredAgreed, setAll, type TermsChecks } from './terms-checks'

type Failure = 'signup' | 'region' | null

/**
 * S02-3 가입 동의 (3 / 4). 여기서 회원 가입 요청을 보내고 내 동네를 저장한 뒤 증상 보고 동의로 간다.
 *
 * - 필수 둘을 켜야 "동의하고 가입하기" 가 켜진다(incomplete 시안 — `aria-disabled`, 누르면 아무 일도 없다)
 * - 가입 종류는 Provider 의 인증 값으로 가린다: 있으면 이메일 가입(이메일 · 인증 값 · 비밀번호 · 닉네임 + 동의),
 *   없으면 카카오 가입(동의만). 성인 확인(S02-2)은 `AGE_OVER_19` 동의로 함께 보낸다
 * - 가입이 되면 비밀번호 · 인증 값을 바로 지운다. 동네 저장만 실패하면 다시 누를 때 가입을 두 번 보내지 않는다
 * - 증상 보고 동의로는 기록을 바꿔 간다 — 뒤로 가기로 이 화면에 돌아와 다시 가입하지 않게 한다
 * - 고른 동네 · 성인 확인이 없으면(바로 들어옴 · 새로고침) 앞 단계로, 이미 가입을 마쳤으면 다음 단계로 보낸다
 *
 * 시안: docs/design/auth/screens/ 의 Setup-3 (default · incomplete, + -T · -D)
 */
export function TermsScreen() {
  const {
    district,
    adultConfirmed,
    signup: draft,
    updateSignup,
    setNotificationOptIn,
    membership,
    updateMembership,
    goBack,
    replace,
  } = useOnboarding()
  const active = useActiveRef()
  const { toast, show, dismiss } = useToast()
  const [checks, setChecks] = useState<TermsChecks>(NO_CHECKS)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<Failure>(null)

  const redirect = !district
    ? SETUP_REGION_PATH
    : !adultConfirmed
      ? SETUP_ADULT_PATH
      : membership.regionSaved
        ? SETUP_HEALTH_CONSENT_PATH
        : null
  useEffect(() => {
    // 가입을 마친 직후 이동은 submit 이 한다. 여기서는 처음부터 갈 곳이 정해진 경우만 보낸다
    if (redirect && !pending) replace(redirect)
  }, [redirect, pending, replace])

  if (redirect || !district) return null

  const blocked = !requiredAgreed(checks) || pending
  const notReady = () => show({ message: LEGAL_TEXT_NOT_READY })

  function change(patch: Partial<TermsChecks>) {
    const next = { ...checks, ...patch }
    setChecks(next)
    setNotificationOptIn(next.notification)
  }

  function buildRequest(): SignupRequest {
    const consents: Consent[] = [
      consentFor('TERMS_OF_SERVICE'),
      consentFor('PRIVACY_POLICY'),
      consentFor('AGE_OVER_19'),
    ]
    if (draft.verificationToken === null) return { kind: 'kakao', consents }
    return {
      kind: 'email',
      email: draft.email,
      verificationToken: draft.verificationToken,
      password: draft.password,
      nickname: draft.nickname,
      consents,
    }
  }

  async function submit() {
    if (blocked || !district) return
    setPending(true)
    setFailure(null)
    try {
      if (!membership.accountCreated) {
        await signup(buildRequest())
        if (!active.current) return
        // 비밀번호 · 인증 값은 가입 요청에만 쓰고 버린다
        updateSignup({ password: '', verificationToken: null })
        updateMembership({ accountCreated: true })
      }
    } catch {
      if (active.current) {
        setFailure('signup')
        setPending(false)
      }
      return
    }
    try {
      await saveRegion(district.code)
      if (!active.current) return
      updateMembership({ regionSaved: true })
      replace(SETUP_HEALTH_CONSENT_PATH)
    } catch {
      if (active.current) {
        setFailure('region')
        setPending(false)
      }
    }
  }

  return (
    <OnboardingLayout
      step={3}
      onBack={() => goBack(SETUP_ADULT_PATH)}
      panelTitle={
        <>
          이름·연락처·주소는
          <br />
          받지 않아요
        </>
      }
      footer={
        <Button fullWidth aria-disabled={blocked || undefined} onClick={() => void submit()}>
          동의하고 가입하기
        </Button>
      }
    >
      <div className="flex flex-col gap-5">
        <div>
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
            가입 약관에 동의해 주세요
          </h1>
          <p className="mt-2 text-body leading-[1.6] text-fg-sub">
            건강·증상 정보 동의는 다음 단계에서 따로 받아요.
          </p>
        </div>

        <div className="flex flex-col">
          <div className="border-b border-divider pb-1">
            <Checkbox
              checked={requiredAgreed(checks)}
              onChange={(event) => {
                const next = setAll(event.target.checked)
                setChecks(next)
                setNotificationOptIn(next.notification)
              }}
              label="전체 동의"
            />
          </div>
          <ConsentRow
            tag="required"
            title="서비스 이용약관"
            checked={checks.terms}
            onChange={(terms) => change({ terms })}
            onView={notReady}
          />
          <ConsentRow
            tag="required"
            title="개인정보 수집·이용"
            detail="이메일, 닉네임, 행정동"
            checked={checks.privacy}
            onChange={(privacy) => change({ privacy })}
            onView={notReady}
          />
          <ConsentRow
            tag="optional"
            title="주간 보고 알림 받기"
            checked={checks.notification}
            onChange={(notification) => change({ notification })}
          />
        </div>

        {failure === 'signup' && (
          <AlertBox tone="danger">가입하지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
        {failure === 'region' && (
          <AlertBox tone="danger">동네를 저장하지 못했어요. 잠시 뒤 다시 눌러 주세요.</AlertBox>
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
