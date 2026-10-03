'use client'

import { type FormEvent, useEffect, useId, useRef, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { ErrorState } from '@/components/error-state'
import { TextField } from '@/components/text-field'
import { ToastRegion, useToast } from '@/components/toast'
import {
  changePassword,
  type ChangePasswordResult,
  type MockAuthState,
} from '@/features/auth/auth-client'
import { saveLoginReturn } from '@/features/auth/login-return-store'
import { retryMemberInfo } from '@/features/auth/member-info'
import { confirmProblem, passwordProblem } from '@/features/auth/signup-rules'
import { useMockProfile, useMockProfileStatus } from '@/features/auth/use-mock-auth'
import { reportButtonLabel } from '@/features/home/report-gate'
import { PASSWORD_RESET_PATH } from '@/features/onboarding/paths'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { navHref } from '@/lib/nav'
import { useActiveRef } from '@/lib/use-active-ref'
import { useClearOnPageFreeze } from '@/lib/use-clear-on-page-freeze'
import { useDataSource } from '@/lib/use-data-source'

import { AccountPageLayout } from './account-page-layout'
import { ME_PASSWORD_PATH, ME_PATH, meSearch, regionSearch, reportHrefFor } from './me-paths'
import { useMeTrail } from './me-trail'
import { useMemberGate } from './member-gate'
import { useShownRegionName } from './member-region'

/**
 * `wrong-current` 는 현재 비밀번호가 맞지 않음, `locked` 는 현재 비밀번호를 여러 번 틀려 잠시 막힘(`MEMBER_006`), `rule` 은 서버가
 * 새 비밀번호를 규칙 위반으로 거절함, `failed` 는 응답을 받지 못함(네트워크 · 서버 오류 · 비밀번호 없는 계정), `done` 은 마치고
 * 내 정보로 가는 중이다
 */
type Status = 'idle' | 'submitting' | 'wrong-current' | 'locked' | 'rule' | 'failed' | 'done'

/**
 * S10 비밀번호 변경 (`/me/password`, Settings-password). 회원만 본다(`useMemberGate` — 비회원은 로그인 뒤 이 화면으로 돌아온다, `?next=`, #140).
 *
 * - 현재 비밀번호 · 새 비밀번호 · 확인 · `비밀번호를 잊었어요`. **비밀번호가 없는 회원(카카오로만 로그인, `hasPassword` false)에게는
 *   이 화면이 없다** — 비밀번호 최초 설정 API 를 백엔드 #61 에서 없앴다(#166). 그리지 않고 내 정보로 돌려보낸다(`useMeTrail().goBack`
 *   — 주소로 들어온 그림에서는 기록을 바꿔 가고, 화면을 연 뒤 내 정보를 다시 읽어 false 가 됐으면 내 정보에서 왔을 때 기록을 되돌린다).
 *   내 정보도 비밀번호 행을 숨긴다
 * - 규칙 · 문구는 가입(S13-4) · 재설정(S13-6)과 같다(`signup-rules`). 오류는 버튼을 누를 때 처음 보이고 그 뒤 고칠 때마다 다시 판단한다.
 *   새 비밀번호가 현재와 같아도 막지 않는다(백엔드 계약에 없음)
 * - 현재 비밀번호가 맞지 않으면 그 칸 아래에 알리고 그 칸으로 포커스를 옮긴다. 확인 시도가 많아 막히면 회색 상자, 응답을 받지 못하면
 *   빨강 상자로 알린다. 서버가 비밀번호 없는 계정이라고 하면(`no-password` — 내 정보와 어긋남) 빨강 상자로 알리고, 내 정보를 다시 읽어
 *   `hasPassword` 가 false 면 위처럼 내 정보로 돌아간다
 * - **비밀번호는 이 화면 상태에만 둔다**(Provider · 목 세션 · 주소 · 로그 금지). 마치면 칸을 비우고 알림(`password-changed` — 다른 기기는
 *   로그아웃됐다는 안내 포함)을 내 정보 레이아웃에 남긴 뒤 내 정보로 간다 — 내 정보에서 왔으면 `router.back()`, 주소로 바로 들어왔으면
 *   `/me` 로 기록을 바꿔 간다. 어느 쪽이든 뒤로 가기로 이 화면에 돌아오지 않고, 기록에 `/me` 가 두 번 남지 않는다.
 *   이 기기는 로그인 상태로 남는다(서버가 다른 기기만 로그아웃한다)
 * - 보내는 중에는 칸을 읽기 전용으로, 버튼 · 뒤로를 `aria-disabled` 로 꺼 두 번 보내지 않는다
 */
export function PasswordScreen({
  regionName,
  regionCode = null,
}: {
  regionName: string
  regionCode?: string | null
}) {
  const auth = useMemberGate({ next: ME_PASSWORD_PATH })
  const profile = useMockProfile()
  const profileStatus = useMockProfileStatus()
  const searchParams = useSearchParams()
  const { goBack } = useMeTrail()
  const noPassword = auth !== null && profile !== null && !profile.hasPassword
  const backHref = navHref(ME_PATH, meSearch(regionCode, searchParams))
  useEffect(() => {
    // 비밀번호가 없는 회원에게는 바꿀 비밀번호가 없다. 내 정보로 돌려보낸다(알림 없음)
    if (noPassword) goBack(backHref)
  }, [noPassword, goBack, backHref])

  // 실데이터 프로필(`GET /me`)을 읽지 못하면 공통 오류 화면이다. 읽는 동안은 그리지 않는다(비밀번호가 있는지 모른다)
  if (auth && profileStatus === 'failed') return <ErrorState onRetry={retryMemberInfo} nav="me" />
  if (!auth || !profile || noPassword) return null
  return <PasswordForm auth={auth} regionName={regionName} regionCode={regionCode} />
}

function PasswordForm({
  auth,
  regionName,
  regionCode,
}: {
  auth: MockAuthState
  regionName: string
  regionCode: string | null
}) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { goBack, leaveNotice } = useMeTrail()
  const openBrowseRegion = useBrowseRegion(ME_PASSWORD_PATH, regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)
  const active = useActiveRef()
  const source = useDataSource()
  const { toast, show, dismiss } = useToast()
  const formId = useId()
  const currentRef = useRef<HTMLInputElement>(null)
  const [current, setCurrent] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [checked, setChecked] = useState(false)
  const [status, setStatus] = useState<Status>('idle')
  // 뒤로 가기 캐시에 들어가기 전에 현재 · 새 비밀번호 칸을 비운다(#186)
  useClearOnPageFreeze(() => {
    setCurrent('')
    setPassword('')
    setConfirm('')
    setChecked(false)
  })

  const busy = status === 'submitting' || status === 'done'
  const problems = {
    password: passwordProblem(password),
    confirm: confirmProblem(password, confirm),
  }
  const hasProblem = problems.password !== null || problems.confirm !== null
  const empty = current === '' || password === '' || confirm === ''
  // 서버가 규칙 위반으로 거절했으면 칸을 고칠 때까지 같은 값을 다시 보내지 않는다
  const blocked = empty || busy || (checked && hasProblem) || status === 'rule'

  function change(set: (value: string) => void, value: string) {
    set(value)
    if (status === 'failed' || status === 'locked' || status === 'rule') setStatus('idle')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (blocked) return
    setChecked(true)
    if (hasProblem) return
    setStatus('submitting')
    let result: ChangePasswordResult
    try {
      result = await changePassword(current, password, source)
    } catch {
      if (active.current) setStatus('failed')
      return
    }
    if (!active.current) return
    if (result.status === 'wrong-current') {
      setStatus('wrong-current')
      currentRef.current?.focus()
      return
    }
    if (result.status !== 'ok') {
      // 비밀번호 없는 계정(`no-password`)은 내 정보를 다시 읽는 중이다 — false 로 바뀌면 화면이 내 정보로 돌아간다
      setStatus(
        result.status === 'locked'
          ? 'locked'
          : result.status === 'invalid-password'
            ? 'rule'
            : 'failed',
      )
      return
    }
    setCurrent('')
    setPassword('')
    setConfirm('')
    setStatus('done')
    // 알림은 내 정보 레이아웃에 남긴다. 내 정보에서 왔으면 기록을 되돌려(기록에 /me 가 두 번 남지 않게),
    // 주소로 바로 들어왔으면 동네 · 덮어쓰기를 남긴 내 정보로 기록을 바꿔 간다 — 어느 쪽이든 뒤로 가기로 이 화면에 돌아오지 않는다
    leaveNotice('password-changed')
    goBack(navHref(ME_PATH, meSearch(regionCode, searchParams)))
  }

  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })

  return (
    <AccountPageLayout
      title="비밀번호 변경"
      regionName={shownRegionName}
      navSearch={regionSearch(regionCode)}
      onBack={() => {
        if (!busy) goBack(navHref(ME_PATH, meSearch(regionCode, searchParams)))
      }}
      backDisabled={busy}
      onRegionClick={openBrowseRegion}
      onNotificationClick={() => notReady('알림 설정')}
      onReportClick={() => router.push(reportHrefFor(auth, regionCode))}
      reportLabel={reportLabel}
      footer={
        <>
          <Button type="submit" form={formId} fullWidth aria-disabled={blocked || undefined}>
            비밀번호 바꾸기
          </Button>
          <Button
            variant="subtle"
            aria-disabled={busy || undefined}
            onClick={() => {
              if (busy) return
              // 이 계정을 재설정하면 서버가 모든 기기를 로그아웃해 이 탭도 비회원으로 이메일 로그인(reset-done)에 닿는다.
              // 그 로그인 뒤 내 정보로 돌아오게 돌아갈 곳을 둔다 — 앞서 다른 흐름에서 둔 값이 끼어들지 않게 덮어쓰기도 한다(#140).
              // 비밀번호 변경 화면으로 돌려보내지 않는다: 방금 비밀번호를 정했으니 다시 바꿀 까닭이 없다
              saveLoginReturn({ next: ME_PATH, region: null, intent: null })
              router.push(PASSWORD_RESET_PATH)
            }}
          >
            비밀번호를 잊었어요
          </Button>
        </>
      }
    >
      <form
        id={formId}
        onSubmit={(event) => void submit(event)}
        noValidate
        className="flex flex-col gap-5"
      >
        <TextField
          ref={currentRef}
          label="현재 비밀번호"
          type="password"
          autoComplete="current-password"
          value={current}
          readOnly={busy}
          onChange={(event) => {
            setCurrent(event.target.value)
            // 새 비밀번호의 규칙 오류(rule)는 새 비밀번호를 고칠 때 지운다
            if (status === 'wrong-current' || status === 'locked' || status === 'failed') {
              setStatus('idle')
            }
          }}
          error={status === 'wrong-current' ? '현재 비밀번호가 맞지 않아요.' : undefined}
        />
        <TextField
          label="새 비밀번호"
          type="password"
          autoComplete="new-password"
          value={password}
          readOnly={busy}
          onChange={(event) => change(setPassword, event.target.value)}
          hint="8~20자 · 영문과 숫자 포함 · 띄어쓰기 없이"
          error={
            (checked && problems.password) || status === 'rule'
              ? '영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요.'
              : undefined
          }
        />
        <TextField
          label="새 비밀번호 확인"
          type="password"
          autoComplete="new-password"
          value={confirm}
          readOnly={busy}
          onChange={(event) => change(setConfirm, event.target.value)}
          error={checked && problems.confirm ? '비밀번호가 서로 달라요.' : undefined}
        />

        {status === 'locked' && (
          // 새로 나타나는 상자라 꼭 읽히게 alert 로 둔다. 잠금 시간은 서버 설정이라 못 박지 않는다
          <AlertBox tone="neutral" role="alert">
            비밀번호 확인 시도가 많아 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.
          </AlertBox>
        )}
        {status === 'failed' && (
          <AlertBox tone="danger">비밀번호를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
        )}
      </form>

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-36 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </AccountPageLayout>
  )
}
