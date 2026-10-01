'use client'

import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { PASSWORD_RESET_NEW_PATH, PASSWORD_RESET_PATH } from '@/features/onboarding/paths'

import { sendPasswordResetCode, verifyPasswordResetCode } from './auth-client'
import { CodeStep } from './code-step'

/**
 * S13-6 비밀번호 재설정 — 인증 코드. Signup-code 를 중립 문구만 바꿔 쓴다(상태 · 문구는 `CodeStep`).
 *
 * 제목 아래 "가입한 이메일이면 코드를 보내 드려요." 는 상태와 무관하게 늘 보인다 — 화면은 가입 여부를 모른 채 이 단계로 온다.
 * 한도 · 수명은 가입 인증과 같다(6자리 · 5분 · 다시 받기 60초 · 오입력 5회). 코드를 맞히면 일회용 재설정 토큰을 받아
 * Provider 에 두고 새 비밀번호로 간다. 다시 받으면 토큰을 지운다.
 * 보낸 이메일이 없으면(주소로 바로 들어옴 · 새로고침) 이메일 단계로 돌려보낸다.
 *
 * 시안: docs/design/auth/screens/ 의 Signup-code (+ -T · -D), docs/design/auth/README.md
 */
export function PasswordResetCodeScreen() {
  const { passwordReset, updatePasswordReset } = useOnboarding()

  return (
    <CodeStep
      notice="가입한 이메일이면 코드를 보내 드려요."
      email={passwordReset.email}
      codeSentAt={passwordReset.codeSentAt}
      verified={passwordReset.resetToken !== null}
      sendCode={sendPasswordResetCode}
      verifyCode={verifyPasswordResetCode}
      // 일회용 재설정 토큰은 Provider 메모리에만 둔다(주소 · 로그 · 저장소 금지)
      onVerified={({ resetToken }) => updatePasswordReset({ resetToken })}
      onResent={(codeSentAt) => updatePasswordReset({ codeSentAt, resetToken: null })}
      emailPath={PASSWORD_RESET_PATH}
      nextPath={PASSWORD_RESET_NEW_PATH}
    />
  )
}
