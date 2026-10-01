'use client'

import { useState } from 'react'

import { AlertBox } from '@/components/alert-box'
import { Modal } from '@/components/modal'
import { ToastRegion, useToast } from '@/components/toast'
import { useActiveRef } from '@/lib/use-active-ref'

import { agreeHealthConsent } from './auth-client'
import { LEGAL_TEXT_NOT_READY } from './consent-row'
import { HealthConsentActions, HealthConsentCheck, HealthConsentNotice } from './health-consent'
import { consentFor } from './legal'

/**
 * 증상 보고 동의 시트 (Consent-health-sheet, unchecked · checked). 건강정보 동의를 하지 않은 회원이 홈에서 보고를 누르면
 * 홈 위에 뜬다. 고지 표 · 체크 · 버튼은 S02-4 화면과 같은 것을 쓴다(`health-consent.tsx`).
 *
 * - "동의하고 보고하기" 는 체크해야 켜진다(`aria-disabled`). 누르면 S02-4 와 같은 동의(문서 버전 포함)를 보내고,
 *   성공하면 `onAgreed` 를 부른다 — 홈이 보고 흐름으로 바꾼다
 * - 보내지 못하면 빨강 상자(`role="alert"`)로 알리고 다시 누를 수 있다
 * - "나중에 할게요" · 닫기는 `onClose` 다. 보내는 중에는 "나중에 할게요" 가 꺼진다
 *
 * 내용은 열려 있을 때만 그린다. 응답 전에 시트가 닫히면(닫기 · 휴대폰 뒤로 가기) 늦게 온 응답으로 보고 흐름을 열지 않는다
 * (`useActiveRef`). 동의 자체는 서버에 남고 목 세션도 `auth-client` 가 바꾼다 — 화면이 사라져도 세션 상태는 남긴다.
 *
 * 시안: docs/design/auth/screens/ 의 Consent-health-sheet (+ -T · -D)
 */
export function HealthConsentSheet({
  open,
  onClose,
  onAgreed,
}: {
  open: boolean
  onClose: () => void
  onAgreed: () => void
}) {
  return (
    <Modal open={open} onClose={onClose} title="증상 보고에 동의해 주세요">
      {open && <HealthConsentSheetBody onLater={onClose} onAgreed={onAgreed} />}
    </Modal>
  )
}

function HealthConsentSheetBody({
  onLater,
  onAgreed,
}: {
  onLater: () => void
  onAgreed: () => void
}) {
  const active = useActiveRef()
  const { toast, show, dismiss } = useToast()
  const [checked, setChecked] = useState(false)
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)

  async function agree() {
    if (pending) return
    setPending(true)
    setFailed(false)
    try {
      await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
      if (!active.current) return
      // 보고 흐름으로 바뀌면 이 시트가 닫히며 내용이 사라진다. 보내는 중 상태는 그대로 둔다
      onAgreed()
    } catch {
      if (!active.current) return
      setFailed(true)
      setPending(false)
    }
  }

  return (
    <>
      <p className="text-body leading-[1.6] text-fg-sub">
        보고하려면 건강 정보 처리에 따로 동의해야 해요.
      </p>
      <HealthConsentNotice compact />
      <HealthConsentCheck
        checked={checked}
        onChange={setChecked}
        onView={() => show({ message: LEGAL_TEXT_NOT_READY })}
      />
      {failed && (
        <AlertBox tone="danger">동의를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
      )}
      <HealthConsentActions
        agreeLabel="동의하고 보고하기"
        checked={checked}
        pending={pending}
        onAgree={() => void agree()}
        onLater={() => {
          if (!pending) onLater()
        }}
      />
      {/* 시트 흐름 밖에 띄워 빈 알림 영역이 버튼 아래 간격을 늘리지 않게 한다 (S02-4 와 같은 자리) */}
      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-30 z-10 mx-auto max-w-115 px-page-mobile"
      />
    </>
  )
}
