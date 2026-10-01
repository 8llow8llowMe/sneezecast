'use client'

import { type FormEvent, useEffect, useId, useState } from 'react'
import { useRouter } from 'next/navigation'

import { Button } from '@/components/button'
import { TextField } from '@/components/text-field'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { SETUP_REGION_PATH, SIGNUP_CODE_PATH, SIGNUP_EMAIL_PATH } from '@/features/onboarding/paths'

import {
  confirmProblem,
  NICKNAME_MAX_LENGTH,
  nicknameLength,
  nicknameProblem,
  passwordProblem,
} from './signup-rules'

/**
 * S13-4 비밀번호 · 닉네임. 단계 표시는 없다.
 *
 * 오류(pw-rule · pw-mismatch · nick-long)는 "다음" 을 누를 때 처음 보인다 — 입력 중에 미리 빨갛게 하지 않는다
 * (이메일 입력과 같은 규칙). 한 번 누른 뒤에는 고칠 때마다 다시 판단해 맞으면 바로 지운다.
 * 오류가 남아 있으면 "다음" 이 꺼진다(`aria-disabled` — 포커스를 지킨다).
 * 닉네임 글자 수는 늘 보이고 10자를 넘으면 빨갛다.
 *
 * 값은 Provider 메모리에만 두고 동네 선택으로 간다. 비밀번호는 가입 요청(S02-3)과 이어지는 로그인에 쓰고 버린다.
 * 비밀번호 칸은 20자로 자르지 않는다(`maxLength` 없음) — 붙여 넣은 값이 조용히 잘리면 다른 비밀번호로 가입된다.
 * 넘으면 규칙 오류로 알린다. 도움말 · 오류 문구는 시안에 상한 · 공백 규칙을 더했다(백엔드 #56 규칙).
 * 인증 시간이 지나 이메일 단계부터 다시 오면 닉네임은 남아 있고 비밀번호는 다시 쓴다.
 * 인증을 마치지 않았으면(주소로 바로 들어옴 · 새로고침) 이메일 입력으로 돌려보낸다.
 *
 * 시안: docs/design/auth/screens/ 의 Signup-account (+ -T · -D)
 */
export function SignupAccountScreen() {
  const router = useRouter()
  const { signup, updateSignup, goBack, replace } = useOnboarding()
  const formId = useId()
  const [password, setPassword] = useState(signup.password)
  const [confirm, setConfirm] = useState(signup.password)
  const [nickname, setNickname] = useState(signup.nickname)
  const [checked, setChecked] = useState(false)

  const verified = signup.verifiedAt !== null
  useEffect(() => {
    if (!verified) replace(SIGNUP_EMAIL_PATH)
  }, [verified, replace])

  if (!verified) return null

  const problems = {
    password: passwordProblem(password),
    confirm: confirmProblem(password, confirm),
    nickname: nicknameProblem(nickname),
  }
  const hasProblem = Object.values(problems).some((problem) => problem !== null)
  const empty = password === '' || confirm === '' || nickname.trim() === ''
  const blocked = empty || (checked && hasProblem)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (blocked) return
    setChecked(true)
    if (hasProblem) return
    updateSignup({ password, nickname: nickname.trim() })
    router.push(SETUP_REGION_PATH)
  }

  return (
    <OnboardingLayout
      onBack={() => goBack(SIGNUP_CODE_PATH)}
      panelTitle={
        <>
          닉네임은 나에게만
          <br />
          보여요
        </>
      }
      footer={
        <Button type="submit" form={formId} fullWidth aria-disabled={blocked || undefined}>
          다음
        </Button>
      }
    >
      <form id={formId} onSubmit={submit} noValidate className="flex flex-col gap-5">
        <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
          비밀번호와 닉네임을
          <br />
          정해 주세요
        </h1>

        <TextField
          label="비밀번호"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          hint="8~20자 · 영문과 숫자 포함 · 띄어쓰기 없이"
          error={
            checked && problems.password
              ? '영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요.'
              : undefined
          }
        />
        <TextField
          label="비밀번호 확인"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(event) => setConfirm(event.target.value)}
          error={checked && problems.confirm ? '비밀번호가 서로 달라요.' : undefined}
        />
        <TextField
          label="닉네임"
          autoComplete="nickname"
          value={nickname}
          onChange={(event) => setNickname(event.target.value)}
          hint="내 정보에서만 보여요. 다른 사람에게는 보이지 않아요."
          error={checked && problems.nickname ? '닉네임은 2~10자로 써 주세요.' : undefined}
          counter={{ current: nicknameLength(nickname), max: NICKNAME_MAX_LENGTH }}
        />
      </form>
    </OnboardingLayout>
  )
}
