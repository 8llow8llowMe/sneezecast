'use client'

import { type FormEvent, useEffect, useId, useState } from 'react'
import Link from 'next/link'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { TextField } from '@/components/text-field'
import { ToastRegion, useToast } from '@/components/toast'
import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import {
  HOME_PATH,
  LOGIN_PATH,
  PASSWORD_RESET_PATH,
  SIGNUP_EMAIL_PATH,
} from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'
import { useClearOnPageFreeze } from '@/lib/use-clear-on-page-freeze'
import { useDataSource } from '@/lib/use-data-source'
import { useNavTrail } from '@/lib/use-nav-trail'

import { loginWithEmail } from './auth-client'
import { afterLoginHref, loginHref, type LoginReturn, NO_LOGIN_RETURN } from './login-return'
import { clearLoginReturn, saveLoginReturn } from './login-return-store'

/** 보낸 뒤 결과. `failed` 는 응답을 받지 못한 경우다(네트워크 · 서버 오류) */
type Status = 'idle' | 'submitting' | 'wrong' | 'locked' | 'limited' | 'suspended' | 'failed'

/**
 * S13-5 이메일 로그인. 단계 표시는 없다.
 *
 * | 상태 | 보이는 것 |
 * | --- | --- |
 * | 기본 | 이메일 · 비밀번호(보기) · 로그인. 빈 칸이 있으면 로그인이 꺼진다 |
 * | wrong | "이메일 또는 비밀번호가 맞지 않아요." 빨강 상자 |
 * | locked | 회색 상자(`role="alert"`) · 로그인 꺼짐. 이메일을 바꿔야 다시 켜진다 — 비밀번호만 고쳐서는 풀리지 않는다 |
 * | limited | 회색 상자(`role="alert"`) "잠시 뒤 다시". 이 기기(IP)에 걸린 제한이라 로그인을 끄지 않는다 (시안 없음, `AUTH_013`) |
 * | suspended | "이용이 정지된 계정이에요." 빨강 상자 (시안 없음, `MEMBER_003`) |
 * | reset-done | `?reason=reset-done` 이면 "비밀번호를 바꿨어요" 토스트 · 재설정에 쓴 이메일로 칸을 채운다(S13-6) |
 *
 * 로그인 버튼은 `disabled` 대신 `aria-disabled` 로 끈다. 보내는 중 · 잠김으로 바뀌어도 포커스가 버튼에 남고
 * (disabled 가 되면 포커스가 사라진다) 스크린리더는 "흐리게 표시됨" 으로 읽는다. 누름은 `submit` 이 막는다.
 * 보내는 중에는 칸을 읽기 전용으로 두어, 고치기 전 값의 결과가 고친 값 옆에 뜨지 않게 한다.
 *
 * 성공하면 홈으로 기록을 바꿔 간다 — 뒤로 가기로 로그인 화면에 돌아오지 않게 한다. 돌아갈 곳(`?next=` · `?region=`, #123)이
 * 있으면 그곳(예: `/me?region=…`)으로 간다. 보고하려던 로그인(`?intent=report`, #136)이면 같은 동네 홈의 보고 진입
 * (`/?region=…&report=start`)이다. 뒤로는 로그인 방법 고르기(S13-1)에서 왔을 때만 되돌리고, 아니면 돌아갈 곳을 붙인
 * 로그인 방법 고르기로 기록을 바꿔 간다. 보고하려던 로그인은 홈(로그인 안내 시트)에서 바로 오므로 홈에서 왔을 때도 되돌린다 — 시트로 돌아간다.
 * 가입 · 비밀번호 재설정 링크는 주소에 돌아갈 곳을 넘기지 않고, 누를 때 둔다(`login-return-store.ts`, #140). 그 화면들의 뒤로는 기록을
 * 되돌려 이 주소(쿼리 포함)로 돌아온다. 가입을 마치면(S02-4) 돌아갈 곳으로 가고, 재설정을 마치면 `?reason=reset-done` 에 돌아갈 곳을
 * 붙여 이 화면으로 와 로그인 뒤 그곳으로 간다. 로그인에 성공하면 둔 값을 지운다(이 화면은 주소로 받았다).
 * 비밀번호는 이 화면 상태에만 두고 어디에도 남기지 않는다.
 *
 * 시안: docs/design/auth/screens/ 의 Login-email (+ -T · -D)
 */
export function LoginEmailScreen({
  resetDone = false,
  loginReturn = NO_LOGIN_RETURN,
}: {
  resetDone?: boolean
  /** 로그인 뒤 돌아갈 곳. 라우트가 `?next=` · `?region=` · `?intent=` 에서 읽어 넘긴다 */
  loginReturn?: LoginReturn
}) {
  const navTrail = useNavTrail()
  const source = useDataSource()
  const { replace, passwordReset, clearPasswordReset } = useOnboarding()
  const active = useActiveRef()
  const { toast, show, dismiss } = useToast()
  const formId = useId()
  // 비밀번호를 바꾸고 왔으면 재설정에 쓴 이메일로 칸을 채운다. 주소로는 받지 않는다(이메일을 주소에 남기지 않는다) —
  // 새로고침했거나 주소로 바로 왔으면 Provider 가 비어 빈 칸이다
  const [email, setEmail] = useState(resetDone ? passwordReset.email : '')
  const [password, setPassword] = useState('')
  const [status, setStatus] = useState<Status>('idle')
  // 뒤로 가기 캐시에 들어가기 전에 입력만 하고 보내지 않은 이메일 · 비밀번호를 비운다(#186). 공용 기기의 다음 사람이 보지 못하게
  useClearOnPageFreeze(() => {
    setEmail('')
    setPassword('')
    setStatus('idle')
  })

  // 그만둔(또는 마친) 비밀번호 재설정의 보낸 시각 · 토큰을 지운다. 공용 기기에서 앞으로 가기로 새 비밀번호 화면에 다시
  // 들어가지 못하게 한다. 이메일은 남긴다 — 위 칸의 처음 값은 이 effect 보다 먼저(첫 렌더) 읽힌다
  useEffect(() => {
    clearPasswordReset({ keepEmail: true })
  }, [clearPasswordReset])

  useEffect(() => {
    if (resetDone) show({ message: '비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.' })
  }, [resetDone, show])

  const submitting = status === 'submitting'
  const empty = email.trim() === '' || password === ''
  const blocked = empty || submitting || status === 'locked'

  // 칸을 고치면 앞 결과 안내를 지운다. 잠김은 계정(이메일)에 걸린 것이라 이메일을 바꿨을 때만 푼다
  function changeEmail(value: string) {
    setEmail(value)
    if (!submitting) setStatus('idle')
  }
  function changePassword(value: string) {
    setPassword(value)
    if (status !== 'idle' && status !== 'submitting' && status !== 'locked') setStatus('idle')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (blocked) return
    setStatus('submitting')
    try {
      const result = await loginWithEmail(email.trim(), password, source)
      // 로그인했으면 가입 · 재설정에 들고 가던 돌아갈 곳은 끝났다(화면을 떠났어도 지운다)
      if (result.status === 'ok') clearLoginReturn()
      // 기다리는 동안 화면을 떠났으면 늦은 응답으로 이동하지 않는다
      if (!active.current) return
      if (result.status === 'ok') {
        replace(afterLoginHref(loginReturn))
        return
      }
      setStatus(result.status)
    } catch {
      if (active.current) setStatus('failed')
    }
  }

  return (
    <OnboardingLayout
      onBack={() =>
        navTrail.goBack(
          loginHref(LOGIN_PATH, loginReturn),
          loginReturn.intent === 'report' ? [LOGIN_PATH, HOME_PATH] : [LOGIN_PATH],
        )
      }
      panelTitle="다시 오셨네요"
      footer={
        <Button type="submit" form={formId} fullWidth aria-disabled={blocked || undefined}>
          로그인
        </Button>
      }
    >
      <form
        id={formId}
        onSubmit={(event) => void submit(event)}
        noValidate
        className="flex flex-col gap-5"
      >
        <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
          이메일로 로그인해 주세요
        </h1>

        <TextField
          label="이메일"
          type="email"
          inputMode="email"
          autoComplete="username"
          value={email}
          readOnly={submitting}
          onChange={(event) => changeEmail(event.target.value)}
        />
        <TextField
          label="비밀번호"
          type="password"
          autoComplete="current-password"
          value={password}
          readOnly={submitting}
          onChange={(event) => changePassword(event.target.value)}
        />

        {status === 'wrong' && (
          <AlertBox tone="danger">이메일 또는 비밀번호가 맞지 않아요.</AlertBox>
        )}
        {status === 'failed' && (
          <AlertBox tone="danger">로그인하지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
        {status === 'locked' && (
          // 새로 나타나는 상자라 status 로는 첫 알림을 놓칠 수 있다. 잠김은 꼭 알려야 해 alert 로 읽힌다
          <AlertBox tone="neutral" role="alert">
            로그인 시도가 많아 잠시 막혔어요. 10분 뒤 다시 시도해 주세요.
          </AlertBox>
        )}
        {status === 'limited' && (
          // 잠김과 같은 모양이다. 남은 시간을 서버가 주지 않아 시간을 못 박지 않는다
          <AlertBox tone="neutral" role="alert">
            로그인 시도가 많아 잠시 막혔어요. 잠시 뒤 다시 시도해 주세요.
          </AlertBox>
        )}
        {status === 'suspended' && <AlertBox tone="danger">이용이 정지된 계정이에요.</AlertBox>}

        <div className="flex justify-between">
          <Link
            href={PASSWORD_RESET_PATH}
            onClick={() => saveLoginReturn(loginReturn)}
            className="flex min-h-touch items-center px-1 text-body font-semibold text-fg-sub"
          >
            비밀번호를 잊었어요
          </Link>
          <Link
            href={SIGNUP_EMAIL_PATH}
            onClick={() => saveLoginReturn(loginReturn)}
            className="flex min-h-touch items-center px-1 text-body font-semibold text-brand"
          >
            이메일로 가입하기
          </Link>
        </div>
      </form>

      {/* 시안: 화면 아래에서 120 위, 좌우 20 여백 · 최대 420. 데스크톱은 화면 가운데라 일러스트 패널 위에 걸친다 */}
      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-30 z-10 mx-auto max-w-115 px-page-mobile"
      />
    </OnboardingLayout>
  )
}
