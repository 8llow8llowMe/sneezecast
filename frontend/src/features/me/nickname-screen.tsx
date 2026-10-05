'use client'

import { type FormEvent, useId, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { ErrorState } from '@/components/error-state'
import { TextField } from '@/components/text-field'
import { ToastRegion, useToast } from '@/components/toast'
import {
  type MockAuthState,
  updateNickname,
  type UpdateNicknameResult,
} from '@/features/auth/auth-client'
import { retryMemberInfo } from '@/features/auth/member-info'
import {
  NICKNAME_HINT,
  NICKNAME_MAX_LENGTH,
  NICKNAME_RULE_ERROR,
  nicknameLength,
  nicknameProblem,
} from '@/features/auth/signup-rules'
import { useMockProfile, useMockProfileStatus } from '@/features/auth/use-mock-auth'
import { reportButtonLabel } from '@/features/home/report-gate'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { navHref } from '@/lib/nav'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import { AccountPageLayout } from './account-page-layout'
import { ME_NICKNAME_PATH, ME_PATH, meSearch, regionSearch, reportHrefFor } from './me-paths'
import { useMeTrail } from './me-trail'
import { useMemberGate } from './member-gate'
import { useShownRegionName } from './member-region'

/**
 * `invalid` 는 서버가 닉네임을 규칙 위반으로 거절함, `failed` 는 응답을 받지 못함(네트워크 · 서버 오류), `done` 은 마치고
 * 내 정보로 가는 중이다
 */
type Status = 'idle' | 'submitting' | 'invalid' | 'failed' | 'done'

/**
 * S10 닉네임 바꾸기 (`/me/nickname`, #192). 회원만 본다(`useMemberGate`). **시안이 없어** 계정 화면(Settings-password)의 틀을 그대로 쓴다.
 *
 * - 닉네임 칸 하나(지금 닉네임으로 채움) → `이 닉네임으로 바꾸기`. 도움말 · 글자 수 · 규칙 문구는 가입(S13-4)과 같다(`signup-rules` 의 상수 · 판정 —
 *   앞뒤 공백을 지운 뒤 2~10자, 이모지도 한 글자). 오류는 버튼을 누를 때 처음 보이고 그 뒤 고칠 때마다 다시 판단한다
 * - 비었거나(공백만 포함) 지금 닉네임과 같으면(앞뒤 공백을 지우고 비교) 버튼이 꺼져 있다(`aria-disabled`)
 * - 보내는 중에는 칸을 읽기 전용으로, 버튼 · 뒤로를 꺼 두 번 보내지 않는다. 응답을 받지 못하면 빨강 상자로 알리고 다시 누를 수 있다.
 *   서버가 규칙 위반으로 거절하면(`invalid` — 화면 규칙과 어긋났을 때만) 칸 아래 규칙 문구를 보이고 고칠 때까지 버튼을 끈다
 * - 성공하면 프로필(목 프로필 · 실데이터 회원 정보 저장소)이 화면과 무관하게 먼저 바뀐다. 알림(`nickname-changed`)을 내 정보 레이아웃에
 *   남기고 비밀번호 · 내 동네와 같게 내 정보로 간다 — 내 정보에서 왔으면 `router.back()`, 주소로 바로 들어왔으면 `/me` 로 기록을 바꿔 간다
 * - 실데이터 프로필(`GET /me`)을 읽는 동안은 그리지 않고(지금 닉네임을 모른다), 읽지 못하면 공통 오류 화면이다(비밀번호 화면과 같다)
 */
export function NicknameScreen({
  regionName,
  regionCode = null,
}: {
  /** 데스크톱 머리줄의 동네 이름(서버가 준 둘러보기 동네 · 목 예시) */
  regionName: string
  /** 둘러보기(`?region=`)로 고른 행정동 코드 */
  regionCode?: string | null
}) {
  const auth = useMemberGate({ next: ME_NICKNAME_PATH })
  const profile = useMockProfile()
  const profileStatus = useMockProfileStatus()

  if (auth && profileStatus === 'failed') return <ErrorState onRetry={retryMemberInfo} nav="me" />
  if (!auth || !profile) return null
  return (
    <NicknameForm
      auth={auth}
      currentNickname={profile.nickname}
      regionName={regionName}
      regionCode={regionCode}
    />
  )
}

function NicknameForm({
  auth,
  currentNickname,
  regionName,
  regionCode,
}: {
  auth: MockAuthState
  /** 지금 닉네임. 칸의 처음 값이고, 같은 값이면 버튼을 끈다 */
  currentNickname: string
  regionName: string
  regionCode: string | null
}) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { goBack, leaveNotice } = useMeTrail()
  const openBrowseRegion = useBrowseRegion(ME_NICKNAME_PATH, regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)
  const active = useActiveRef()
  const source = useDataSource()
  const { toast, show, dismiss } = useToast()
  const formId = useId()
  const [nickname, setNickname] = useState(currentNickname)
  const [checked, setChecked] = useState(false)
  const [status, setStatus] = useState<Status>('idle')

  const busy = status === 'submitting' || status === 'done'
  const problem = nicknameProblem(nickname)
  const empty = nickname.trim() === ''
  const unchanged = nickname.trim() === currentNickname
  // 서버가 규칙 위반으로 거절했으면 칸을 고칠 때까지 같은 값을 다시 보내지 않는다
  const blocked =
    empty || unchanged || busy || (checked && problem !== null) || status === 'invalid'
  const backHref = navHref(ME_PATH, meSearch(regionCode, searchParams))

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (blocked) return
    setChecked(true)
    if (problem) return
    setStatus('submitting')
    let result: UpdateNicknameResult
    try {
      result = await updateNickname(nickname, source)
    } catch {
      if (active.current) setStatus('failed')
      return
    }
    if (!active.current) return
    if (result.status === 'invalid') {
      setStatus('invalid')
      return
    }
    // 목 프로필 · 실데이터 저장소에는 이미 바뀐 닉네임이 남았다. 이동만 화면이 떠 있을 때 한다
    setStatus('done')
    leaveNotice('nickname-changed')
    goBack(backHref)
  }

  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })

  return (
    <AccountPageLayout
      title="닉네임 바꾸기"
      regionName={shownRegionName}
      navSearch={regionSearch(regionCode)}
      onBack={() => {
        if (!busy) goBack(backHref)
      }}
      backDisabled={busy}
      onRegionClick={openBrowseRegion}
      onNotificationClick={() => notReady('알림 설정')}
      onReportClick={() => router.push(reportHrefFor(auth, regionCode))}
      reportLabel={reportLabel}
      footer={
        <Button type="submit" form={formId} fullWidth aria-disabled={blocked || undefined}>
          이 닉네임으로 바꾸기
        </Button>
      }
    >
      <form
        id={formId}
        onSubmit={(event) => void submit(event)}
        noValidate
        className="flex flex-col gap-5"
      >
        <TextField
          label="닉네임"
          autoComplete="nickname"
          value={nickname}
          readOnly={busy}
          onChange={(event) => {
            setNickname(event.target.value)
            if (status === 'invalid' || status === 'failed') setStatus('idle')
          }}
          hint={NICKNAME_HINT}
          error={(checked && problem) || status === 'invalid' ? NICKNAME_RULE_ERROR : undefined}
          counter={{ current: nicknameLength(nickname), max: NICKNAME_MAX_LENGTH }}
        />

        {status === 'failed' && (
          <AlertBox tone="danger">닉네임을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
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
