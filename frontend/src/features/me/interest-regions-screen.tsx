'use client'

import { useEffect, useId, useRef, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { ToastRegion, useToast } from '@/components/toast'
import type { MockAuthState } from '@/features/auth/auth-client'
import { reloadMemberRegion } from '@/features/auth/member-info'
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

/** 읽는 중(null) · 목록 · 읽지 못함(다시 시도할 수 있다) */
type Load = null | { status: 'ready'; regions: readonly InterestRegion[] } | { status: 'failed' }

/** 보내기 실패. 더하기 · 빼기는 "잠시 뒤 다시", `invalid` 는 서버가 그 동네를 받지 않음(없는 코드 · 폐지) */
type Failed = 'add' | 'remove' | 'invalid' | null

/** 보내는 중인 동작. 더하기이거나 빼는 동네 코드다 — 한 번에 하나만 보낸다 */
type Busy = { kind: 'add' } | { kind: 'remove'; code: string } | null

/**
 * S10 관심 동네 (`/me/interest-regions`, #198). 회원만 본다(`useMemberGate`). **시안이 없어** 계정 화면(Settings-devices)의 틀과
 * 내 동네 바꾸기의 검색 조각(`district-picker.tsx`)을 쓴다.
 *
 * - 안내 → 고른 관심 동네 목록(이름 · 시군구 · `삭제`) 또는 빈 상태 → 동네 검색 · 결과 → `관심 동네로 더하기`.
 *   **위치 권한을 요청하지 않는다** — 행정동은 직접 고른다. 관심 동네는 보고 · 알림의 기준(내 동네)을 바꾸지 않는다
 * - 상한(`INTEREST_REGION_LIMIT`, 3곳 — 서버와 같음)이면 검색 대신 회색 상자로 하나를 지워야 더할 수 있다고 알린다.
 *   내 동네 · 이미 고른 동네를 고르면 버튼이 꺼지고 이유를 보인다(결과 목록에도 `내 동네` · `관심 동네` 로 적는다). 판단은 코드로 한다
 * - 폐지된 동네(`abolished`)는 이름 · 시군구 뒤에 `없어진 동네` 를 붙이고, 행정동 서비스가 모르는 동네(이름 null)는 이름 자리에
 *   `없어진 동네`, 시군구 자리에 `행정구역 개편으로 바뀌었어요` 를 보인다(지어낸 이름 · 코드를 보이지 않는다). 둘 다 `삭제` 는 그대로이고,
 *   하나라도 있으면 목록 아래 회색 상자로 지우고 새로 고르게 안내한다
 * - 시안에 확인 단계가 없는 로그인한 기기처럼 `삭제` 는 누르면 바로 보낸다. 더해서 상한에 닿으면 사라진 버튼 대신 목록 자리로 포커스를 옮긴다. 보내는 중에는 버튼이 모두 꺼지고 검색 칸이 읽기 전용이다.
 *   성공하면 알림(토스트)으로 알리고, 실패하면 빨강 상자로 알린다(목록 · 선택은 그대로, 다시 누를 수 있다). 서버가 그 동네를 받지
 *   않으면(`invalid`) 선택을 지우고 다른 동네를 고르라고 알린다(내 동네 바꾸기와 같다). 거절(상한 · 이미 있음 · 내 동네와 같음)이면
 *   서버 목록으로 맞추고 선택은 남겨 이유를 보인다 — 내 동네와 같음은 이 화면의 내 동네가 낡은 것이라, 실데이터면 회원 정보 저장소의
 *   내 동네를 다시 읽고(`reloadMemberRegion` — 보고가 옛 동네로 나가지 않게) 저장소 값이 바뀔 때까지 서버가 거절한 동네를 내 동네로
 *   삼는다. 다시 읽은 목록이 거절과 맞지 않거나 장애 뒤 목록에 그 동네가 없으면(`failed`) 목록을 맞추고 "더하지 못했어요" 로 알린다
 * - 이름을 모르는 줄이 둘 이상이면 `삭제` 이름에 순번을 붙인다(`없어진 동네 1 삭제`, 하나뿐이면 없음)
 * - 목록은 실데이터면 `GET /api/v1/members/me/interest-regions` 다(#237). 읽는 동안 "불러오고 있어요", 읽지 못하면 빨강 상자 +
 *   `다시 시도` 이고 검색 · 버튼을 두지 않는다(로그인한 기기와 같은 모양). 목은 목 회원의 목록이고 `?mock-interest-regions=` 로 재현한다
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
  const memberHomeCode = useMemberRegion()?.code ?? null
  // 서버가 내 동네와 같다고 거절한 코드(`home`)와 그때 이 화면이 알던 내 동네. 이 화면의 내 동네가 낡았으면(다른 탭 · 기기에서 바꿈)
  // 저장소를 다시 읽는 동안 서버 쪽을 따르고, 저장소 값이 바뀌면(다시 읽음 · 다른 저장) 저장소 값을 따른다
  const [serverHome, setServerHome] = useState<{ code: string; over: string | null } | null>(null)
  const homeCode =
    serverHome && serverHome.over === memberHomeCode ? serverHome.code : memberHomeCode
  const scenario = parseMockInterestScenario(searchParams.get(MOCK_INTEREST_REGIONS_PARAM))
  const [load, setLoad] = useState<Load>(null)
  const [query, setQuery] = useState('')
  const search = useDistrictSearch(query)
  const [selected, setSelected] = useState<District | null>(null)
  const [busy, setBusy] = useState<Busy>(null)
  const [failed, setFailed] = useState<Failed>(null)
  // 다시 시도할 때 올린다(목록을 다시 읽는다)
  const [loadKey, setLoadKey] = useState(0)
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let live = true
    // 늦게 온 응답(화면을 떠남 · 출처나 재현이 바뀜 · 다시 시도)은 버린다. 목은 실패하지 않는다
    listInterestRegions(source, { scenario }).then(
      (regions) => {
        if (live) setLoad({ status: 'ready', regions })
      },
      () => {
        if (live) setLoad({ status: 'failed' })
      },
    )
    return () => {
      live = false
    }
  }, [source, scenario, loadKey])

  function retryLoad() {
    setLoad(null)
    setLoadKey((key) => key + 1)
  }

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
      if (result.status === 'invalid') {
        // 서버가 그 동네를 받지 않았다(없는 코드 · 폐지). 다른 동네를 고르게 한다
        setSelected(null)
        setFailed('invalid')
        return
      }
      // 거절(이미 있음 · 상한 · 내 동네와 같음) · 실패여도 서버 목록으로 맞춘다 — 선택은 남아 이유가 보인다
      setLoad({ status: 'ready', regions: result.regions })
      if (result.status === 'failed') {
        // 거절이 다시 읽은 목록과 맞지 않거나 장애 뒤 목록에 없다. 다시 누를 수 있다
        setFailed('add')
        return
      }
      if (result.status === 'home' && district.code !== memberHomeCode) {
        setServerHome({ code: district.code, over: memberHomeCode })
        // 회원 정보 저장소의 내 동네가 낡았다. 보고가 옛 동네로 나가지 않게 다시 읽는다(목은 목 프로필이 곧 서버라 어긋나지 않는다)
        if (source === 'api') reloadMemberRegion()
      }
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
      const regions = await removeInterestRegion(region.code, source)
      if (!active.current) return
      setBusy(null)
      setLoad({ status: 'ready', regions })
      const name = regionTitle(region)
      show({ message: `${name}${withEulReul(name)} 관심 동네에서 뺐어요` })
      // 누른 버튼이 목록에서 사라진다. 포커스가 문서 처음으로 튀지 않게 목록 자리로 옮긴다
      listRef.current?.focus()
    } catch {
      if (!active.current) return
      setBusy(null)
      setFailed('remove')
    }
  }

  const canAdd = load?.status === 'ready' && !full
  const hasAbolished = regions.some((region) => region.abolished)
  const clearAddFailure = () => {
    if (failed === 'add' || failed === 'invalid') setFailed(null)
  }

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
      <p className="text-body leading-[1.6] text-fg-sub">
        내 동네 말고 지켜볼 동네를 {INTEREST_REGION_LIMIT}곳까지 고를 수 있어요. 행정동을 직접 골라
        주세요. 위치 정보는 사용하지 않아요.
      </p>

      {/*
        목록 자리. 불러오는 중 안내 영역(role=status)은 스크린리더가 이미 있던 영역의 내용이 바뀔 때 읽으므로 늘 그려 둔다.
        비어 있으면 높이가 없다
      */}
      <div role="status">
        {load === null && <p className="text-body text-fg-sub">관심 동네를 불러오고 있어요</p>}
      </div>

      {load?.status === 'failed' && (
        <AlertBox
          tone="danger"
          action={
            <Button variant="secondary" size="sm" className="self-start" onClick={retryLoad}>
              다시 시도
            </Button>
          }
        >
          관심 동네를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </AlertBox>
      )}

      {load?.status === 'ready' && (
        <div ref={listRef} tabIndex={-1} className="flex flex-col focus:outline-none">
          {regions.length > 0 ? (
            <ul aria-label="관심 동네 목록" className="flex flex-col border-t border-divider">
              {withRemoveLabels(regions).map(({ region, removeLabel }) => (
                <RegionRow
                  key={region.code}
                  region={region}
                  removeLabel={removeLabel}
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

      {hasAbolished && (
        <AlertBox tone="neutral">
          행정구역 개편으로 없어진 동네가 있어요. 삭제하고 새 동네를 골라 주세요.
        </AlertBox>
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
              clearAddFailure()
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
              clearAddFailure()
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
          {failed === 'invalid' && (
            <AlertBox tone="danger">이 동네는 고를 수 없어요. 다른 동네를 골라 주세요.</AlertBox>
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

/** 행정동 서비스가 모르는 동네(이름 null)의 이름 자리. 지어낸 이름 · 코드를 보이지 않는다 */
const UNKNOWN_REGION_TITLE = '없어진 동네'

/** 목록 한 줄의 이름 */
function regionTitle(region: InterestRegion): string {
  return region.name ?? UNKNOWN_REGION_TITLE
}

/**
 * 줄마다 `삭제` 버튼의 접근 이름(`○○동 삭제`). 이름을 모르는 줄이 둘 이상이면 이름이 겹치지 않게 그 줄들에만 순번을 붙인다
 * (`없어진 동네 1 삭제` — 지어낸 이름 · 코드 대신). 하나뿐이면 붙이지 않는다
 */
function withRemoveLabels(
  regions: readonly InterestRegion[],
): { region: InterestRegion; removeLabel: string }[] {
  const unknownCount = regions.filter((region) => region.name === null).length
  let unknownSeen = 0
  return regions.map((region) => {
    if (region.name !== null || unknownCount < 2) {
      return { region, removeLabel: `${regionTitle(region)} 삭제` }
    }
    unknownSeen += 1
    return { region, removeLabel: `${UNKNOWN_REGION_TITLE} ${unknownSeen} 삭제` }
  })
}

/** 목록 한 줄의 설명. 시군구에 폐지 · 내 동네를 붙인다. 이름을 모르면 시군구 자리에 개편 안내다 */
function regionDetail(region: InterestRegion, isHome: boolean): string {
  const parts =
    region.name === null
      ? ['행정구역 개편으로 바뀌었어요']
      : [region.sigungu, region.abolished ? '없어진 동네' : null]
  if (isHome) parts.push('내 동네')
  return parts.filter((part): part is string => Boolean(part)).join(' · ')
}

function RegionRow({
  region,
  removeLabel,
  isHome,
  disabled,
  onRemove,
}: {
  region: InterestRegion
  /** `삭제` 버튼의 접근 이름. 보이는 글자("삭제")를 끝에 둔다 */
  removeLabel: string
  /** 내 동네를 이 관심 동네로 바꿨다(서버는 관심 동네를 그대로 둔다) */
  isHome: boolean
  disabled: boolean
  onRemove: () => void
}) {
  const detailId = useId()
  const title = regionTitle(region)
  return (
    <li className="flex min-h-17 items-center justify-between gap-3 border-b border-divider">
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="text-body font-semibold text-fg">{title}</span>
        <span id={detailId} className="text-sub text-fg-sub">
          {regionDetail(region, isHome)}
        </span>
      </span>
      <button
        type="button"
        // 버튼이 여러 개라 동네 이름을 이름에 붙인다. 보이는 글자("삭제")를 이름 끝에 그대로 둔다.
        // 이름이 같은 동(신사동 — 강남구 · 관악구)을 가리게 시군구 줄을 설명으로 잇는다
        aria-label={removeLabel}
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
