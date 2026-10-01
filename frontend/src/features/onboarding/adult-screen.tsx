'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'

import { Button } from '@/components/button'
import { Checkbox } from '@/components/checkbox'

import { useOnboarding } from './onboarding-context'
import { OnboardingLayout } from './onboarding-layout'
import { SETUP_CONSENT_PATH, SETUP_REGION_PATH } from './paths'

/**
 * S02-2 성인 확인. 성인 본인 보고만 받는다는 것을 확인받는다 — 아이 대리 보고는 이번 범위가 아니다.
 *
 * 고른 동네가 없으면(주소로 바로 들어옴 · 새로고침) 동네 선택으로 돌려보낸다. 돌려보내는 동안은 그리지 않는다.
 *
 * 시안(정본): docs/design/auth/screens/ 의 Setup-2 · Setup-2-T · Setup-2-D
 */
export function AdultScreen() {
  const router = useRouter()
  const { district, adultConfirmed, setAdultConfirmed, goBack, replace } = useOnboarding()

  useEffect(() => {
    if (!district) replace(SETUP_REGION_PATH)
  }, [district, replace])

  if (!district) return null

  return (
    <OnboardingLayout
      step={2}
      onBack={() => goBack(SETUP_REGION_PATH)}
      panelTitle={
        <>
          지금은 성인 본인의
          <br />
          보고만 받아요
        </>
      }
      footer={
        <Button
          fullWidth
          disabled={!adultConfirmed}
          onClick={() => router.push(SETUP_CONSENT_PATH)}
        >
          다음
        </Button>
      }
    >
      <div className="flex flex-col gap-5">
        <div>
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
            성인 본인만
            <br />
            보고할 수 있어요
          </h1>
          <p className="mt-2 text-body leading-[1.6] text-fg-sub">
            지금은 성인(만 19세 이상) 본인의 건강 상태만 받아요. 아이 대신 보고하는 기능은 보호자
            동의 절차를 갖춘 뒤 열 예정이에요.
          </p>
        </div>

        <div className="rounded-button border-selected border-brand px-4.5 py-2">
          <Checkbox
            checked={adultConfirmed}
            onChange={(event) => setAdultConfirmed(event.target.checked)}
            label="성인 본인의 건강 상태만 보고할게요"
          />
        </div>
      </div>
    </OnboardingLayout>
  )
}
