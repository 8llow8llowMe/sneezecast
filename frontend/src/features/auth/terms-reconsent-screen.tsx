'use client'

import { useEffect, useState } from 'react'
import { useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { Checkbox } from '@/components/checkbox'
import { ToastRegion, useToast } from '@/components/toast'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { HOME_PATH } from '@/features/onboarding/paths'
import { formatMonthDay } from '@/lib/format'
import { navHref } from '@/lib/nav'
import { isSessionExpiring } from '@/lib/session-expiry'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import { agreeTermsReconsent, logout } from './auth-client'
import { LEGAL_TEXT_NOT_READY } from './consent-row'
import { consentFor, TERMS_REVISION } from './legal'
import { carriedParams, NEXT_PARAM, safeNextPath, stepTarget, targetAfter } from './required-steps'
import { useAuthSettled } from './use-auth'
import { useMemberRequirements } from './use-member-requirements'
import { useMockAuth } from './use-mock-auth'

/**
 * S02-3 약관 재동의 (`/terms/reconsent`, Setup-3-reconsent). 필수 약관(서비스 이용약관)이 개정되면 홈 · 내 정보가 먼저 보낸다
 * (`features/me/member-gate.ts`). 동네 다시 고르기보다 먼저다 — 법적 동의가 먼저다.
 *
 * - 시행일 · 바뀐 내용은 `legal.ts` 의 `TERMS_REVISION`(지금은 시안 예시 문구)이다. 단계 표시는 없다
 * - `전문 보기` 는 약관 본문이 아직 없어 "약관 본문을 준비하고 있어요" 알림을 띄운다(가입 동의와 같다)
 * - 체크해야 `동의하고 계속하기` 가 켜진다(`aria-disabled`, 포커스는 남는다). 지금 이용약관 버전으로 동의를 보낸다
 * - 성공하면 목 프로필이 먼저 바뀌고(화면과 무관), 화면이 떠 있으면 남은 조건(동네 다시 고르기)이나 `?next=` 로 기록을 바꿔 간다.
 *   실패하면 빨강 상자로 알리고 다시 누를 수 있다
 * - 뒤로 버튼은 없다(시안에도 없음). 나가는 길은 아래 보조 버튼 `동의하지 않고 로그아웃` 하나다 — **시안에 없는 기본안이고 기획 확인이
 *   필요하다**(docs/design/SCREENS.md). 로그아웃(`logout`)에 성공하면 목 세션이 먼저 비회원이 되고(화면과 무관), 화면이 떠 있으면
 *   동네(`region`)만 남긴 홈으로 기록을 바꿔 간다. 목 덮어쓰기는 버린다 — 남기면 비회원 홈이 다시 회원으로 보인다
 * - 두 동작은 한 번에 하나만 보낸다(보내는 중이면 두 버튼 모두 `aria-disabled`). 실패는 각자의 빨강 상자로 알린다
 * - 회원 상태가 정해진 뒤(`useAuthSettled`) 판단한다: 비회원이거나 재동의 조건이 없으면 남은 조건 화면이나 `?next=` 로 보낸다
 *
 * 시안(정본): docs/design/auth/screens/ 의 Setup-3-reconsent (+ -T · -D)
 */
export function TermsReconsentScreen() {
  const searchParams = useSearchParams()
  // 회원 상태가 정해진 뒤(하이드레이션 · 실데이터 복원을 마침)에만 판단한다 — 복원 중의 비회원으로 내보내지 않는다
  const settled = useAuthSettled()
  const auth = useMockAuth()
  const { steps } = useMemberRequirements()
  const { replace } = useOnboarding()
  const active = useActiveRef()
  const source = useDataSource()
  const { toast, show, dismiss } = useToast()
  const [checked, setChecked] = useState(false)
  // 보내는 중인 동작 · 실패한 동작. 이동할 때까지 보내는 중으로 둔다(조건 · 세션이 먼저 바뀌어도 여기서 한 번 더 보내지 않게)
  const [pending, setPending] = useState<'agree' | 'logout' | null>(null)
  const [failed, setFailed] = useState<'agree' | 'logout' | null>(null)

  const showing = settled && steps[0] === 'terms'
  const redirect =
    settled && !showing
      ? stepTarget(steps, safeNextPath(searchParams.get(NEXT_PARAM)), carriedParams(searchParams))
      : null
  useEffect(() => {
    // 동의 · 로그아웃을 마친 뒤의 이동은 그 함수가 한다(조건이 먼저 사라져도 여기서 한 번 더 보내지 않는다)
    // 로그인 만료로 비회원이 됐으면 만료 이동(session-expiry-watcher)에 맡긴다 — 여기서 홈으로 덮어쓰지 않는다
    if (redirect && pending === null && !isSessionExpiring()) replace(redirect)
  }, [redirect, pending, replace])

  // 보내는 중에는 조건이 먼저 사라져도 이동할 때까지 그대로 그린다
  if (!showing && pending === null) return null

  const blocked = !checked || pending !== null
  const effectiveDate = formatMonthDay(TERMS_REVISION.effectiveDate)

  async function agree() {
    if (blocked) return
    setPending('agree')
    setFailed(null)
    try {
      await agreeTermsReconsent(consentFor('TERMS_OF_SERVICE'))
    } catch {
      if (active.current) {
        setFailed('agree')
        setPending(null)
      }
      return
    }
    // 목 프로필(연동 때는 서버)에는 이미 동의가 남았다. 이동만 화면이 떠 있을 때 한다
    if (!active.current) return
    replace(targetAfter('terms', auth, searchParams))
  }

  async function leave() {
    if (pending !== null) return
    setPending('logout')
    setFailed(null)
    try {
      await logout(source)
    } catch {
      if (active.current) {
        setFailed('logout')
        setPending(null)
      }
      return
    }
    // 목 세션(연동 때는 서버)은 이미 비회원이다. 이동만 화면이 떠 있을 때 한다
    if (!active.current) return
    const region = searchParams.get('region')
    replace(navHref(HOME_PATH, region ? new URLSearchParams({ region }).toString() : undefined))
  }

  return (
    <OnboardingLayout
      panelTitle={
        <>
          바뀐 내용만
          <br />
          다시 여쭤볼게요
        </>
      }
      footer={
        <>
          <Button fullWidth aria-disabled={blocked || undefined} onClick={() => void agree()}>
            동의하고 계속하기
          </Button>
          <Button
            variant="subtle"
            aria-disabled={pending !== null || undefined}
            onClick={() => void leave()}
          >
            동의하지 않고 로그아웃
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-5">
        <div>
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
            바뀐 약관을 확인해 주세요
          </h1>
          <p className="mt-2 text-body leading-[1.6] text-fg-sub">
            {effectiveDate ? `${effectiveDate}부터 아래 내용이 바뀌어요.` : '아래 내용이 바뀌어요.'}
          </p>
        </div>

        <section
          aria-labelledby="reconsent-terms-title"
          className="flex flex-col gap-1.5 rounded-button bg-section px-4.5 py-4"
        >
          <div className="flex items-center justify-between gap-2">
            <h2 id="reconsent-terms-title" className="text-body font-bold text-fg">
              <span className="text-brand">[필수]</span> 서비스 이용약관
            </h2>
            {/* 시안은 `#terms` 링크다. 본문이 아직 없고 같은 문서 # 링크는 쓰지 않아(docs/conventions.md) 버튼으로 둔다 */}
            <button
              type="button"
              aria-label="서비스 이용약관 전문 보기"
              onClick={() => show({ message: LEGAL_TEXT_NOT_READY })}
              className="-my-3 flex min-h-touch shrink-0 cursor-pointer items-center text-sub font-semibold text-fg-sub"
            >
              전문 보기
            </button>
          </div>
          <ul className="flex flex-col gap-1.5">
            {TERMS_REVISION.changes.map((change) => (
              <li key={change} className="text-body-strong leading-[1.55] text-fg">
                {change}
              </li>
            ))}
          </ul>
        </section>

        <Checkbox
          size="md"
          checked={checked}
          onChange={(event) => {
            // 보내는 중에는 바꾸지 않는다(보낸 동의와 화면이 어긋나지 않게)
            if (pending === null) setChecked(event.target.checked)
          }}
          label="바뀐 서비스 이용약관에 동의해요"
        />

        {failed === 'agree' && (
          <AlertBox tone="danger">
            약관 동의를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.
          </AlertBox>
        )}
        {failed === 'logout' && (
          <AlertBox tone="danger">로그아웃하지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
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
