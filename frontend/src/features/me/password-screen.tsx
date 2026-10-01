'use client'

import { type FormEvent, useId, useRef, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { TextField } from '@/components/text-field'
import { ToastRegion, useToast } from '@/components/toast'
import { changePassword, type MockAuthState, setupPassword } from '@/features/auth/auth-client'
import { confirmProblem, passwordProblem } from '@/features/auth/signup-rules'
import { useMockProfile } from '@/features/auth/use-mock-auth'
import { PASSWORD_RESET_PATH } from '@/features/onboarding/paths'
import { navHref } from '@/lib/nav'
import { useActiveRef } from '@/lib/use-active-ref'

import { AccountPageLayout } from './account-page-layout'
import { ME_PATH, type MeNotice, meSearch, regionSearch, reportHrefFor } from './me-paths'
import { useMeTrail } from './me-trail'
import { useMemberGate } from './member-gate'

/** 바꾸기(이메일 회원 · 비밀번호를 정한 카카오 회원) · 설정(아직 비밀번호가 없는 카카오 회원) */
type Mode = 'change' | 'setup'

/**
 * `wrong-current` 는 현재 비밀번호가 맞지 않음, `failed` 는 응답을 받지 못함(네트워크 · 서버 오류), `done` 은 마치고 내 정보로 가는 중이다
 */
type Status = 'idle' | 'submitting' | 'wrong-current' | 'failed' | 'done'

const COPY: Record<
  Mode,
  {
    title: string
    password: string
    confirm: string
    submit: string
    failed: string
    notice: MeNotice
  }
> = {
  change: {
    title: '비밀번호 변경',
    password: '새 비밀번호',
    confirm: '새 비밀번호 확인',
    submit: '비밀번호 바꾸기',
    failed: '비밀번호를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    notice: 'password-changed',
  },
  setup: {
    title: '비밀번호 설정',
    password: '비밀번호',
    confirm: '비밀번호 확인',
    submit: '비밀번호 설정하기',
    failed: '비밀번호를 설정하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    notice: 'password-set',
  },
}

/**
 * S10 비밀번호 변경 · 설정 (`/me/password`, Settings-password). 회원만 본다(`useMemberGate`).
 *
 * | 회원 | 화면 |
 * | --- | --- |
 * | 비밀번호가 있음 (이메일 회원 · 설정을 마친 카카오 회원) | `비밀번호 변경`: 현재 비밀번호 · 새 비밀번호 · 확인 · `비밀번호를 잊었어요` |
 * | 비밀번호가 없음 (카카오 가입) | `비밀번호 설정`: 안내 문단 · 비밀번호 · 확인 (현재 비밀번호 없음) |
 *
 * - 규칙 · 문구는 가입(S13-4) · 재설정(S13-6)과 같다(`signup-rules`). 오류는 버튼을 누를 때 처음 보이고 그 뒤 고칠 때마다 다시 판단한다.
 *   새 비밀번호가 현재와 같아도 막지 않는다(백엔드 계약에 없음)
 * - 현재 비밀번호가 맞지 않으면 그 칸 아래에 알리고 그 칸으로 포커스를 옮긴다. 응답을 받지 못하면 빨강 상자로 알린다
 * - **비밀번호는 이 화면 상태에만 둔다**(Provider · 목 세션 · 주소 · 로그 금지). 마치면 칸을 비우고 알림(`password-changed` ·
 *   `password-set`)을 내 정보 레이아웃에 남긴 뒤 내 정보로 간다 — 내 정보에서 왔으면 `router.back()`, 주소로 바로 들어왔으면
 *   `/me` 로 기록을 바꿔 간다. 어느 쪽이든 뒤로 가기로 이 화면에 돌아오지 않고, 기록에 `/me` 가 두 번 남지 않는다
 * - 보내는 중에는 칸을 읽기 전용으로, 버튼 · 뒤로를 `aria-disabled` 로 꺼 두 번 보내지 않는다
 * - 설정에 성공하면 목 프로필의 `hasPassword` 가 화면과 무관하게 먼저 바뀐다. 이동할 때까지 화면이 바꾸기로 뒤집히지 않게
 *   처음 그린 모드를 그대로 쓴다
 */
export function PasswordScreen({
  regionName,
  regionCode = null,
}: {
  regionName: string
  regionCode?: string | null
}) {
  const auth = useMemberGate()
  const profile = useMockProfile()
  if (!auth || !profile) return null
  return (
    <PasswordForm
      auth={auth}
      initialMode={profile.hasPassword ? 'change' : 'setup'}
      regionName={regionName}
      regionCode={regionCode}
    />
  )
}

function PasswordForm({
  auth,
  initialMode,
  regionName,
  regionCode,
}: {
  auth: MockAuthState
  initialMode: Mode
  regionName: string
  regionCode: string | null
}) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { goBack, leaveNotice } = useMeTrail()
  const active = useActiveRef()
  const { toast, show, dismiss } = useToast()
  const formId = useId()
  const currentRef = useRef<HTMLInputElement>(null)
  const [mode] = useState(initialMode)
  const [current, setCurrent] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [checked, setChecked] = useState(false)
  const [status, setStatus] = useState<Status>('idle')
  const copy = COPY[mode]

  const busy = status === 'submitting' || status === 'done'
  const problems = {
    password: passwordProblem(password),
    confirm: confirmProblem(password, confirm),
  }
  const hasProblem = problems.password !== null || problems.confirm !== null
  const empty = (mode === 'change' && current === '') || password === '' || confirm === ''
  const blocked = empty || busy || (checked && hasProblem)

  function change(set: (value: string) => void, value: string) {
    set(value)
    if (status === 'failed') setStatus('idle')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (blocked) return
    setChecked(true)
    if (hasProblem) return
    setStatus('submitting')
    try {
      if (mode === 'change') {
        const result = await changePassword(current, password)
        if (result.status === 'wrong-current') {
          if (!active.current) return
          setStatus('wrong-current')
          currentRef.current?.focus()
          return
        }
      } else {
        await setupPassword(password)
      }
    } catch {
      if (active.current) setStatus('failed')
      return
    }
    if (!active.current) return
    setCurrent('')
    setPassword('')
    setConfirm('')
    setStatus('done')
    // 알림은 내 정보 레이아웃에 남긴다. 내 정보에서 왔으면 기록을 되돌려(기록에 /me 가 두 번 남지 않게),
    // 주소로 바로 들어왔으면 동네 · 덮어쓰기를 남긴 내 정보로 기록을 바꿔 간다 — 어느 쪽이든 뒤로 가기로 이 화면에 돌아오지 않는다
    leaveNotice(copy.notice)
    goBack(navHref(ME_PATH, meSearch(regionCode, searchParams)))
  }

  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })

  return (
    <AccountPageLayout
      title={copy.title}
      regionName={regionName}
      navSearch={regionSearch(regionCode)}
      onBack={() => {
        if (!busy) goBack(navHref(ME_PATH, meSearch(regionCode, searchParams)))
      }}
      backDisabled={busy}
      onRegionClick={() => notReady('동네 바꾸기')}
      onNotificationClick={() => notReady('알림 설정')}
      onReportClick={() => router.push(reportHrefFor(auth, regionCode))}
      footer={
        <>
          <Button type="submit" form={formId} fullWidth aria-disabled={blocked || undefined}>
            {copy.submit}
          </Button>
          {mode === 'change' && (
            <Button
              variant="subtle"
              aria-disabled={busy || undefined}
              onClick={() => {
                if (!busy) router.push(PASSWORD_RESET_PATH)
              }}
            >
              비밀번호를 잊었어요
            </Button>
          )}
        </>
      }
    >
      <form
        id={formId}
        onSubmit={(event) => void submit(event)}
        noValidate
        className="flex flex-col gap-5"
      >
        {mode === 'setup' && (
          <p className="text-body leading-[1.6] text-fg-sub">
            비밀번호를 정하면 이메일로도 로그인할 수 있어요.
          </p>
        )}

        {mode === 'change' && (
          <TextField
            ref={currentRef}
            label="현재 비밀번호"
            type="password"
            autoComplete="current-password"
            value={current}
            readOnly={busy}
            onChange={(event) => {
              setCurrent(event.target.value)
              if (status === 'wrong-current' || status === 'failed') setStatus('idle')
            }}
            error={status === 'wrong-current' ? '현재 비밀번호가 맞지 않아요.' : undefined}
          />
        )}
        <TextField
          label={copy.password}
          type="password"
          autoComplete="new-password"
          value={password}
          readOnly={busy}
          onChange={(event) => change(setPassword, event.target.value)}
          hint="8~20자 · 영문과 숫자 포함 · 띄어쓰기 없이"
          error={
            checked && problems.password
              ? '영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요.'
              : undefined
          }
        />
        <TextField
          label={copy.confirm}
          type="password"
          autoComplete="new-password"
          value={confirm}
          readOnly={busy}
          onChange={(event) => change(setConfirm, event.target.value)}
          error={checked && problems.confirm ? '비밀번호가 서로 달라요.' : undefined}
        />

        {status === 'failed' && <AlertBox tone="danger">{copy.failed}</AlertBox>}
      </form>

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-36 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </AccountPageLayout>
  )
}
