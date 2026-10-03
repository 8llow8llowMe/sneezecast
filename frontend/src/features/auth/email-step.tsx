'use client'

import { type FormEvent, type ReactNode, useId, useState } from 'react'
import { useRouter } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { TextField } from '@/components/text-field'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { useActiveRef } from '@/lib/use-active-ref'
import { useClearOnPageFreeze } from '@/lib/use-clear-on-page-freeze'

import type { SendCodeResult } from './auth-client'
import { isEmailFormat } from './signup-rules'

type Status = 'idle' | 'submitting' | 'invalid' | 'limit' | 'failed'

export type EmailStepProps = {
  /** 화면 제목. 가입(S13-2)과 비밀번호 재설정(S13-6)이 다르다 */
  title: ReactNode
  /** 앞서 쓴 이메일. "이메일 바꾸기" 로 돌아오면 그대로 있다 */
  initialEmail: string
  /** 인증 시간이 지나 돌아왔다는 안내를 보일지. 부르는 쪽이 쿼리와 초안(코드를 새로 보냈는지)으로 정한다 */
  showVerificationExpired: boolean
  sendCode: (email: string) => Promise<SendCodeResult>
  /** 코드를 보냈을 때 그 흐름의 초안을 고친다. 이어서 `nextPath` 로 간다 */
  onSent: (email: string, sentAt: number) => void
  /**
   * 뒤로 (앞 단계). 앞 화면이 여럿이면 후보를 모두 준다 — 바로 앞이 그중 하나면 기록을 되돌리고, 아니면 첫 후보로 바꿔 간다
   * (`useOnboarding().goBack`)
   */
  backPath: string | readonly [string, ...string[]]
  /** 코드 단계 */
  nextPath: string
}

/**
 * 인증 코드를 받을 이메일 입력. 이메일 가입(S13-2)과 비밀번호 재설정(S13-6) 이메일 단계가 같이 쓴다 — 시안 안내대로
 * 재설정은 Signup-email 을 제목만 바꿔 쓴다(docs/design/auth/README.md). 단계 표시는 없다.
 *
 * 흐름마다 다른 것(제목 · 보낼 함수 · 초안 · 앞뒤 경로)만 prop 으로 받고, 상태와 문구는 이 컴포넌트가 정한다.
 *
 * | 상태 | 보이는 것 |
 * | --- | --- |
 * | 기본 | 이메일 칸 · "가입과 로그인에만 써요." · 인증 코드 받기 |
 * | invalid | 칸 아래 "이메일 형식을 확인해 주세요." — 보낼 때 판단한다(입력 중에는 띄우지 않는다) |
 * | limit | 회색 상자 "코드 요청이 많아 잠시 막혔어요." — 남은 시간은 서버가 주지 않아 못 박지 않는다 |
 * | verification-expired | 회색 상자 "인증 시간이 지났어요." (코드를 새로 보내기 전까지) |
 *
 * "이미 가입된 이메일" 상태는 두지 않는다 — 코드 받기는 가입 여부와 무관하게 같은 응답이라(계정 열거 방지,
 * backend/docs/modules.md "화면 계약") 늘 코드 단계로 간다. 코드 화면이 중립 문구로 알린다.
 *
 * 오류 · 막힘이면 버튼이 꺼지고 칸을 고치면 다시 켜진다. 버튼은 `aria-disabled` 로 꺼서 포커스를 지킨다.
 * 응답을 기다리는 동안 화면을 떠나면 늦은 응답은 버린다.
 *
 * 시안: docs/design/auth/screens/ 의 Signup-email (+ -T · -D)
 */
export function EmailStep({
  title,
  initialEmail,
  showVerificationExpired,
  sendCode,
  onSent,
  backPath,
  nextPath,
}: EmailStepProps) {
  const router = useRouter()
  const { goBack } = useOnboarding()
  const active = useActiveRef()
  const formId = useId()
  const [email, setEmail] = useState(initialEmail)
  const [status, setStatus] = useState<Status>('idle')
  // 뒤로 가기 캐시에 들어가기 전에 입력한 이메일을 비운다(#186)
  useClearOnPageFreeze(() => {
    setEmail('')
    setStatus('idle')
  })
  const submitting = status === 'submitting'
  const blocked = email.trim() === '' || submitting || status === 'invalid' || status === 'limit'

  function change(value: string) {
    setEmail(value)
    if (!submitting) setStatus('idle')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (blocked) return
    const value = email.trim()
    if (!isEmailFormat(value)) {
      setStatus('invalid')
      return
    }
    setStatus('submitting')
    try {
      const result = await sendCode(value)
      // 기다리는 동안 화면을 떠났으면 늦은 응답을 버린다
      if (!active.current) return
      if (result.status === 'sent') {
        onSent(value, Date.now())
        router.push(nextPath)
        return
      }
      setStatus(result.status)
    } catch {
      if (active.current) setStatus('failed')
    }
  }

  const error = status === 'invalid' ? '이메일 형식을 확인해 주세요.' : undefined

  return (
    <OnboardingLayout
      onBack={() => goBack(backPath)}
      panelTitle={
        <>
          이메일은 가입과
          <br />
          로그인에만 써요
        </>
      }
      footer={
        <Button type="submit" form={formId} fullWidth aria-disabled={blocked || undefined}>
          인증 코드 받기
        </Button>
      }
    >
      <form
        id={formId}
        onSubmit={(event) => void submit(event)}
        noValidate
        className="flex flex-col gap-5"
      >
        <div>
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">{title}</h1>
          <p className="mt-2 text-body leading-[1.6] text-fg-sub">인증 코드를 보내 드릴게요.</p>
        </div>

        <TextField
          label="이메일"
          type="email"
          inputMode="email"
          autoComplete="email"
          value={email}
          readOnly={submitting}
          onChange={(event) => change(event.target.value)}
          hint={status === 'limit' ? undefined : '가입과 로그인에만 써요.'}
          error={error}
        />

        {showVerificationExpired && (
          // 이 화면에 들어오자마자 보이는 상자다. 처음부터 있던 status 영역은 읽히지 않을 수 있어 alert 로 둔다
          <AlertBox tone="neutral" role="alert">
            인증 시간이 지났어요. 이메일 인증부터 다시 해 주세요.
          </AlertBox>
        )}

        {status === 'limit' && (
          // 새로 나타나는 상자라 꼭 읽히게 alert 로 둔다
          <AlertBox tone="neutral" role="alert">
            코드 요청이 많아 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.
          </AlertBox>
        )}
        {status === 'failed' && (
          <AlertBox tone="danger">코드를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
      </form>
    </OnboardingLayout>
  )
}
