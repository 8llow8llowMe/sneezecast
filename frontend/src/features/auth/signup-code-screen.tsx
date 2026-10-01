'use client'

import { type FormEvent, useEffect, useId, useState } from 'react'
import { useRouter } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { TextField } from '@/components/text-field'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { SIGNUP_ACCOUNT_PATH, SIGNUP_EMAIL_PATH } from '@/features/onboarding/paths'
import { formatMinSec, useSecondsLeft } from '@/lib/countdown'
import { useActiveRef } from '@/lib/use-active-ref'

import {
  CODE_TTL_SECONDS,
  RESEND_COOLDOWN_SECONDS,
  sendEmailCode,
  verifyEmailCode,
} from './auth-client'

type Status =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'resending' }
  | { kind: 'wrong'; remainingAttempts: number }
  | { kind: 'expired' }
  | { kind: 'locked' }
  | { kind: 'limit' }
  /** 코드 확인 응답을 받지 못함 */
  | { kind: 'failed' }
  /** 다시 받기 응답을 받지 못함 */
  | { kind: 'resend-failed' }

const CODE_LENGTH = 6

/**
 * S13-3 인증 코드. 단계 표시는 없다.
 *
 * | 상태 | 보이는 것 |
 * | --- | --- |
 * | 기본 | 보낸 이메일 · 이메일 바꾸기 · 코드 칸(남은 시간) · 다시 받기(대기 60초) · 확인 |
 * | wrong | 칸 아래 "코드가 맞지 않아요. 남은 시도는 n번이에요." |
 * | expired | 남은 시간 0:00(빨강) · 빨강 상자 · 확인 꺼짐 · 다시 받기 켜짐 |
 * | locked | 칸 꺼짐 · 빨강 상자 안 "이메일 다시 입력하기" · 두 버튼 꺼짐 |
 * | 인증 마침 | 비밀번호 화면에서 뒤로 돌아온 경우. 칸 꺼짐 · 남은 시간 숨김 · 파랑 안내, 확인은 다시 묻지 않고 넘어간다 |
 *
 * 남은 시간은 코드를 보낸 시각(Provider)으로 계산해 탭이 백그라운드였거나 다음 단계에서 돌아와도 맞다.
 * 다시 받으면 코드 · 시간 · 남은 시도가 처음으로 돌아가고 앞선 인증은 무효가 된다.
 * 보낸 이메일이 없으면(주소로 바로 들어옴 · 새로고침) 이메일 입력으로 돌려보낸다.
 * 응답을 기다리는 동안 화면을 떠나면 늦은 응답은 버린다.
 *
 * 시안: docs/design/auth/screens/ 의 Signup-code (+ -T · -D)
 */
export function SignupCodeScreen() {
  const router = useRouter()
  const { signup, updateSignup, goBack, replace } = useOnboarding()
  const active = useActiveRef()
  const formId = useId()
  const [code, setCode] = useState('')
  const [status, setStatus] = useState<Status>({ kind: 'idle' })
  const sentAt = signup.codeSentAt
  const secondsLeft = useSecondsLeft(sentAt === null ? null : sentAt + CODE_TTL_SECONDS * 1000)
  const resendIn = useSecondsLeft(sentAt === null ? null : sentAt + RESEND_COOLDOWN_SECONDS * 1000)

  const ready = signup.email !== '' && sentAt !== null
  useEffect(() => {
    if (!ready) replace(SIGNUP_EMAIL_PATH)
  }, [ready, replace])

  if (!ready) return null

  const verified = signup.verificationToken !== null
  const locked = !verified && status.kind === 'locked'
  const expired = !verified && !locked && (secondsLeft === 0 || status.kind === 'expired')
  const busy = status.kind === 'submitting' || status.kind === 'resending'
  const confirmOff = busy || (!verified && (code.length !== CODE_LENGTH || expired || locked))
  const resendOff = resendIn > 0 || locked || busy

  function changeCode(value: string) {
    // 붙여넣은 "482 915" 처럼 숫자 사이 공백 · 기호가 있어도 숫자만 남겨 6자리로 자른다
    setCode(value.replace(/\D/g, '').slice(0, CODE_LENGTH))
    if (status.kind === 'wrong' || status.kind === 'failed') setStatus({ kind: 'idle' })
  }

  async function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (confirmOff) return
    // 이미 인증했으면 다시 묻지 않는다 — 목 · 서버 모두 쓴 코드는 다시 받지 않는다
    if (verified) {
      router.push(SIGNUP_ACCOUNT_PATH)
      return
    }
    setStatus({ kind: 'submitting' })
    try {
      const result = await verifyEmailCode(signup.email, code)
      if (!active.current) return
      if (result.status === 'ok') {
        updateSignup({ verificationToken: result.verificationToken })
        router.push(SIGNUP_ACCOUNT_PATH)
        return
      }
      if (result.status === 'wrong') {
        setStatus({ kind: 'wrong', remainingAttempts: result.remainingAttempts })
      } else {
        if (result.status === 'locked') setCode('')
        setStatus({ kind: result.status })
      }
    } catch {
      if (active.current) setStatus({ kind: 'failed' })
    }
  }

  async function resend() {
    if (resendOff) return
    setStatus({ kind: 'resending' })
    try {
      const result = await sendEmailCode(signup.email)
      if (!active.current) return
      if (result.status === 'sent') {
        // 새 코드를 받으면 앞선 인증은 무효다
        updateSignup({ codeSentAt: Date.now(), verificationToken: null })
        setCode('')
        setStatus({ kind: 'idle' })
      } else {
        setStatus({ kind: result.status === 'limit' ? 'limit' : 'resend-failed' })
      }
    } catch {
      if (active.current) setStatus({ kind: 'resend-failed' })
    }
  }

  const timeLabel = formatMinSec(secondsLeft)

  return (
    <OnboardingLayout
      onBack={() => goBack(SIGNUP_EMAIL_PATH)}
      panelTitle={
        <>
          코드는 5분 동안
          <br />쓸 수 있어요
        </>
      }
      footer={
        <div className="flex gap-2.5">
          <Button
            variant="secondary"
            className="flex-1"
            aria-disabled={resendOff || undefined}
            onClick={() => void resend()}
          >
            {resendIn > 0 && !locked ? `다시 받기 ${formatMinSec(resendIn)}` : '다시 받기'}
          </Button>
          <Button
            type="submit"
            form={formId}
            className="flex-1"
            aria-disabled={confirmOff || undefined}
          >
            확인
          </Button>
        </div>
      }
    >
      <form
        id={formId}
        onSubmit={(event) => void confirm(event)}
        noValidate
        className="flex flex-col gap-5"
      >
        <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
          메일로 받은 코드를
          <br />
          입력해 주세요
        </h1>

        <div className="flex items-center justify-between gap-2 pb-1">
          <span className="min-w-0 text-body break-all text-fg">
            <strong>{signup.email}</strong>으로 보냈어요
          </span>
          <button
            type="button"
            onClick={() => goBack(SIGNUP_EMAIL_PATH)}
            className="min-h-touch shrink-0 cursor-pointer px-1 text-body font-semibold text-brand"
          >
            이메일 바꾸기
          </button>
        </div>

        <TextField
          label="인증 코드 6자리"
          inputMode="numeric"
          autoComplete="one-time-code"
          value={code}
          readOnly={busy}
          disabled={locked || verified}
          onChange={(event) => changeCode(event.target.value)}
          hint={expired || locked || verified ? undefined : '5분 안에 입력해 주세요.'}
          error={
            status.kind === 'wrong' && !expired
              ? `코드가 맞지 않아요. 남은 시도는 ${status.remainingAttempts}번이에요.`
              : undefined
          }
          trailing={
            verified ? undefined : (
              <span
                role="timer"
                aria-label="남은 시간"
                className={
                  expired
                    ? 'px-3.5 text-body font-semibold text-danger'
                    : 'px-3.5 text-body font-semibold text-brand'
                }
              >
                {timeLabel}
              </span>
            )
          }
        />

        {verified && (
          <AlertBox tone="info">인증을 마쳤어요. 확인을 누르면 다음 단계로 가요.</AlertBox>
        )}
        {expired && (
          <AlertBox tone="danger">입력 시간이 지났어요. 코드를 다시 받아 주세요.</AlertBox>
        )}
        {locked && (
          <AlertBox
            tone="danger"
            action={
              // 이동만 한다. 보낸 기록은 이메일 화면에서 새로 보낼 때 덮어쓴다
              <Button variant="secondary" fullWidth onClick={() => goBack(SIGNUP_EMAIL_PATH)}>
                이메일 다시 입력하기
              </Button>
            }
          >
            시도 횟수를 넘겼어요. 이메일부터 다시 진행해 주세요.
          </AlertBox>
        )}
        {status.kind === 'limit' && (
          <AlertBox tone="neutral" role="alert">
            코드 요청이 많아 잠시 막혔어요. 10분 뒤 다시 시도해 주세요.
          </AlertBox>
        )}
        {status.kind === 'failed' && (
          <AlertBox tone="danger">코드를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
        {status.kind === 'resend-failed' && (
          <AlertBox tone="danger">코드를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
      </form>
    </OnboardingLayout>
  )
}
