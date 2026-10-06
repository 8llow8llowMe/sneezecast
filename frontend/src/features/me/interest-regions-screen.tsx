'use client'

import { useEffect, useId, useRef, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { ToastRegion, useToast } from '@/components/toast'
import type { MockAuthState } from '@/features/auth/auth-client'
import { reportButtonLabel } from '@/features/home/report-gate'
import {
  DistrictOptionList,
  DistrictSearchInput,
  SearchNotices,
  searchResultsOf,
} from '@/features/onboarding/district-picker'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import {
  addInterestRegion,
  INTEREST_REGION_LIMIT,
  type InterestRegion,
  listInterestRegions,
  MOCK_INTEREST_REGIONS_PARAM,
  parseMockInterestScenario,
  removeInterestRegion,
} from '@/features/region/interest-region-client'
import type { District } from '@/features/region/types'
import { useDistrictSearch } from '@/features/region/use-district-search'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { withEulReul } from '@/lib/korean'
import { navHref } from '@/lib/nav'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import { AccountPageLayout } from './account-page-layout'
import {
  ME_INTEREST_REGIONS_PATH,
  ME_PATH,
  meSearch,
  notificationsHref,
  regionSearch,
  reportHrefFor,
} from './me-paths'
import { useMeTrail } from './me-trail'
import { useMemberGate } from './member-gate'
import { useMemberRegion, useShownRegionName } from './member-region'

/** 읽기 전(null) · 목록 · 실데이터라 아직 저장할 수 없음 */
type Load =
  null | { status: 'ready'; regions: readonly InterestRegion[] } | { status: 'unavailable' }

/** 보내는 중인 동작. 더하기이거나 빼는 동네 코드다 — 한 번에 하나만 보낸다 */
type Busy = { kind: 'add' } | { kind: 'remove'; code: string } | null

/**
 * S10 관심 동네 (`/me/interest-regions`, #198). 회원만 본다(`useMemberGate`). **시안이 없어** 계정 화면(Settings-devices)의 틀과
 * 내 동네 바꾸기의 검색 조각(`district-picker.tsx`)을 쓴다.
 *
 * - 안내 → 고른 관심 동네 목록(이름 · 시군구 · `삭제`) 또는 빈 상태 → 동네 검색 · 결과 → `관심 동네로 더하기`.
 *   **위치 권한을 요청하지 않는다** — 행정동은 직접 고른다. 관심 동네는 보고 · 알림의 기준(내 동네)을 바꾸지 않는다
 * - 상한(`INTEREST_REGION_LIMIT`, 임시 3곳)이면 검색 대신 회색 상자로 하나를 지워야 더할 수 있다고 알린다.
 *   내 동네 · 이미 고른 동네를 고르면 버튼이 꺼지고 이유를 보인다(결과 목록에도 `내 동네` · `관심 동네` 로 적는다)
 * - 시안에 확인 단계가 없는 로그인한 기기처럼 `삭제` 는 누르면 바로 보낸다. 더해서 상한에 닿으면 사라진 버튼 대신 목록 자리로 포커스를 옮긴다. 보내는 중에는 버튼이 모두 꺼지고 검색 칸이 읽기 전용이다.
 *   성공하면 알림(토스트)으로 알리고, 실패하면 빨강 상자로 알린다(목록 · 선택은 그대로, 다시 누를 수 있다)
 * - **실데이터는 관심 동네 API 가 없어(BE 미정) 저장하지 않는다** — 서버에 저장된 것처럼 보이지 않게 안내 · 목록 · 검색 없이 회색 상자로
 *   아직 저장할 수 없다고 알린다(최근 보고 내역의 지난 보고와 같은 결). 목은 목 회원의 목록이고 `?mock-interest-regions=` 로 재현한다
 */
export function InterestRegionsScreen({
  regionName,
  regionCode = null,
}: {
  /** 데스크톱 머리줄의 동네 이름(서버가 준 둘러보기 동네 · 목 예시) */
  regionName: string
  /** 둘러보기(`?region=`)로 고른 행정동 코드 */
  regionCode?: string | null
}) {
  const auth = useMemberGate({ next: ME_INTEREST_REGIONS_PATH })
  if (!auth) return null
  return <InterestRegions auth={auth} regionName={regionName} regionCode={regionCode} />
}

function InterestRegions({
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
  const { goBack } = useMeTrail()
  const active = useActiveRef()
  const source = useDataSource()
  const { toast, show, dismiss } = useToast()
  const openBrowseRegion = useBrowseRegion(ME_INTEREST_REGIONS_PATH, regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)
  const homeCode = useMemberRegion()?.code ?? null
  const scenario = parseMockInterestScenario(searchParams.get(MOCK_INTEREST_REGIONS_PARAM))
  const [load, setLoad] = useState<Load>(null)
  const [query, setQuery] = useState('')
  const search = useDistrictSearch(query)
  const [selected, setSelected] = useState<District | null>(null)
  const [busy, setBusy] = useState<Busy>(null)
  const [failed, setFailed] = useState<'add' | 'remove' | null>(null)
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let live = true
    // 목은 실패하지 않고 실데이터는 요청하지 않는다. 연동 때 불러오는 중 · 실패 상태를 더한다
    void listInterestRegions(source, { scenario }).then((result) => {
      if (live) setLoad(result)
    })
    return () => {
      live = false
    }
  }, [source, scenario])

  const regions = load?.status === 'ready' ? load.regions : []
  const full = regions.length >= INTEREST_REGION_LIMIT
  const blockedBy = (district: District): 'home' | 'chosen' | null =>
    district.code === homeCode
      ? 'home'
      : regions.some((region) => region.code === district.code)
        ? 'chosen'
        : null
  const reason = selected ? blockedBy(selected) : null
  const addBlocked = !selected || reason !== null || full || busy !== null
  const backHref = navHref(ME_PATH, meSearch(regionCode, searchParams))

  async function add() {
    if (addBlocked || !selected) return
    const district = selected
    setBusy({ kind: 'add' })
    setFailed(null)
    try {
      const result = await addInterestRegion(district, source)
      if (!active.current) return
      setBusy(null)
      if (result.status === 'unavailable') return
      // 거절(이미 있음 · 상한)이어도 서버 목록으로 맞춘다 — 선택은 남아 이유가 보인다
      setLoad({ status: 'ready', regions: result.regions })
      if (result.status === 'ok') {
        setQuery('')
        setSelected(null)
        show({ message: `${district.name}${withEulReul(district.name)} 관심 동네에 더했어요` })
      }
      // 상한에 닿으면 누른 버튼 · 검색 칸이 사라진다. 포커스가 문서 처음으로 튀지 않게 목록 자리로 옮긴다
      if (result.regions.length >= INTEREST_REGION_LIMIT) listRef.current?.focus()
    } catch {
      if (!active.current) return
      setBusy(null)
      setFailed('add')
    }
  }

  async function remove(region: InterestRegion) {
    if (busy) return
    setBusy({ kind: 'remove', code: region.code })
    setFailed(null)
    try {
      const result = await removeInterestRegion(region.code, source)
      if (!active.current) return
      setBusy(null)
      if (result.status === 'unavailable') return
      setLoad(result)
      show({ message: `${region.name}${withEulReul(region.name)} 관심 동네에서 뺐어요` })
      // 누른 버튼이 목록에서 사라진다. 포커스가 문서 처음으로 튀지 않게 목록 자리로 옮긴다
      listRef.current?.focus()
    } catch {
      if (!active.current) return
      setBusy(null)
      setFailed('remove')
    }
  }

  const canAdd = load?.status === 'ready' && !full

  return (
    <AccountPageLayout
      title="관심 동네"
      regionName={shownRegionName}
      navSearch={regionSearch(regionCode)}
      onBack={() => goBack(backHref)}
      onRegionClick={openBrowseRegion}
      onNotificationClick={() => router.push(notificationsHref(regionCode, searchParams))}
      onReportClick={() => router.push(reportHrefFor(auth, regionCode))}
      reportLabel={reportLabel}
      footer={
        canAdd ? (
          <Button fullWidth aria-disabled={addBlocked || undefined} onClick={() => void add()}>
            관심 동네로 더하기
          </Button>
        ) : null
      }
    >
      {/* 실데이터(저장할 수 없음)에서는 고를 수 있다는 안내를 두지 않는다 — 아래 회색 상자와 서로 다른 말을 하지 않게 */}
      {load?.status === 'ready' && (
        <p className="text-body leading-[1.6] text-fg-sub">
          내 동네 말고 지켜볼 동네를 {INTEREST_REGION_LIMIT}곳까지 고를 수 있어요. 행정동을 직접
          골라 주세요. 위치 정보는 사용하지 않아요.
        </p>
      )}

      {load?.status === 'unavailable' && (
        <AlertBox tone="neutral">
          관심 동네는 아직 저장할 수 없어요. 준비되면 여기에서 고를 수 있어요.
        </AlertBox>
      )}

      {load?.status === 'ready' && (
        <div ref={listRef} tabIndex={-1} className="flex flex-col focus:outline-none">
          {regions.length > 0 ? (
            <ul aria-label="관심 동네 목록" className="flex flex-col border-t border-divider">
              {regions.map((region) => (
                <RegionRow
                  key={region.code}
                  region={region}
                  isHome={region.code === homeCode}
                  disabled={busy !== null}
                  onRemove={() => void remove(region)}
                />
              ))}
            </ul>
          ) : (
            <div className="flex flex-col items-center gap-2 px-3 py-6 text-center">
              <p className="text-section-title font-semibold text-fg">
                아직 고른 관심 동네가 없어요
              </p>
              <p className="text-body-strong leading-[1.55] text-fg-sub">
                아래에서 동네를 찾아 더해 주세요.
              </p>
            </div>
          )}
        </div>
      )}

      {failed === 'remove' && (
        <AlertBox tone="danger">관심 동네를 빼지 못했어요. 잠시 뒤 다시 시도해 주세요.</AlertBox>
      )}

      {load?.status === 'ready' && full && (
        <AlertBox tone="neutral">
          관심 동네를 {INTEREST_REGION_LIMIT}곳 모두 골랐어요. 다른 동네를 더하려면 하나를 삭제해
          주세요.
        </AlertBox>
      )}

      {canAdd && (
        <>
          <DistrictSearchInput
            value={query}
            readOnly={busy !== null}
            onChange={(value) => {
              setQuery(value)
              setSelected(null)
              if (failed === 'add') setFailed(null)
            }}
          />
          <DistrictOptionList
            legend="검색 결과"
            options={searchResultsOf(search).map((district) => ({
              district,
              detail: optionDetail(district, blockedBy(district)),
            }))}
            selectedCode={selected?.code ?? null}
            onSelect={(district) => {
              if (busy) return
              setSelected(district)
              if (failed === 'add') setFailed(null)
            }}
          />
          {/* 검색 결과 알림. 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽으므로 늘 그려 둔다 */}
          <div role="status">
            <SearchNotices search={search} keyword={query.trim()} />
            {reason && (
              <p className="text-body text-fg-sub">
                {reason === 'home'
                  ? '내 동네는 이미 지켜보고 있어요. 다른 동네를 골라 주세요.'
                  : '이미 고른 관심 동네예요. 다른 동네를 골라 주세요.'}
              </p>
            )}
          </div>
          {failed === 'add' && (
            <AlertBox tone="danger">
              관심 동네를 더하지 못했어요. 잠시 뒤 다시 시도해 주세요.
            </AlertBox>
          )}
        </>
      )}

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-36 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </AccountPageLayout>
  )
}

/** 결과 한 줄의 설명. 시군구에 고를 수 없는 이유를 붙인다 */
function optionDetail(district: District, blocked: 'home' | 'chosen' | null): string {
  if (blocked === 'home') return `${district.sigungu} · 내 동네`
  if (blocked === 'chosen') return `${district.sigungu} · 관심 동네`
  return district.sigungu
}

function RegionRow({
  region,
  isHome,
  disabled,
  onRemove,
}: {
  region: InterestRegion
  /** 관심 동네로 고른 뒤 내 동네를 이 동네로 바꿨다 */
  isHome: boolean
  disabled: boolean
  onRemove: () => void
}) {
  const detailId = useId()
  return (
    <li className="flex min-h-17 items-center justify-between gap-3 border-b border-divider">
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="text-body font-semibold text-fg">{region.name}</span>
        <span id={detailId} className="text-sub text-fg-sub">
          {isHome ? `${region.sigungu} · 내 동네` : region.sigungu}
        </span>
      </span>
      <button
        type="button"
        // 버튼이 여러 개라 동네 이름을 이름에 붙인다. 보이는 글자("삭제")를 이름 끝에 그대로 둔다.
        // 이름이 같은 동(신사동 — 강남구 · 관악구)을 가리게 시군구 줄을 설명으로 잇는다
        aria-label={`${region.name} 삭제`}
        aria-describedby={detailId}
        aria-disabled={disabled || undefined}
        onClick={() => {
          if (!disabled) onRemove()
        }}
        className="min-h-touch shrink-0 cursor-pointer rounded-button border-hairline border-inactive-bar bg-bg px-3.5 text-body-strong font-semibold text-fg aria-disabled:cursor-not-allowed aria-disabled:opacity-disabled"
      >
        삭제
      </button>
    </li>
  )
}
