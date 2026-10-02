'use client'

import { useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { ToastRegion, useToast } from '@/components/toast'
import { type MemberRegion, type MockAuthState, saveRegion } from '@/features/auth/auth-client'
import { reportButtonLabel } from '@/features/home/report-gate'
import {
  DistrictOptionList,
  districtOptions,
  DistrictSearchInput,
  SearchNotices,
  searchResultsOf,
} from '@/features/onboarding/district-picker'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import type { District } from '@/features/region/types'
import { useDistrictSearch } from '@/features/region/use-district-search'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { navHref } from '@/lib/nav'
import { useActiveRef } from '@/lib/use-active-ref'

import { AccountPageLayout } from './account-page-layout'
import { ME_PATH, ME_REGION_PATH, meSearch, regionSearch, reportHrefFor } from './me-paths'
import { useMeTrail } from './me-trail'
import { useMemberGate } from './member-gate'
import { useMemberRegion, useShownRegionName } from './member-region'

/** `failed` 는 응답을 받지 못함(네트워크 · 서버 오류), `done` 은 마치고 내 정보로 가는 중이다 */
type Status = 'idle' | 'submitting' | 'failed' | 'done'

/**
 * S10 내 동네 바꾸기 (`/me/region`, #141). 회원만 본다(`useMemberGate`). **시안이 없어** 계정 화면(Settings-password)의 틀 ·
 * 동네 선택(Setup-1)의 검색 조각을 그대로 쓴다.
 *
 * - 안내 → 지금 내 동네 → 검색 칸 → 결과 목록 → `이 동네로 바꾸기`. **위치 권한을 요청하지 않는다** — 행정동은 직접 고른다
 * - 내 동네는 보고 · 알림의 기준이다. 머리줄 동네 이름(둘러보기 동네)과 다르다 — 그쪽은 `?region=` 만 바꾼다
 * - 지금 내 동네와 같은 동네를 골랐거나 고르지 않았으면 버튼이 꺼져 있다(`aria-disabled`). 검색어를 고치면 선택을 지운다(동네 선택과 같다)
 * - 저장(`saveRegion`, 가입 마무리 · 다시 고르기와 같은 백엔드 #60 설정 API)하는 중에는 검색 칸이 읽기 전용, 버튼 · 뒤로가 꺼진다.
 *   실패하면 빨강 상자로 알리고 다시 누를 수 있다
 * - 성공하면 알림(`region-changed`)을 내 정보 레이아웃에 남기고 내 정보로 간다 — 비밀번호 화면과 같게 내 정보에서 왔으면 `router.back()`,
 *   주소로 바로 들어왔으면 `/me` 로 기록을 바꿔 간다. 어느 쪽이든 뒤로 가기로 이 화면에 돌아오지 않는다
 * - 저장에 성공하면 목 프로필이 화면과 무관하게 먼저 바뀐다. 이동할 때까지 "지금 내 동네" 가 바뀌지 않게 처음 그린 값을 쓴다
 */
export function MyRegionScreen({
  regionName,
  regionCode = null,
}: {
  /** 데스크톱 머리줄의 동네 이름(서버가 준 둘러보기 동네 · 목 예시) */
  regionName: string
  /** 둘러보기(`?region=`)로 고른 행정동 코드 */
  regionCode?: string | null
}) {
  const auth = useMemberGate({ next: ME_REGION_PATH })
  const memberRegion = useMemberRegion()
  if (!auth) return null
  return (
    <MyRegionForm
      auth={auth}
      initialRegion={memberRegion}
      regionName={regionName}
      regionCode={regionCode}
    />
  )
}

function MyRegionForm({
  auth,
  initialRegion,
  regionName,
  regionCode,
}: {
  auth: MockAuthState
  initialRegion: MemberRegion | null
  regionName: string
  regionCode: string | null
}) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { goBack, leaveNotice } = useMeTrail()
  const active = useActiveRef()
  const { toast, show, dismiss } = useToast()
  const openBrowseRegion = useBrowseRegion(ME_REGION_PATH, regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)
  const [current] = useState(initialRegion)
  const [query, setQuery] = useState('')
  const search = useDistrictSearch(query)
  const [selected, setSelected] = useState<District | null>(null)
  const [status, setStatus] = useState<Status>('idle')

  const busy = status === 'submitting' || status === 'done'
  const blocked = !selected || selected.code === current?.code || busy
  const backHref = navHref(ME_PATH, meSearch(regionCode, searchParams))

  async function save() {
    if (blocked || !selected) return
    setStatus('submitting')
    try {
      await saveRegion(selected)
    } catch {
      if (active.current) setStatus('failed')
      return
    }
    // 목 프로필(연동 때는 서버)에는 이미 바뀐 동네가 남았다. 이동만 화면이 떠 있을 때 한다
    if (!active.current) return
    setStatus('done')
    leaveNotice('region-changed')
    goBack(backHref)
  }

  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })

  return (
    <AccountPageLayout
      title="내 동네 바꾸기"
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
        <Button fullWidth aria-disabled={blocked || undefined} onClick={() => void save()}>
          이 동네로 바꾸기
        </Button>
      }
    >
      <p className="text-body leading-[1.6] text-fg-sub">
        보고와 알림은 내 동네를 기준으로 해요. 행정동을 직접 골라 주세요. 위치 정보는 사용하지
        않아요.
      </p>

      <div className="flex flex-col gap-1.5 rounded-button bg-section p-4.5">
        <span className="text-sub text-fg-sub">지금 내 동네</span>
        <span className="text-section-title font-semibold text-fg">
          {current?.name ?? '아직 정하지 않았어요'}
        </span>
      </div>

      <DistrictSearchInput
        value={query}
        readOnly={busy}
        onChange={(value) => {
          setQuery(value)
          setSelected(null)
          if (status === 'failed') setStatus('idle')
        }}
      />
      <DistrictOptionList
        legend="검색 결과"
        options={districtOptions(searchResultsOf(search))}
        selectedCode={selected?.code ?? null}
        onSelect={(district) => {
          if (busy) return
          setSelected(district)
          if (status === 'failed') setStatus('idle')
        }}
      />
      {/* 검색 결과 알림. 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽으므로 늘 그려 둔다 */}
      <div role="status">
        <SearchNotices search={search} keyword={query.trim()} />
      </div>

      {status === 'failed' && (
        <AlertBox tone="danger">내 동네를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
      )}

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-36 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </AccountPageLayout>
  )
}
