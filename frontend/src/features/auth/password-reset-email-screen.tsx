'use client'

import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { LOGIN_EMAIL_PATH, PASSWORD_RESET_CODE_PATH } from '@/features/onboarding/paths'
import { useDataSource } from '@/lib/use-data-source'

import { sendPasswordResetCode } from './auth-client'
import { EmailStep } from './email-step'

/**
 * S13-6 비밀번호 재설정 — 이메일 입력. Signup-email 을 제목만 바꿔 쓴다(상태 · 문구는 `EmailStep`).
 *
 * 가입 여부를 드러내지 않는다 — 코드 받기는 가입 여부와 무관하게 같은 응답이라(백엔드 #58, backend/docs/modules.md "비밀번호 재설정")
 * 늘 코드 단계로 간다. 그래서 "가입하지 않은 이메일" 상태가 없다. 출처는 `useDataSource()` 로 넘긴다(#166).
 *
 * 코드를 보내면 재설정 초안을 이 이메일 · 보낸 시각으로 새로 쓴다 — 앞선 코드 · 토큰은 무효다.
 * 재설정이 인증 만료로 돌아오면 `?reason=verification-expired` 로 와서 안내를 띄운다(코드를 새로 보내기 전까지).
 *
 * 시안: docs/design/auth/screens/ 의 Signup-email (+ -T · -D), docs/design/auth/README.md
 */
export function PasswordResetEmailScreen({
  verificationExpired = false,
}: {
  /** 재설정 요청이 인증 만료로 돌아왔는지 (`?reason=verification-expired`) */
  verificationExpired?: boolean
}) {
  const { passwordReset, updatePasswordReset } = useOnboarding()
  const source = useDataSource()

  return (
    <EmailStep
      title="가입한 이메일을 알려 주세요"
      initialEmail={passwordReset.email}
      showVerificationExpired={verificationExpired && passwordReset.codeSentAt === null}
      sendCode={(email) => sendPasswordResetCode(email, source)}
      onSent={(email, sentAt) =>
        updatePasswordReset({ email, codeSentAt: sentAt, resetToken: null })
      }
      backPath={LOGIN_EMAIL_PATH}
      nextPath={PASSWORD_RESET_CODE_PATH}
    />
  )
}
