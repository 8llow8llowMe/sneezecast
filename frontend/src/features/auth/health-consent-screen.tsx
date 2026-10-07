'use client'

import { useEffect, useState } from 'react'

import { ToastRegion, useToast } from '@/components/toast'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { SETUP_TERMS_PATH } from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import { agreeHealthConsent } from './auth-client'
import { LEGAL_TEXT_NOT_READY } from './consent-row'
import {
  HealthConsentActions,
  HealthConsentAlert,
  HealthConsentCheck,
  HealthConsentNotice,
  type HealthConsentProblem,
} from './health-consent'
import { consentFor } from './legal'
import { afterLoginHref } from './login-return'
import { takeLoginReturn } from './login-return-store'

/**
 * 가입을 마친 뒤 갈 곳(#140). 로그인 화면 · 로그인 안내 시트에서 가입으로 떠날 때 둔 돌아갈 곳(`login-return-store.ts`)을 읽고 지운다.
 * 보고하려던 가입이면 같은 동네 홈의 보고 진입(`/?region=…&report=start`)이다 — `reportEntry` 가 참일 때만(동의했고 회원 상태에 반영됨 —
 * `agreeHealthConsent` 결과의 `reportWritable`). 반영되지 않았는데 붙이면 홈이 미동의로 보고 `report=start` 를 동의 시트로 바꿔, 방금
 * 동의한 사람에게 동의를 다시 묻는다. 실데이터에서 동의 뒤 재발급이 일시 장애였을 때 그렇고, 그때는 보고 진입 없이 같은 동네 홈으로만
 * 간다(파기 대기는 이동하지 않고 알린다 — 아래). `나중에 할게요` 는 보고 진입을 붙이지 않는다 — 붙이면 미동의 회원이라 홈이 방금 미룬
 * 동의 시트를 다시 연다. 없으면 홈이다
 */
function afterSignupHref({ reportEntry }: { reportEntry: boolean }): string {
  const loginReturn = takeLoginReturn()
  return afterLoginHref(reportEntry ? loginReturn : { ...loginReturn, intent: null })
}

/**
 * S02-4 증상 보고 동의 (4 / 4). 가입을 마친 뒤 건강정보 동의를 따로 받는다.
 *
 * - "동의하고 시작하기" 는 체크해야 켜지고, 동의를 보낸 뒤 홈(로그인 뒤 돌아갈 곳이 있으면 그곳, `afterSignupHref`)으로 기록을 바꿔 간다.
 *   보내지 못하면 빨강 상자로 알린다 — 동의서 버전이 낡았으면(`outdated`) 다시 눌러도 같아 새로고침을 안내한다(`HealthConsentAlert`).
 *   동의는 됐는데 앞선 철회의 보고 파기가 끝나지 않았으면(`purgePending`) 이동하지 않고 지우는 중이라고 알린다 — 가입 마무리의 새 회원에게는
 *   철회 이력이 없어 실제로는 생기지 않지만, 생기면 홈의 동의 시트와 같게 알린다
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
  const [failed, setFailed] = useState<Exclude<HealthConsentProblem, 'not-reflected'> | null>(null)

  const registered = membership.regionSaved
  useEffect(() => {
    if (!registered) replace(SETUP_TERMS_PATH)
  }, [registered, replace])

  if (!registered) return null

  async function agree() {
    if (pending) return
    setPending(true)
    setFailed(null)
    let problem: Exclude<HealthConsentProblem, 'not-reflected'> = 'failed'
    try {
      const result = await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'), source)
      if (!active.current) return
      if (result.status === 'outdated') {
        problem = 'outdated'
      } else if (!result.reportWritable && result.purgePending) {
        // 지우기가 끝날 때까지 보고할 수 없다고 알리고 머문다. 나가는 길은 `나중에 할게요` 다
        problem = 'purge-pending'
      } else {
        replace(afterSignupHref({ reportEntry: result.reportWritable }))
        return
      }
    } catch {
      if (!active.current) return
    }
    setFailed(problem)
    setPending(false)
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

        {failed && <HealthConsentAlert problem={failed} />}
      </div>

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-30 z-10 mx-auto max-w-115 px-page-mobile"
      />
    </OnboardingLayout>
  )
}
