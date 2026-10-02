'use client'

import { NO_MEMBERSHIP, useOnboarding } from '@/features/onboarding/onboarding-context'
import { LOGIN_EMAIL_PATH, LOGIN_PATH, SIGNUP_CODE_PATH } from '@/features/onboarding/paths'

import { sendEmailCode } from './auth-client'
import { EmailStep } from './email-step'

/**
 * S13-2 이메일 가입 — 이메일 입력. 상태 · 문구는 `EmailStep` 에 있다.
 *
 * 코드를 보내면 가입 초안에 새 이메일 · 보낸 시각을 쓰고 앞선 인증을 지운다. 가입 종류는 이메일이다.
 * 새 가입 시도라 앞선 가입 마무리 진행도 비운다 — 남으면 S02-3 이 이 이메일의 가입을 건너뛴다.
 *
 * 시안: docs/design/auth/screens/ 의 Signup-email (+ -T · -D)
 */
export function SignupEmailScreen({
  verificationExpired = false,
}: {
  /** 가입 요청이 인증 만료(`AUTH_007`)로 돌아왔는지 (`?reason=verification-expired`) */
  verificationExpired?: boolean
}) {
  const { signup, updateSignup, updateMembership } = useOnboarding()

  return (
    <EmailStep
      title="이메일을 알려 주세요"
      initialEmail={signup.email}
      // 코드를 새로 보낸 뒤 "이메일 바꾸기" 로 돌아오면 주소에 쿼리가 남아 있어도 안내를 다시 띄우지 않는다
      showVerificationExpired={verificationExpired && signup.codeSentAt === null}
      sendCode={sendEmailCode}
      onSent={(email, sentAt) => {
        updateSignup({ method: 'email', email, codeSentAt: sentAt, verifiedAt: null })
        updateMembership(NO_MEMBERSHIP)
      }}
      // 로그인 방법 고르기 · 이메일 로그인 둘 다 이 화면으로 온다. 앱 안에서 왔으면 그 화면(돌아갈 곳 쿼리 포함)으로 되돌리고,
      // 주소로 바로 들어왔으면 로그인 방법 고르기로 바꿔 간다
      backPath={[LOGIN_PATH, LOGIN_EMAIL_PATH]}
      nextPath={SIGNUP_CODE_PATH}
    />
  )
}
