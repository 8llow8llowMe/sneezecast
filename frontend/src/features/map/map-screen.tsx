'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'

import { AppHeader } from '@/components/app-header'
import { TabBar } from '@/components/tab-bar'
import { ToastRegion, useToast } from '@/components/toast'
import { useMockAuth } from '@/features/auth/use-mock-auth'
import { ExplainSheet } from '@/features/home/explain-sheet'
import { useExplainParam } from '@/features/home/use-explain-param'
import { regionSearch, reportHrefFor } from '@/features/me/me-paths'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'

import { DistrictPanel } from './district-panel'
import { MapView } from './map-view'
import type { MapWeek } from './types'

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
 *   지도의 처음 고른 동네는 둘러보기 동네 · 목 예시 동네다(회원의 내 동네는 집계 API 연동 뒤).
 * - 알림 설정 · 행정동 찾기 · 내 동네로 설정은 아직 "준비하고 있어요" 알림이다. 내 동네로 설정은 회원 내 동네 저장과 붙이는 후속이다.
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
  const explain = useExplainParam()
  const { toast, show, dismiss } = useToast()
  const navSearch = regionSearch(regionCode)
  const openBrowseRegion = useBrowseRegion('/map', regionCode)

  const [selectedCode, setSelectedCode] = useState(map.mineCode)
  const [expanded, setExpanded] = useState(false)
  const selected =
    map.districts.find((district) => district.code === selectedCode) ?? map.districts[0] ?? null

  const notReady = (message: string) => show({ message })
  const select = (code: string) => {
    setSelectedCode(code)
    setExpanded(true)
  }

  return (
    <div className="flex min-h-dvh flex-col">
      <h1 className="sr-only">동네 지도</h1>

      {/* 모바일 시안에는 머리줄이 없다. 태블릿은 아래 구분선이 있다(데스크톱은 AppHeader 가 긋는다) */}
      <div className="hidden tablet:block tablet:border-b tablet:border-divider desktop:border-b-0">
        <AppHeader
          regionName={map.mineName}
          current="map"
          onRegionClick={openBrowseRegion}
          onNotificationClick={
            guest ? undefined : () => notReady('알림 설정 화면은 준비하고 있어요')
          }
          onReportClick={() => router.push(reportHrefFor(auth, regionCode))}
          reportLabel={guest ? '로그인하고 보고하기' : undefined}
          navSearch={navSearch}
        />
      </div>

      <main className="flex grow flex-col tablet:gap-5 tablet:px-6 tablet:py-5 desktop:flex-row desktop:gap-6 desktop:px-8 desktop:py-6">
        <MapView
          weekLabel={map.weekLabel}
          districts={map.districts}
          mineCode={map.mineCode}
          selectedCode={selected?.code ?? null}
          onSelect={select}
          onSearch={() => notReady('행정동 찾기는 준비하고 있어요')}
        />

        {selected && (
          <DistrictPanel
            district={selected}
            mine={selected.code === map.mineCode}
            expanded={expanded}
            onExpandedChange={setExpanded}
            onSetMine={() => notReady('내 동네로 설정은 준비하고 있어요')}
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

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-24 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </div>
  )
}
