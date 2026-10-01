'use client'

import { type FormEvent, useEffect, useId, useState } from 'react'
import { useRouter } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { TextField } from '@/components/text-field'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { formatMinSec, useSecondsLeft } from '@/lib/countdown'
import { useActiveRef } from '@/lib/use-active-ref'

import {
  CODE_TTL_SECONDS,
  RESEND_COOLDOWN_SECONDS,
  type SendCodeResult,
  type VerifyCodeFailure,
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

function isOk<Ok extends { status: 'ok' }>(result: Ok | VerifyCodeFailure): result is Ok {
  return result.status === 'ok'
}

export type CodeStepProps<Ok extends { status: 'ok' }> = {
  /**
   * 제목 아래 늘 보이는 중립 문구. 코드 받기가 가입 여부와 무관하게 같은 응답이라(계정 열거 방지) 화면은 가입 여부를
   * 모른 채 이 단계로 온다 — 문구도 가입 여부를 드러내지 않는다
   */
  notice: string
  /** 코드를 보낸 이메일. 비었으면 이메일 단계로 돌려보낸다 */
  email: string
  /** 코드를 보낸 시각(ms). 남은 시간 · 다시 받기 대기를 이 시각으로 계산한다. 없으면 이메일 단계로 돌려보낸다 */
  codeSentAt: number | null
  /** 이 코드로 인증을 이미 마쳤는지(다음 화면에서 뒤로 돌아온 경우). 마쳤으면 확인은 다시 묻지 않고 넘어간다 */
  verified: boolean
  sendCode: (email: string) => Promise<SendCodeResult>
  /** 성공 결과는 흐름마다 다르다 — 가입은 값이 없고 재설정은 일회용 토큰을 준다 */
  verifyCode: (email: string, code: string) => Promise<Ok | VerifyCodeFailure>
  /**
   * 인증을 마쳤을 때 그 흐름의 초안에 결과를 남긴다. 서버는 이미 코드를 썼으므로 응답을 기다리는 동안 화면을 떠났어도
   * 부른다(서버에서 끝난 일은 Provider 에 먼저 남긴다). 이동은 화면이 떠 있을 때만 한다
   */
  onVerified: (result: Ok, verifiedAt: number) => void
  /** 코드를 다시 보냈을 때 보낸 시각을 고치고 앞선 인증을 지운다 */
  onResent: (sentAt: number) => void
  /** 이메일 단계. 뒤로 · 이메일 바꾸기 · 이메일 다시 입력하기 · 값이 없을 때 돌려보내는 곳 */
  emailPath: string
  /** 인증을 마친 뒤 가는 곳 */
  nextPath: string
}

/**
 * 메일로 받은 인증 코드 입력. 이메일 가입(S13-3)과 비밀번호 재설정(S13-6) 코드 단계가 같이 쓴다 — 시안 안내대로
 * 재설정은 Signup-code 를 문구만 바꿔 쓴다(docs/design/auth/README.md). 단계 표시는 없다.
 *
 * 흐름마다 다른 것(중립 문구 · 보낼 · 확인할 함수 · 인증 결과를 남기는 법 · 앞뒤 경로)만 prop 으로 받고, 상태와 그 밖 문구는
 * 이 컴포넌트가 정한다. 인증을 마쳤는지는 흐름의 초안으로 부르는 쪽이 정한다(가입은 인증 시각, 재설정은 토큰).
 *
 * | 상태 | 보이는 것 |
 * | --- | --- |
 * | 기본 | 중립 문구 · 보낸 이메일 · 이메일 바꾸기 · 코드 칸(남은 시간) · 다시 받기(대기 60초) · 확인 |
 * | wrong | 칸 아래 "코드가 맞지 않아요. 남은 시도는 n번이에요." |
 * | expired | 남은 시간 0:00(빨강) · 빨강 상자 · 확인 꺼짐 · 다시 받기 켜짐 |
 * | locked | 칸 꺼짐 · 빨강 상자 안 "이메일 다시 입력하기" · 두 버튼 꺼짐 |
 * | 인증 마침 | 다음 화면에서 뒤로 돌아온 경우. 칸 꺼짐 · 남은 시간 숨김 · 파랑 안내, 확인은 다시 묻지 않고 넘어간다 |
 *
 * 칸 도움말은 오류 · 만료 · 잠김에서 바뀌므로 중립 문구는 도움말이 아니라 설명 문단(이메일 화면의 제목 아래 문단과 같은 자리)에 둔다.
 *
 * 남은 시간은 코드를 보낸 시각(Provider)으로 계산해 탭이 백그라운드였거나 다음 단계에서 돌아와도 맞다.
 * 다시 받으면 코드 · 시간 · 남은 시도가 처음으로 돌아가고 앞선 인증은 무효가 된다.
 * 보낸 이메일이 없으면(주소로 바로 들어옴 · 새로고침) 이메일 단계로 돌려보낸다.
 * 응답을 기다리는 동안 화면을 떠나면 늦은 응답은 버린다.
 *
 * 시안: docs/design/auth/screens/ 의 Signup-code (+ -T · -D)
 */
export function CodeStep<Ok extends { status: 'ok' }>({
  notice,
  email,
  codeSentAt: sentAt,
  verified,
  sendCode,
  verifyCode,
  onVerified,
  onResent,
  emailPath,
  nextPath,
}: CodeStepProps<Ok>) {
  const router = useRouter()
  const { goBack, replace } = useOnboarding()
  const active = useActiveRef()
  const formId = useId()
  const [code, setCode] = useState('')
  const [status, setStatus] = useState<Status>({ kind: 'idle' })
  const secondsLeft = useSecondsLeft(sentAt === null ? null : sentAt + CODE_TTL_SECONDS * 1000)
  const resendIn = useSecondsLeft(sentAt === null ? null : sentAt + RESEND_COOLDOWN_SECONDS * 1000)

  const ready = email !== '' && sentAt !== null
  useEffect(() => {
    if (!ready) replace(emailPath)
  }, [ready, replace, emailPath])

  if (!ready) return null

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
      router.push(nextPath)
      return
    }
    setStatus({ kind: 'submitting' })
    try {
      const result = await verifyCode(email, code)
      if (isOk(result)) {
        // 서버는 코드를 이미 썼다. 화면을 떠났어도 결과(재설정 토큰 등)를 남긴다 — 버리면 코드를 다시 받아야 한다
        onVerified(result, Date.now())
        if (active.current) router.push(nextPath)
        return
      }
      if (!active.current) return
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
      const result = await sendCode(email)
      if (!active.current) return
      if (result.status === 'sent') {
        // 새 코드를 받으면 앞선 인증은 무효다
        onResent(Date.now())
        setCode('')
        setStatus({ kind: 'idle' })
      } else {
        setStatus({ kind: 'limit' })
      }
    } catch {
      if (active.current) setStatus({ kind: 'resend-failed' })
    }
  }

  const timeLabel = formatMinSec(secondsLeft)

  return (
    <OnboardingLayout
      onBack={() => goBack(emailPath)}
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
        <div>
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
            메일로 받은 코드를
            <br />
            입력해 주세요
          </h1>
          <p className="mt-2 text-body leading-[1.6] text-fg-sub">{notice}</p>
        </div>

        <div className="flex items-center justify-between gap-2 pb-1">
          <span className="min-w-0 text-body break-all text-fg">
            <strong>{email}</strong>으로 보냈어요
          </span>
          <button
            type="button"
            onClick={() => goBack(emailPath)}
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
              <Button variant="secondary" fullWidth onClick={() => goBack(emailPath)}>
                이메일 다시 입력하기
              </Button>
            }
          >
            시도 횟수를 넘겼어요. 이메일부터 다시 진행해 주세요.
          </AlertBox>
        )}
        {status.kind === 'limit' && (
          <AlertBox tone="neutral" role="alert">
            코드 요청이 많아 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.
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
