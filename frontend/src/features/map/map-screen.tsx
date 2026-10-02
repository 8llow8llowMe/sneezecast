'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'

import { AppHeader } from '@/components/app-header'
import { TabBar } from '@/components/tab-bar'
import { ToastRegion, useToast } from '@/components/toast'
import { type MemberRegion, saveRegion, type SaveRegionResult } from '@/features/auth/auth-client'
import { loginHref } from '@/features/auth/login-return'
import { useMockAuth } from '@/features/auth/use-mock-auth'
import { ExplainSheet } from '@/features/home/explain-sheet'
import { reportButtonLabel } from '@/features/home/report-gate'
import { useExplainParam } from '@/features/home/use-explain-param'
import { CONFIRM_PARAM } from '@/features/me/confirm'
import { ConfirmModal } from '@/features/me/confirm-dialog'
import { regionSearch, reportHrefFor } from '@/features/me/me-paths'
import { useMemberRegion, useShownRegionName } from '@/features/me/member-region'
import { HOME_PATH, LOGIN_PATH } from '@/features/onboarding/paths'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { withEulReul } from '@/lib/korean'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'
import { useModalParam } from '@/lib/use-modal-param'

import { DistrictPanel } from './district-panel'
import { MapView } from './map-view'
import type { MapWeek } from './types'

/** `내 동네로 설정` 확인 대화상자의 쿼리 값 (`?confirm=set-mine`, 내 정보의 확인 대화상자와 같은 키) */
export const SET_MINE_CONFIRM = 'set-mine'

/** 내 동네를 저장하지 못했을 때 문구. 내 동네 바꾸기(`/me/region`)와 같다 */
const SET_MINE_FAILURE = '내 동네를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.'

/** 서버가 그 동네를 받지 않을 때(없는 코드 · 폐지 — `saveRegion` 의 `invalid`) 문구 */
const SET_MINE_INVALID = '이 동네는 내 동네로 설정할 수 없어요. 다른 동네를 골라 주세요.'

/**
 * S04 지도. 동네별 이번 주 상태를 지도에서 고르고 고른 동네 정보를 본다 (시안 Map-collapsed · Map-expanded · Map-nodata, -T · -D).
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 머리줄 없음. 지도(화면 폭) → 동네 정보 시트(접힘 · 펼침) → 탭바 |
 * | 태블릿 | 머리줄(동네 · 알림 · 보고 버튼, 아래 구분선) → 지도 → 펼친 동네 정보 → 탭바 |
 * | 데스크톱 | 홈과 같은 머리줄(지금 메뉴 `지도`) → 왼쪽 지도 + 오른쪽 400 동네 정보 |
 *
 * - **지도 그림만 임시다**(`MapView`). 행정동 경계 · 지도 라이브러리 · 집계 API 가 붙으면 목록 대신 칠한 지도로 바꾼다.
 * - 처음에는 처음 고른 동네(내 동네 · 둘러보기 동네)를 접어서 보인다. 다른 동네를 고르면 펼친다(Map-expanded).
 * - 동네는 사용자가 고른다 — 위치 권한을 묻지 않는다. 공식 정보(질병관리청)는 이 화면에 섞지 않는다.
 * - 둘러보기 동네(`regionCode`)는 탭바 · 메뉴 · 보고 진입 · 동네 안내 링크에 남긴다(다른 화면과 같다).
 * - 머리줄 동네 이름은 둘러볼 동네 고르기(`/browse/region?next=/map`)로 가고, 고르면 `/map?region=<새 코드>` 로 돌아온다(#141).
 *   보이는 이름은 다른 화면과 같은 `useShownRegionName`(둘러보기 동네 → 회원의 내 동네 → 목 예시)이다.
 *   지도의 처음 고른 동네는 둘러보기 동네 · 목 예시 동네다(회원의 내 동네로 두는 것은 집계 API 연동 뒤 — 지도 자료를 서버가 만든다).
 * - **내 동네**(`(내 동네)` 표시 · `내 동네로 설정` 이 없는 동네): 회원은 프로필의 내 동네(`useMemberRegion`), 비회원은 처음 고른 동네다.
 * - **내 동네로 설정**(#145): 비회원은 로그인(돌아올 곳 홈 · 고른 동네를 `region` 으로)으로 간다. 회원은 확인 대화상자(`?confirm=set-mine`,
 *   기록을 쌓음)를 열고, 확인하면 고른 동네를 내 동네로 저장(`saveRegion`)한 뒤 대화상자를 닫고 알림으로 알린다. 버튼이 사라지므로
 *   포커스는 동네 이름 제목으로 옮긴다. 저장하는 중에는
 *   두 버튼 · 닫기를 막고, 실패하면 대화상자 안 빨강 상자로 알린다(뒤로 가기로 이미 닫았으면 알림) — 내 정보의 확인 대화상자와 같다.
 *   서버가 그 동네를 받지 않으면(`invalid` — 없는 코드 · 폐지) 같은 자리에 "설정할 수 없어요" 로 알린다.
 *   주소로 바로 들어온 값은 회원이 내 동네가 아닌 동네를 골랐을 때만 연다(아니면 열지 않고 두기만 한다 — 판단 기준 `?explain=` 과 같다).
 * - 알림 설정 · 행정동 찾기는 아직 "준비하고 있어요" 알림이다.
 */
export function MapScreen({
  map,
  regionCode = null,
}: {
  map: MapWeek
  /** 둘러보기(`?region=`)로 고른 행정동 코드. `app/map/page.tsx` 가 아는 코드일 때만 넘긴다 */
  regionCode?: string | null
}) {
  const router = useRouter()
  const auth = useMockAuth()
  const guest = auth === 'guest'
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)
  const explain = useExplainParam()
  const { toast, show, dismiss } = useToast()
  const navSearch = regionSearch(regionCode)
  const openBrowseRegion = useBrowseRegion('/map', regionCode)
  const shownRegionName = useShownRegionName(map.mineName, regionCode)
  const memberRegion = useMemberRegion()
  const confirm = useModalParam(CONFIRM_PARAM)
  const active = useActiveRef()
  const source = useDataSource()

  const [selectedCode, setSelectedCode] = useState(map.mineCode)
  const [expanded, setExpanded] = useState(false)
  // 저장하는 중인 동네. 응답이 올 때까지 대화상자의 동네로 남긴다
  const [saving, setSaving] = useState<MemberRegion | null>(null)
  // 대화상자 안 빨강 상자 문구. 없으면 null
  const [failure, setFailure] = useState<string | null>(null)
  const selected =
    map.districts.find((district) => district.code === selectedCode) ?? map.districts[0] ?? null

  // 회원의 내 동네는 프로필의 동네(모르면 없음), 비회원은 처음 고른 동네(둘러보기 · 목 예시)다
  const mineCode = guest ? map.mineCode : (memberRegion?.code ?? null)
  const settable =
    !guest && selected && selected.code !== mineCode
      ? { code: selected.code, name: selected.week.regionName }
      : null
  const confirmTarget = saving ?? settable
  const confirmOpen = confirm.value === SET_MINE_CONFIRM && confirmTarget !== null
  // 늦게 온 응답이 대화상자가 아직 열려 있는지 볼 수 있게 둔다(저장하는 중에 뒤로 가기로 닫았을 수 있다)
  const confirmOpenRef = useRef(confirmOpen)
  useEffect(() => {
    confirmOpenRef.current = confirmOpen
  }, [confirmOpen])

  // 저장에 성공하면 누른 버튼이 사라지므로 포커스를 동네 이름 제목으로 옮긴다. 대화상자가 닫히며 포커스를 (사라진) 버튼에
  // 돌려준 뒤여야 한다 — 닫힌 그림의 effect 에서 옮긴다. 자식(Modal)의 `dialog.close()` effect 가 이 effect 보다 먼저 돈다
  const nameRef = useRef<HTMLHeadingElement>(null)
  const focusNameRef = useRef(false)
  useEffect(() => {
    if (!focusNameRef.current || confirmOpen) return
    focusNameRef.current = false
    nameRef.current?.focus({ preventScroll: true })
  })

  const notReady = (message: string) => show({ message })
  const select = (code: string) => {
    setSelectedCode(code)
    setExpanded(true)
  }

  function onSetMine() {
    if (guest) {
      // 고른 동네를 내 동네로 하려던 것이라 로그인 뒤 그 동네 홈이 보이게 고른 동네를 싣는다(없으면 둘러보기 동네).
      // 지도는 로그인 돌아올 곳(허용 목록) 밖이라 홈으로 돌아온다
      const region = selected?.code ?? regionCode
      router.push(loginHref(LOGIN_PATH, { next: HOME_PATH, region, intent: null }))
      return
    }
    setFailure(null)
    confirm.open(SET_MINE_CONFIRM)
  }

  function closeConfirm() {
    if (saving) return
    setFailure(null)
    confirm.close()
  }

  async function saveMine() {
    if (saving || !confirmTarget) return
    const region = confirmTarget
    setSaving(region)
    setFailure(null)
    let result: SaveRegionResult | null
    try {
      result = await saveRegion(region, source)
    } catch {
      result = null
    }
    if (result?.status !== 'ok') {
      if (!active.current) return
      setSaving(null)
      const message = result === null ? SET_MINE_FAILURE : SET_MINE_INVALID
      if (confirmOpenRef.current) setFailure(message)
      else show({ message })
      return
    }
    // 목 프로필 · 실데이터 저장소에는 이미 바뀐 동네가 남았다. 대화상자 닫기 · 알림만 화면이 떠 있을 때 한다
    if (!active.current) return
    setSaving(null)
    focusNameRef.current = true
    if (confirmOpenRef.current) confirm.close()
    show({ message: `${region.name}${withEulReul(region.name)} 내 동네로 설정했어요` })
  }

  return (
    <div className="flex min-h-dvh flex-col">
      <h1 className="sr-only">동네 지도</h1>

      {/* 모바일 시안에는 머리줄이 없다. 태블릿은 아래 구분선이 있다(데스크톱은 AppHeader 가 긋는다) */}
      <div className="hidden tablet:block tablet:border-b tablet:border-divider desktop:border-b-0">
        <AppHeader
          regionName={shownRegionName}
          current="map"
          onRegionClick={openBrowseRegion}
          onNotificationClick={
            guest ? undefined : () => notReady('알림 설정 화면은 준비하고 있어요')
          }
          onReportClick={() => router.push(reportHrefFor(auth, regionCode))}
          reportLabel={reportLabel}
          navSearch={navSearch}
        />
      </div>

      <main className="flex grow flex-col tablet:gap-5 tablet:px-6 tablet:py-5 desktop:flex-row desktop:gap-6 desktop:px-8 desktop:py-6">
        <MapView
          weekLabel={map.weekLabel}
          districts={map.districts}
          mineCode={mineCode}
          selectedCode={selected?.code ?? null}
          onSelect={select}
          onSearch={() => notReady('행정동 찾기는 준비하고 있어요')}
        />

        {selected && (
          <DistrictPanel
            district={selected}
            mine={selected.code === mineCode}
            expanded={expanded}
            onExpandedChange={setExpanded}
            onSetMine={onSetMine}
            nameRef={nameRef}
            onExplain={explain.openExplain}
            navSearch={navSearch}
          />
        )}
      </main>

      <TabBar current="map" navSearch={navSearch} className="sticky bottom-0" />

      {/* 판단 기준(S11)은 고른 동네가 수치가 있을 때만 연다. 자료 부족이면 ?explain=1 로 들어와도 열지 않는다 */}
      {selected && selected.week.status !== 'insufficient' && (
        <ExplainSheet week={selected.week} open={explain.open} onClose={explain.closeExplain} />
      )}

      {/* 내 동네로 설정 확인. 시안이 없어 내 정보의 확인 대화상자 모양을 따른다 */}
      <ConfirmModal
        open={confirmOpen}
        title={
          confirmTarget
            ? `${confirmTarget.name}${withEulReul(confirmTarget.name)} 내 동네로 설정할까요?`
            : '내 동네로 설정할까요?'
        }
        items={[
          '보고와 알림은 내 동네를 기준으로 해요.',
          '내 정보의 보고 동네에서 다시 바꿀 수 있어요.',
        ]}
        action="설정하기"
        pending={saving !== null}
        failure={failure}
        onConfirm={() => void saveMine()}
        onClose={closeConfirm}
      />

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-24 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </div>
  )
}
