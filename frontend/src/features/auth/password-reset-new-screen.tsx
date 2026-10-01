'use client'

import { type FormEvent, useEffect, useId, useState } from 'react'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { TextField } from '@/components/text-field'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import {
  LOGIN_EMAIL_RESET_DONE_PATH,
  PASSWORD_RESET_CODE_PATH,
  PASSWORD_RESET_PATH,
  PASSWORD_RESET_VERIFICATION_EXPIRED_PATH,
} from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'

import { type PasswordResetResult, resetPassword } from './auth-client'
import { confirmProblem, passwordProblem } from './signup-rules'

/** `failed` 는 응답을 받지 못한 경우다(네트워크 · 서버 오류) */
type Status = 'idle' | 'submitting' | 'failed'

/**
 * S13-6 비밀번호 재설정 — 새 비밀번호. 단계 표시는 없다.
 *
 * | 상태 | 보이는 것 |
 * | --- | --- |
 * | default | 새 비밀번호(도움말) · 새 비밀번호 확인 · 비밀번호 바꾸기 |
 * | rule | 새 비밀번호 칸 아래 규칙 오류 · 버튼 꺼짐 |
 * | mismatch | 확인 칸 아래 "비밀번호가 서로 달라요." · 버튼 꺼짐 |
 * | failed | 빨강 상자 "비밀번호를 바꾸지 못했어요." — 다시 누를 수 있다 |
 *
 * 규칙 · 문구는 가입(S13-4)과 같다(`signup-rules` — 8~20자 · 영문과 숫자 함께 · 공백 없이, 백엔드 #56 규칙).
 * 오류는 "비밀번호 바꾸기" 를 누를 때 처음 보이고, 그 뒤에는 고칠 때마다 다시 판단해 맞으면 바로 지운다.
 * 칸은 20자로 자르지 않는다(붙여 넣은 값이 조용히 잘리면 다른 비밀번호로 바뀐다).
 *
 * 새 비밀번호는 이 화면 상태에만 두고 Provider · 로그 · 저장소 · 주소에 남기지 않는다.
 * 보내는 중에는 칸을 읽기 전용으로 두고 버튼을 `aria-disabled` 로 꺼 두 번 보내지 않는다.
 *
 * - 바꾸면 재설정 초안을 비우고(이메일만 남긴다) 이메일 로그인(`?reason=reset-done`)으로 기록을 바꿔 간다.
 *   그 화면이 토스트를 띄우고 남긴 이메일로 칸을 채운다
 * - 인증 만료(`verification-expired` — 토큰이 없음 · 15분 지남 · 이미 씀)면 보낸 시각 · 토큰을 지우고
 *   이메일 단계(`?reason=verification-expired`)로 기록을 바꿔 간다
 * - 서버에서 끝난 일(바뀜 · 인증 만료)은 화면을 떠났어도 Provider 에 먼저 남긴다. 이동 · 안내만 화면이 떠 있을 때 한다
 * - 보내는 중에는 머리줄 뒤로도 꺼 둔다(`aria-disabled`) — 응답 전에 코드 단계로 가면 결과 안내를 놓친다
 * - 재설정 토큰이 없으면(주소로 바로 들어옴 · 새로고침 · 이메일 로그인으로 돌아갔다 앞으로 가기) 이메일 단계로 돌려보낸다.
 *   가입 초안의 인증은 보지 않는다
 *
 * 시안: docs/design/auth/screens/ 의 Password-reset (default · mismatch · rule, + -T · -D)
 */
export function PasswordResetNewScreen() {
  const { passwordReset, updatePasswordReset, clearPasswordReset, goBack, replace } =
    useOnboarding()
  const active = useActiveRef()
  const formId = useId()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [checked, setChecked] = useState(false)
  const [status, setStatus] = useState<Status>('idle')
  const submitting = status === 'submitting'

  const token = passwordReset.resetToken
  const verified = token !== null
  useEffect(() => {
    // 바꾼 직후 · 인증 만료의 이동은 submit 이 한다. 그동안은 보내는 중으로 두어 여기서 한 번 더 보내지 않는다
    if (!verified && !submitting) replace(PASSWORD_RESET_PATH)
  }, [verified, submitting, replace])

  if (!verified && !submitting) return null

  const problems = {
    password: passwordProblem(password),
    confirm: confirmProblem(password, confirm),
  }
  const hasProblem = problems.password !== null || problems.confirm !== null
  const empty = password === '' || confirm === ''
  const blocked = empty || submitting || (checked && hasProblem)

  function change(set: (value: string) => void, value: string) {
    set(value)
    if (status === 'failed') setStatus('idle')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (blocked || token === null) return
    setChecked(true)
    if (hasProblem) return
    setStatus('submitting')
    let result: PasswordResetResult
    try {
      result = await resetPassword(token, password)
    } catch {
      if (active.current) setStatus('failed')
      return
    }
    if (result.status === 'ok') {
      // 서버는 이미 바꾸고 토큰을 썼다. 화면을 떠났어도 초안을 비워 이 토큰으로 다시 오지 않게 한다
      clearPasswordReset({ keepEmail: true })
      if (active.current) replace(LOGIN_EMAIL_RESET_DONE_PATH)
      return
    }
    // 토큰이 없거나 지났거나 이미 썼다. 이메일 인증부터 다시 한다
    updatePasswordReset({ codeSentAt: null, resetToken: null })
    if (active.current) replace(PASSWORD_RESET_VERIFICATION_EXPIRED_PATH)
  }

  return (
    <OnboardingLayout
      onBack={() => {
        if (!submitting) goBack(PASSWORD_RESET_CODE_PATH)
      }}
      backDisabled={submitting}
      panelTitle={
        <>
          새 비밀번호로
          <br />
          다시 시작해요
        </>
      }
      footer={
        <Button type="submit" form={formId} fullWidth aria-disabled={blocked || undefined}>
          비밀번호 바꾸기
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
          <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
            새 비밀번호를 정해 주세요
          </h1>
          <p className="mt-2 text-body leading-[1.6] text-fg-sub">이메일 인증을 마쳤어요.</p>
        </div>

        <TextField
          label="새 비밀번호"
          type="password"
          autoComplete="new-password"
          value={password}
          readOnly={submitting}
          onChange={(event) => change(setPassword, event.target.value)}
          hint="8~20자 · 영문과 숫자 포함 · 띄어쓰기 없이"
          error={
            checked && problems.password
              ? '영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요.'
              : undefined
          }
        />
        <TextField
          label="새 비밀번호 확인"
          type="password"
          autoComplete="new-password"
          value={confirm}
          readOnly={submitting}
          onChange={(event) => change(setConfirm, event.target.value)}
          error={checked && problems.confirm ? '비밀번호가 서로 달라요.' : undefined}
        />

        {status === 'failed' && (
          <AlertBox tone="danger">비밀번호를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
      </form>
    </OnboardingLayout>
  )
}
