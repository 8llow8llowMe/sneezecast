'use client'

import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { SIGNUP_ACCOUNT_PATH, SIGNUP_EMAIL_PATH } from '@/features/onboarding/paths'

import { sendEmailCode, verifyEmailCode } from './auth-client'
import { CodeStep } from './code-step'

/**
 * S13-3 이메일 가입 — 인증 코드. 상태 · 문구는 `CodeStep` 에 있다.
 *
 * 제목 아래 "이미 가입한 이메일이면 코드 대신 안내 메일이 가요." 는 상태와 무관하게 늘 보인다. 이미 가입된 이메일이면
 * 서버가 코드 대신 안내 메일을 보낸다(backend/docs/modules.md "가입 흐름"). 인증을 마치면 비밀번호 · 닉네임으로 간다.
 *
 * 시안: docs/design/auth/screens/ 의 Signup-code (+ -T · -D)
 */
export function SignupCodeScreen() {
  const { signup, updateSignup } = useOnboarding()

  return (
    <CodeStep
      notice="이미 가입한 이메일이면 코드 대신 안내 메일이 가요."
      email={signup.email}
      codeSentAt={signup.codeSentAt}
      verified={signup.verifiedAt !== null}
      sendCode={sendEmailCode}
      verifyCode={verifyEmailCode}
      // 서버가 인증 표시를 이메일별로 30분 든다. 화면은 마친 시각만 남긴다
      onVerified={(_, verifiedAt) => updateSignup({ verifiedAt })}
      onResent={(codeSentAt) => updateSignup({ codeSentAt, verifiedAt: null })}
      emailPath={SIGNUP_EMAIL_PATH}
      nextPath={SIGNUP_ACCOUNT_PATH}
    />
  )
}
