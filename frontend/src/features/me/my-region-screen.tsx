'use client'

import { useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import {
  type MemberRegion,
  type MockAuthState,
  saveRegion,
  type SaveRegionResult,
} from '@/features/auth/auth-client'
import { type LoadStatus, retryMemberInfo } from '@/features/auth/member-info'
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
import { useDataSource } from '@/lib/use-data-source'

import { AccountPageLayout } from './account-page-layout'
import {
  ME_PATH,
  ME_REGION_PATH,
  meSearch,
  notificationsHref,
  regionSearch,
  reportHrefFor,
} from './me-paths'
import { useMeTrail } from './me-trail'
import { useMemberGate } from './member-gate'
import { useMemberRegion, useMemberRegionStatus, useShownRegionName } from './member-region'

/**
 * `failed` 는 응답을 받지 못함(네트워크 · 서버 오류), `invalid` 는 서버가 그 동네를 받지 않음(없는 코드 · 폐지),
 * `done` 은 마치고 내 정보로 가는 중이다
 */
type Status = 'idle' | 'submitting' | 'failed' | 'invalid' | 'done'

/**
 * S10 내 동네 바꾸기 (`/me/region`, #141). 회원만 본다(`useMemberGate`). **시안이 없어** 계정 화면(Settings-password)의 틀 ·
 * 동네 선택(Setup-1)의 검색 조각을 그대로 쓴다.
 *
 * - 안내 → 지금 내 동네 → 검색 칸 → 결과 목록 → `이 동네로 바꾸기`. **위치 권한을 요청하지 않는다** — 행정동은 직접 고른다
 * - 내 동네는 보고 · 알림의 기준이다. 머리줄 동네 이름(둘러보기 동네)과 다르다 — 그쪽은 `?region=` 만 바꾼다
 * - 지금 내 동네와 같은 동네를 골랐거나 고르지 않았으면 버튼이 꺼져 있다(`aria-disabled`). 검색어를 고치면 선택을 지운다(동네 선택과 같다)
 * - 저장(`saveRegion`, 가입 마무리 · 다시 고르기와 같은 백엔드 #60 `PUT /api/v1/members/me/region`)하는 중에는 검색 칸이 읽기 전용,
 *   버튼 · 뒤로가 꺼진다. 실패하면 빨강 상자로 알리고 다시 누를 수 있다. 서버가 그 동네를 받지 않으면(`invalid`) 선택을 지우고
 *   다른 동네를 고르라고 알린다
 * - 성공하면 알림(`region-changed`)을 내 정보 레이아웃에 남기고 내 정보로 간다 — 비밀번호 화면과 같게 내 정보에서 왔으면 `router.back()`,
 *   주소로 바로 들어왔으면 `/me` 로 기록을 바꿔 간다. 어느 쪽이든 뒤로 가기로 이 화면에 돌아오지 않는다
 * - 저장에 성공하면 내 동네(목 프로필 · 실데이터 저장소)가 화면과 무관하게 먼저 바뀐다. 이동할 때까지 "지금 내 동네" 가 바뀌지 않게
 *   보낼 때의 값을 붙잡아 쓴다
 * - 실데이터는 내 동네를 따로 읽는다(`useMemberRegionStatus`). 읽는 중이면 "지금 내 동네" 에 "불러오고 있어요", 읽지 못했으면
 *   "불러오지 못했어요" 와 `다시 시도` 를 보인다. 그동안에도 새 동네를 골라 저장할 수 있다
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
  const regionStatus = useMemberRegionStatus()
  if (!auth) return null
  return (
    <MyRegionForm
      auth={auth}
      liveRegion={memberRegion}
      regionStatus={regionStatus}
      regionName={regionName}
      regionCode={regionCode}
    />
  )
}

function MyRegionForm({
  auth,
  liveRegion,
  regionStatus,
  regionName,
  regionCode,
}: {
  auth: MockAuthState
  liveRegion: MemberRegion | null
  regionStatus: LoadStatus
  regionName: string
  regionCode: string | null
}) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { goBack, leaveNotice } = useMeTrail()
  const active = useActiveRef()
  const source = useDataSource()
  const openBrowseRegion = useBrowseRegion(ME_REGION_PATH, regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)
  // 보낼 때의 내 동네. 저장에 성공하면 내 동네가 먼저 바뀌므로 이동할 때까지 이 값을 보인다
  // 읽기 상태도 함께 붙잡는다 — 읽기 전에 보냈으면 저장 응답으로 내 동네가 먼저 바뀌어도 "불러오고 있어요" 를 그대로 보인다
  // (동네가 있는 회원에게 "아직 정하지 않았어요" 가 잠깐 비치지 않게)
  const [frozen, setFrozen] = useState<{
    region: MemberRegion | null
    status: LoadStatus
  } | null>(null)
  const current = frozen ? frozen.region : liveRegion
  const shownStatus = frozen ? frozen.status : regionStatus
  const currentKnown = shownStatus === 'ready'
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
    setFrozen({ region: current, status: regionStatus })
    let result: SaveRegionResult
    try {
      result = await saveRegion(selected, source)
    } catch {
      if (!active.current) return
      setFrozen(null)
      setStatus('failed')
      return
    }
    if (result.status === 'invalid') {
      if (!active.current) return
      setFrozen(null)
      setSelected(null)
      setStatus('invalid')
      return
    }
    // 목 프로필 · 실데이터 저장소에는 이미 바뀐 동네가 남았다. 이동만 화면이 떠 있을 때 한다
    if (!active.current) return
    setStatus('done')
    leaveNotice('region-changed')
    goBack(backHref)
  }

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
      onNotificationClick={() => router.push(notificationsHref(regionCode, searchParams))}
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
          {currentKnown
            ? (current?.name ?? '아직 정하지 않았어요')
            : shownStatus === 'failed'
              ? '불러오지 못했어요'
              : '불러오고 있어요'}
        </span>
        {shownStatus === 'failed' && (
          <Button
            variant="secondary"
            size="sm"
            className="mt-1.5 self-start"
            onClick={retryMemberInfo}
          >
            다시 시도
          </Button>
        )}
      </div>

      <DistrictSearchInput
        value={query}
        readOnly={busy}
        onChange={(value) => {
          setQuery(value)
          setSelected(null)
          if (status === 'failed' || status === 'invalid') setStatus('idle')
        }}
      />
      <DistrictOptionList
        legend="검색 결과"
        options={districtOptions(searchResultsOf(search))}
        selectedCode={selected?.code ?? null}
        onSelect={(district) => {
          if (busy) return
          setSelected(district)
          if (status === 'failed' || status === 'invalid') setStatus('idle')
        }}
      />
      {/* 검색 결과 알림. 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽으므로 늘 그려 둔다 */}
      <div role="status">
        <SearchNotices search={search} keyword={query.trim()} />
      </div>

      {status === 'failed' && (
        <AlertBox tone="danger">내 동네를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
      )}
      {status === 'invalid' && (
        <AlertBox tone="danger">이 동네는 고를 수 없어요. 다른 동네를 골라 주세요.</AlertBox>
      )}
    </AccountPageLayout>
  )
}
