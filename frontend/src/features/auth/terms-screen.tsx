'use client'

import { useEffect, useState } from 'react'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { Checkbox } from '@/components/checkbox'
import { ToastRegion, useToast } from '@/components/toast'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import {
  LOGIN_PATH,
  SETUP_ADULT_PATH,
  SETUP_HEALTH_CONSENT_PATH,
  SETUP_REGION_PATH,
  SIGNUP_EMAIL_PATH,
  SIGNUP_EMAIL_VERIFICATION_EXPIRED_PATH,
} from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'

import {
  loginWithEmail,
  saveRegion,
  signup,
  type SignupRequest,
  type SignupResult,
} from './auth-client'
import { ConsentRow, LEGAL_TEXT_NOT_READY } from './consent-row'
import { type Consent, consentFor } from './legal'
import { NO_CHECKS, requiredAgreed, setAll, type TermsChecks } from './terms-checks'

type Failure = 'signup' | 'login' | 'region' | null

/**
 * S02-3 가입 동의 (3 / 4). 여기서 회원 가입 요청을 보내고, 이메일 가입이면 로그인한 뒤 내 동네를 저장하고
 * 증상 보고 동의로 간다. 가입 응답에는 토큰이 없어서다(backend/docs/modules.md "화면 계약").
 *
 * - 필수 둘을 켜야 "동의하고 가입하기" 가 켜진다(incomplete 시안 — `aria-disabled`, 누르면 아무 일도 없다)
 * - 가입 종류는 Provider 초안의 `method` 로 가린다: 이메일 가입(이메일 · 비밀번호 · 닉네임 + 동의) →
 *   `loginWithEmail` → 동네 저장, 카카오 가입(동의만, 이미 로그인 상태) → 동네 저장.
 *   성인 확인(S02-2)은 `AGE_OVER_19` 동의로 함께 보낸다
 * - 단계마다 끝난 것을 `membership` 에 남겨, 로그인 · 동네 저장이 실패한 뒤 다시 누르면 남은 단계만 보낸다(가입 두 번 금지).
 *   비밀번호는 로그인까지 마친 뒤에 지운다 — 로그인을 다시 시도할 때 필요하다
 * - 가입이 인증 만료(`verification-expired`, `AUTH_007`)로 돌아오면 인증 · 보낸 시각 · 비밀번호를 지우고
 *   (이메일 · 닉네임은 남긴다) 이메일 단계로 기록을 바꿔 간다. 이메일 화면이 안내를 띄운다
 * - 증상 보고 동의로는 기록을 바꿔 간다 — 뒤로 가기로 이 화면에 돌아와 다시 가입하지 않게 한다
 * - 값이 없으면(바로 들어옴 · 새로고침) 앞 단계로 보낸다: 동네 → 성인 확인 → 가입 종류(모르면 로그인 방법 선택) →
 *   이메일 가입의 인증 · 비밀번호(없으면 이메일 단계). 이미 가입을 마쳤으면 다음 단계로 보낸다
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
        : draft.method === null
          ? LOGIN_PATH
          : // 가입 뒤에는 인증 시각을 지우므로 가입 전에만 본다
            !membership.accountCreated &&
              draft.method === 'email' &&
              (draft.verifiedAt === null || draft.password === '')
            ? SIGNUP_EMAIL_PATH
            : null
  useEffect(() => {
    // 가입을 마친 직후 · 인증 만료의 이동은 submit 이 한다. 여기서는 처음부터 갈 곳이 정해진 경우만 보낸다
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
    if (draft.method === 'kakao') return { kind: 'kakao', consents }
    return {
      kind: 'email',
      email: draft.email,
      password: draft.password,
      nickname: draft.nickname,
      consents,
    }
  }

  function fail(step: Exclude<Failure, null>) {
    if (!active.current) return
    setFailure(step)
    setPending(false)
  }

  /**
   * 서버에서 끝난 단계는 화면을 떠났어도 Provider 에 먼저 남긴다 — 계정이 생겼는데 기록이 없으면 다음 제출이
   * 가입을 두 번 보낸다. 화면 상태(안내 · 보내는 중 · 이동)만 화면이 떠 있을 때(`active`) 바꾼다.
   */
  async function submit() {
    if (blocked || !district) return
    setPending(true)
    setFailure(null)
    // 이 화면이 그려질 때의 진행이다. 이번 누름에서 끝낸 단계는 아래에서 함께 고친다
    let { loggedIn } = membership

    if (!membership.accountCreated) {
      let result: SignupResult
      try {
        result = await signup(buildRequest())
      } catch {
        fail('signup')
        return
      }
      if (result.status === 'verification-expired') {
        // 이메일 인증부터 다시 한다. 다시 쓰지 않아도 되는 이메일 · 닉네임만 남긴다.
        // 이동하는 동안은 보내는 중으로 둔다 — 풀면 값이 없다고 안내 없는 이메일 단계로 한 번 더 보낸다
        updateSignup({ verifiedAt: null, codeSentAt: null, password: '' })
        if (active.current) replace(SIGNUP_EMAIL_VERIFICATION_EXPIRED_PATH)
        return
      }
      // 서버도 가입에 쓴 인증 표시를 지운다. 카카오 가입은 이미 로그인한 상태다
      loggedIn = draft.method === 'kakao'
      updateSignup({ verifiedAt: null })
      updateMembership({ accountCreated: true, loggedIn })
      if (!active.current) return
    }

    if (!loggedIn && draft.method === 'email') {
      // 맞지 않음 · 잠김 · 응답 없음 모두 같은 안내다. 계정은 이미 있으니 다시 누르면 로그인부터 한다
      const ok = await loginWithEmail(draft.email, draft.password).then(
        (result) => result.status === 'ok',
        () => false,
      )
      if (ok) {
        // 비밀번호는 로그인까지 마쳐야 버린다
        updateSignup({ password: '' })
        updateMembership({ loggedIn: true })
      }
      if (!active.current) return
      if (!ok) {
        fail('login')
        return
      }
    }

    try {
      await saveRegion(district.code)
    } catch {
      fail('region')
      return
    }
    updateMembership({ regionSaved: true })
    if (active.current) replace(SETUP_HEALTH_CONSENT_PATH)
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
        {failure === 'login' && (
          <AlertBox tone="danger">
            가입은 됐지만 로그인하지 못했어요. 잠시 뒤 다시 눌러 주세요.
          </AlertBox>
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
