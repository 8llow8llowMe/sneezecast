'use client'

import { type FormEvent, useId, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { TextField } from '@/components/text-field'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { LOGIN_EMAIL_PATH, LOGIN_PATH, SIGNUP_CODE_PATH } from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'

import { sendEmailCode } from './auth-client'
import { isEmailFormat } from './signup-rules'

type Status = 'idle' | 'submitting' | 'invalid' | 'exists' | 'limit' | 'failed'

/**
 * S13-2 이메일 입력. 단계 표시는 없다.
 *
 * | 상태 | 보이는 것 |
 * | --- | --- |
 * | 기본 | 이메일 칸 · "가입과 로그인에만 써요." · 인증 코드 받기 |
 * | invalid | 칸 아래 "이메일 형식을 확인해 주세요." — 보낼 때 판단한다(입력 중에는 띄우지 않는다) |
 * | exists | 칸 아래 "이미 가입된 이메일이에요." + 이메일로 로그인 링크 |
 * | limit | 회색 상자 "코드 요청이 많아 잠시 막혔어요." |
 *
 * 오류 · 막힘이면 버튼이 꺼지고 칸을 고치면 다시 켜진다. 버튼은 `aria-disabled` 로 꺼서 포커스를 지킨다.
 * "이메일 바꾸기" 로 돌아오면 앞서 쓴 이메일이 그대로 있다.
 *
 * 시안: docs/design/auth/screens/ 의 Signup-email (+ -T · -D)
 */
export function SignupEmailScreen() {
  const router = useRouter()
  const { signup, updateSignup, goBack } = useOnboarding()
  const active = useActiveRef()
  const formId = useId()
  const [email, setEmail] = useState(signup.email)
  const [status, setStatus] = useState<Status>('idle')
  const submitting = status === 'submitting'
  const blocked =
    email.trim() === '' ||
    submitting ||
    status === 'invalid' ||
    status === 'exists' ||
    status === 'limit'

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
      const result = await sendEmailCode(value)
      // 기다리는 동안 화면을 떠났으면 늦은 응답을 버린다
      if (!active.current) return
      if (result.status === 'sent') {
        // 새 이메일 · 새 코드다. 앞선 인증은 무효가 된다
        updateSignup({ email: value, codeSentAt: Date.now(), verificationToken: null })
        router.push(SIGNUP_CODE_PATH)
        return
      }
      setStatus(result.status)
    } catch {
      if (active.current) setStatus('failed')
    }
  }

  const error =
    status === 'invalid'
      ? '이메일 형식을 확인해 주세요.'
      : status === 'exists'
        ? '이미 가입된 이메일이에요.'
        : undefined

  return (
    <OnboardingLayout
      onBack={() => goBack(LOGIN_PATH)}
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
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">이메일을 알려 주세요</h1>
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
          messageAction={
            status === 'exists' ? <Link href={LOGIN_EMAIL_PATH}>이메일로 로그인</Link> : undefined
          }
        />

        {status === 'limit' && (
          // 새로 나타나는 상자라 꼭 읽히게 alert 로 둔다
          <AlertBox tone="neutral" role="alert">
            코드 요청이 많아 잠시 막혔어요. 10분 뒤 다시 시도해 주세요.
          </AlertBox>
        )}
        {status === 'failed' && (
          <AlertBox tone="danger">코드를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
      </form>
    </OnboardingLayout>
  )
}
