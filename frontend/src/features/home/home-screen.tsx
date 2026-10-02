'use client'

import { useEffect } from 'react'

import { AlertBox } from '@/components/alert-box'
import { AppHeader } from '@/components/app-header'
import { Button } from '@/components/button'
import { OfflineNotice } from '@/components/offline-notice'
import { SectionBand } from '@/components/section'
import { TabBar } from '@/components/tab-bar'
import { ToastRegion, useToast } from '@/components/toast'
import { HealthConsentSheet } from '@/features/auth/health-consent-sheet'
import { LoginSheet } from '@/features/auth/login-sheet'
import { MOCK_AUTH_PARAM, useMockAuth } from '@/features/auth/use-mock-auth'
import { takeHomeNotice } from '@/features/me/leave-notice'
import { useRequiredStepsGate } from '@/features/me/member-gate'
import { useMemberRegion } from '@/features/me/member-region'
import { HOME_PATH } from '@/features/onboarding/paths'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { REPORT_PARAM, ReportFlow } from '@/features/report/report-flow'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { useModalParam } from '@/lib/use-modal-param'
import { useOnline } from '@/lib/use-online'

import { ExplainSheet } from './explain-sheet'
import { MapPlaceholder } from './map-placeholder'
import { NoticeSection } from './notice-section'
import { officialHref, OfficialPanel, OfficialRow } from './official'
import { HOME_TOP_NOTICE_CLASS, PushInappNotice } from './push-inapp-notice'
import {
  guardReportEntry,
  REPORT_GATE,
  reportButtonLabel,
  reportDone,
  reportEntryFor,
} from './report-gate'
import { StatusCard } from './status-card'
import { SymptomTrends } from './symptom-trends'
import type { HomeWeekly } from './types'
import { useExplainParam } from './use-explain-param'

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
 *
 * **보고 진입은 회원 · 동의 상태(목, `useMockAuth`)로 나뉜다** (`report-gate.ts`). 머리줄 · 하단 보고 버튼이 같은 함수를 부른다.
 * 비회원은 로그인 안내 시트(`?report=login`), 동의하지 않은 회원은 증상 보고 동의 시트(`?report=health-consent`),
 * 동의한 회원은 보고 흐름(`?report=start`)이 열린다. 주소로 바로 들어온 값이 상태에 맞지 않으면 맞는 시트로 바꾼다(replace).
 * 보고 흐름과 보낸 보고는 동의한 회원에게만 그린다.
 *
 * 보고 버튼 글자는 `reportButtonLabel` 이 정한다: 비회원 "로그인하고 보고하기", 회원 "이번 주 건강 보고하기", 동의한 회원이 이번 주 보고를
 * 보낸 뒤 "이번 주 보고 완료 · 수정하기"(하단 버튼은 회색 보조 버튼, 시안 Flow). 보낸 뒤 누르면 보고 흐름이 수정으로 열린다.
 *
 * 비회원 홈(Home-guest)은 동네 현황이 같다. 머리줄 알림(종)은 그리지 않는다(#123). 모바일은 하단 버튼 위에 한 줄 안내,
 * 태블릿 · 데스크톱은 본문 맨 위에 안내 상자를 둔다. 둘러보기에서 고른 동네(`regionCode`)는 메뉴 링크에 붙여 잃지 않게 한다.
 *
 * 다시 들어온 회원에게 약관 재동의 · 동네 다시 고르기 조건이 있으면 그 화면으로 먼저 보낸다(`useRequiredStepsGate`, 내 정보와 같다).
 *
 * **둘러보기 동네와 내 동네**(#141): 동네 현황은 둘러보기 동네(`?region=`)가 있으면 그 동네, 없으면 회원의 내 동네(목 프로필)다.
 * 머리줄 동네 이름은 둘러볼 동네 고르기(`/browse/region?next=/`)로 가 둘러보기 동네만 바꾼다. 보고는 늘 내 동네로 하므로 보고 흐름에는
 * 내 동네 이름을 넘기고, 다른 동네를 둘러보는 중이면 보고 흐름이 보고 동네를 따로 밝힌다.
 *
 * 홈 상단 안내 줄(모바일 주차 줄 아래, 태블릿 · 데스크톱 공식 정보 행 아래)에는 오프라인 띠(State-offline) → 알림 대체 안내
 * (State-push-inapp) 순서로 놓는다. 연결이 끊겼다는 건 아래 모든 정보(알림 대체 안내 포함)가 받아 둔 그대로라는 뜻이라 먼저 읽힌다.
 */
export function HomeScreen({
  week: data,
  regionCode = null,
  receivedAt = null,
}: {
  /** 이번 주 동네 현황. 이름은 둘러보기 동네(있을 때) · 목 예시다 — 둘러보기 동네가 없으면 회원의 내 동네 이름으로 덮는다 */
  week: HomeWeekly
  /** 둘러보기(`?region=`)로 고른 행정동 코드. `app/(home)/page.tsx` 가 아는 코드일 때만 넘긴다 */
  regionCode?: string | null
  /**
   * 지금 보이는 홈 자료를 받은 시각(ISO). `app/(home)/page.tsx` 가 넘긴다. 오프라인 띠가 "○월 ○일 00:00에 받은 정보예요" 로 쓰고,
   * 모르면(null) 시각 없이 "오프라인이에요" 만 보인다
   */
  receivedAt?: string | null
}) {
  const { toast, show, dismiss } = useToast()
  const online = useOnline()
  const memberRegion = useMemberRegion()
  // 보이는 동네: 둘러보기 동네가 있으면 그 동네, 없으면 내 동네. 보고는 늘 내 동네다(모르면 보이는 동네 그대로)
  const week = regionCode || !memberRegion ? data : { ...data, regionName: memberRegion.name }
  const reportWeek = memberRegion ? { ...data, regionName: memberRegion.name } : week
  const reportingElsewhere =
    memberRegion !== null && regionCode !== null && regionCode !== memberRegion.code
  const openBrowseRegion = useBrowseRegion(HOME_PATH, regionCode)
  const explain = useExplainParam()
  const report = useModalParam(REPORT_PARAM)
  const auth = useMockAuth()
  const guest = auth === 'guest'
  // 보고 버튼(하단 · 머리줄) 글자. 보낸 뒤면 "이번 주 보고 완료 · 수정하기" 이고 누르면 보고 흐름이 수정으로 열린다.
  // 하이드레이션 첫 그림은 보낸 보고가 없다(서버 그림과 같다)
  const submitted = useSubmittedReport() !== null
  const reportLabel = reportButtonLabel(auth, submitted)
  // 재동의 · 동네 다시 고르기로 보낼 곳. 있으면 아래 보고 진입 정리(원시 history)를 하지 않는다 — 정리가 그 이동을 버리게 한다
  const requiredTarget = useRequiredStepsGate(HOME_PATH)
  const openReport = () => report.open(reportEntryFor(auth))
  const navSearch = regionCode ? new URLSearchParams({ region: regionCode }).toString() : undefined

  // 주소로 바로 들어온 ?report= 가 지금 상태에 맞지 않으면 기록을 쌓지 않고 맞는 시트로 바꾼다.
  // 이번 그림의 effect 가 모두 끝난 뒤에 바꾼다 — Next 는 history 를 감싸 useSearchParams 와 맞추는 일을 최상위
  // 라우터의 effect 에서 시작하는데, 처음 열 때는 자식(이 화면) effect 가 먼저 돌아 그 전에 바꾸면 Next 가 모른다
  const { value: reportValue, replace: replaceReport } = report
  // 시트 열림은 바뀔 값으로 미리 정한다. 하이드레이션 직후(replace 전)에도 맞는 시트가 바로 보이고, replace 는 주소 정리만 한다.
  // 재동의 · 동네 다시 고르기로 보낼 곳이 있으면 시트를 열지 않는다 — 보고하려던 로그인(#136) 뒤 조건이 남은 회원에게 보내기 전 시트가 비친다
  const reportEntry =
    requiredTarget === null ? (guardReportEntry(reportValue, auth) ?? reportValue) : null
  useEffect(() => {
    const fixed = guardReportEntry(reportValue, auth)
    if (fixed === null || requiredTarget !== null) return
    const timer = setTimeout(() => replaceReport(fixed), 0)
    return () => clearTimeout(timer)
  }, [reportValue, auth, replaceReport, requiredTarget])

  // 내 정보에서 건강정보 동의를 철회하고 왔으면 알림을 한 번 띄운다(`features/me/leave-notice.ts`)
  useEffect(() => {
    const notice = takeHomeNotice()
    if (notice) show({ message: notice })
  }, [show])

  // 알림 설정(S10) 화면이 생기면 연결한다
  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })

  return (
    <div className="flex min-h-dvh flex-col">
      <h1 className="sr-only">{week.regionName} 이번 주 우리 동네 건강</h1>

      <AppHeader
        regionName={week.regionName}
        current="home"
        onRegionClick={openBrowseRegion}
        onNotificationClick={guest ? undefined : () => notReady('알림 설정')}
        onReportClick={openReport}
        reportLabel={reportLabel}
        navSearch={navSearch}
      />

      <p className="px-page-mobile text-sub text-fg-sub tablet:hidden">
        {week.weekLabel} · {week.updatedLabel}
      </p>

      <div className="hidden border-b border-divider tablet:block tablet:border-t desktop:border-t-0">
        <OfficialRow official={week.official} navSearch={navSearch} />
      </div>

      {/* 오프라인 띠가 알림 대체 안내보다 위다. 띠의 live 영역은 온라인일 때도 빈 채로 그려 두고, 여백은 띠 상자에만 붙는다 */}
      <OfflineNotice offline={!online} receivedAt={receivedAt} className={HOME_TOP_NOTICE_CLASS} />
      <PushInappNotice week={week} signedIn={!guest} />

      <main className="flex grow flex-col tablet:gap-7 tablet:p-6 desktop:flex-row desktop:gap-8 desktop:px-8">
        <div className="flex flex-col tablet:grid tablet:grid-cols-2 tablet:items-start tablet:gap-7 desktop:order-last desktop:flex desktop:w-105 desktop:shrink-0 desktop:gap-4">
          {/* 비회원 안내. 태블릿은 격자 위 한 줄, 데스크톱은 패널 맨 위 (Home-guest-T · -D). 모바일은 하단 버튼 위 문장이 맡는다 */}
          {guest && (
            <div className="hidden tablet:col-span-2 tablet:block">
              <AlertBox tone="info">
                로그인하면 이번 주 보고를 할 수 있어요. 동네 현황은 지금처럼 볼 수 있어요.
              </AlertBox>
            </div>
          )}

          <StatusCard week={week} onExplain={explain.openExplain} />

          <SectionBand className="tablet:hidden" />
          <div className="tablet:hidden">
            <OfficialRow official={week.official} navSearch={navSearch} />
          </div>
          <SectionBand className="tablet:hidden" />

          <SymptomTrends week={week} />

          <SectionBand className="tablet:hidden" />

          <NoticeSection
            notice={week.notice}
            officialHref={officialHref(week.official, navSearch)}
          />

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
        <div className="flex flex-col gap-2 border-t border-divider px-page-mobile py-3 tablet:hidden">
          {guest && (
            <p className="text-center text-sub text-fg-sub">
              로그인하면 이번 주 보고를 할 수 있어요
            </p>
          )}
          {/* 보낸 뒤에는 회색 보조 버튼이다 (Flow 의 reported) */}
          <Button
            fullWidth
            variant={reportDone(auth, submitted) ? 'secondary' : 'primary'}
            onClick={openReport}
          >
            {reportLabel}
          </Button>
        </div>
        <TabBar current="home" navSearch={navSearch} />
      </div>

      {/* reportEntry 는 회원 상태에 맞춘 값이라 login 은 비회원, health-consent 는 미동의 회원에게만 나온다 */}
      <LoginSheet
        open={reportEntry === REPORT_GATE.login}
        onClose={report.close}
        regionCode={regionCode}
      />

      <HealthConsentSheet
        open={reportEntry === REPORT_GATE.healthConsent}
        onClose={report.close}
        // 동의 시트를 보고 시작으로 바꾼다(replace) — 뒤로 가기로 동의 시트에 돌아오지 않고, 닫으면 홈이다.
        // QA 덮어쓰기(?mock-auth=member-no-consent)가 남으면 동의한 뒤에도 미동의로 보여 함께 지운다.
        // 덮어쓰기가 없으면 동의 순간 목 세션이 member 가 되어 시트가 먼저 닫힐 수 있다 — 그때는 위 guard 가 같은 값으로 바꾼다
        onAgreed={() => report.replace('start', { remove: [MOCK_AUTH_PARAM] })}
      />

      {/* 보고 흐름과 보낸 보고는 동의한 회원만 쓴다 */}
      {auth === 'member' && (
        <ReportFlow
          week={reportWeek}
          regionCode={regionCode}
          reportingElsewhere={reportingElsewhere}
          onNotReady={notReady}
        />
      )}

      {/* 자료 부족이면 보일 숫자가 없어 ?explain=1 로 들어와도 열지 않는다 */}
      {week.status !== 'insufficient' && (
        <ExplainSheet week={week} open={explain.open} onClose={explain.closeExplain} />
      )}

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-40 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </div>
  )
}
