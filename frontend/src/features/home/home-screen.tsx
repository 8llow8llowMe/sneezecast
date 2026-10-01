'use client'

import { AppHeader } from '@/components/app-header'
import { Button } from '@/components/button'
import { SectionBand } from '@/components/section'
import { TabBar } from '@/components/tab-bar'
import { ToastRegion, useToast } from '@/components/toast'

import { MapPlaceholder } from './map-placeholder'
import { NoticeSection } from './notice-section'
import { OfficialPanel, OfficialRow } from './official'
import { StatusCard } from './status-card'
import { SymptomTrends } from './symptom-trends'
import type { HomeWeekly } from './types'

/**
 * S03 홈. 폭에 따라 구성이 바뀐다 (시안 Home · Tablet · Desktop).
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 상태 카드 → 공식 정보 행 → 증상별 변화 → 동네 안내, 8px 띠로 구분. 아래 보고 버튼 + 탭바 |
 * | 태블릿 | 헤더 아래 공식 정보 행, 2열 격자(상태 카드 · 증상별 변화 · 동네 안내 · 공식 정보) + 지도 자리. 탭바 |
 * | 데스크톱 | 헤더 아래 공식 정보 행, 왼쪽 지도 자리 + 오른쪽 420 패널(상태 카드 · 증상별 변화 · 동네 안내) |
 *
 * 공식 정보 행은 모바일과 태블릿 이상에서 놓이는 자리가 달라 두 번 그리고 폭에 따라 하나만 보인다.
 * 숨긴 쪽은 `display: none` 이라 보조기술에도 한 번만 읽힌다.
 */
export function HomeScreen({ week }: { week: HomeWeekly }) {
  const { toast, show, dismiss } = useToast()

  // 보고(S05) · 동네 바꾸기(S02) · 알림 설정(S10) 화면이 생기면 각각 연결한다
  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })

  return (
    <div className="flex min-h-dvh flex-col">
      <h1 className="sr-only">{week.regionName} 이번 주 우리 동네 건강</h1>

      <AppHeader
        regionName={week.regionName}
        current="home"
        onRegionClick={() => notReady('동네 바꾸기')}
        onNotificationClick={() => notReady('알림 설정')}
        onReportClick={() => notReady('건강 보고')}
      />

      <p className="px-page-mobile text-sub text-fg-sub tablet:hidden">
        {week.weekLabel} · {week.updatedLabel}
      </p>

      <div className="hidden border-b border-divider tablet:block tablet:border-t desktop:border-t-0">
        <OfficialRow official={week.official} />
      </div>

      <main className="flex grow flex-col tablet:gap-7 tablet:p-6 desktop:flex-row desktop:gap-8 desktop:px-8">
        <div className="flex flex-col tablet:grid tablet:grid-cols-2 tablet:items-start tablet:gap-7 desktop:order-last desktop:flex desktop:w-105 desktop:shrink-0 desktop:gap-4">
          <StatusCard week={week} />

          <SectionBand className="tablet:hidden" />
          <div className="tablet:hidden">
            <OfficialRow official={week.official} />
          </div>
          <SectionBand className="tablet:hidden" />

          <SymptomTrends week={week} />

          <SectionBand className="tablet:hidden" />

          <NoticeSection notice={week.notice} />

          <div className="hidden tablet:block desktop:hidden">
            <OfficialPanel official={week.official} />
          </div>
        </div>

        <div className="hidden tablet:flex tablet:grow">
          <MapPlaceholder weekLabel={week.weekLabel} />
        </div>
      </main>

      {/* 모바일 · 태블릿 하단. 모바일만 보고 버튼이 있고(태블릿 · 데스크톱은 헤더에 있다) 탭바는 데스크톱에서 숨는다 */}
      <div className="sticky bottom-0 bg-bg">
        <div className="border-t border-divider px-page-mobile py-3 tablet:hidden">
          <Button fullWidth onClick={() => notReady('건강 보고')}>
            이번 주 건강 보고하기
          </Button>
        </div>
        <TabBar current="home" />
      </div>

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-40 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </div>
  )
}
