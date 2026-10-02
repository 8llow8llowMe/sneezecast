'use client'

import { useRouter } from 'next/navigation'

import { AppHeader } from '@/components/app-header'
import { Badge } from '@/components/badge'
import { Button } from '@/components/button'
import { IconButton } from '@/components/icon-button'
import { BellIcon, ChevronLeftIcon } from '@/components/icons'
import { TabBar } from '@/components/tab-bar'
import { ToastRegion, useToast } from '@/components/toast'
import { useMockAuth } from '@/features/auth/use-mock-auth'
import { reportButtonLabel } from '@/features/home/report-gate'
import { regionSearch, reportHrefFor } from '@/features/me/me-paths'
import { useShownRegionName } from '@/features/me/member-region'
import { HOME_PATH } from '@/features/onboarding/paths'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { navHref } from '@/lib/nav'
import { useNavTrail } from '@/lib/use-nav-trail'

import type { OfficialReport, PublishedOfficial } from './types'

/** 공식 정보(S08) 주소 (docs/design/SCREENS.md) */
export const OFFICIAL_PATH = '/official'

const TITLE = '질병관리청 발표'

/**
 * S08 공식 정보 (`/official`, 시안 Official · Official-T · Official-D). 비회원도 본다. 폭에 따라 구성이 바뀐다.
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 머리줄 56(뒤로 · 제목 17) → 출처 줄 → 단계 → 쉬운 요약 → 차이 안내 → 원문 보기. 탭바 · 아래 버튼 없음 |
 * | 태블릿 | 머리줄 72(뒤로 · 제목 20 · 알림 · 보고 버튼, 아래 구분선) → 가운데 640: 출처 줄 → 제목 28 → 단계 → 쉬운 요약 → 원문 보기 → 차이 안내 → 탭바 |
 * | 데스크톱 | 홈과 같은 머리줄 → 가운데 1080: 뒤로 → 왼쪽 본문(태블릿과 같은 순서) + 오른쪽 360 차이 안내 |
 *
 * - **질병관리청 자료만 그린다.** 시민 자가보고 값(참여 · 증상 비율 · 상태)은 이 화면에 없다 — 차이 안내가 두 정보가 다르다는 것만 알린다.
 * - 출처(`공식` 배지 · 제목 "질병관리청 발표") · 집계 단위(전국) · 기준 주 · 발표일을 본문 맨 위 줄에 밝힌다.
 *   기준 주는 모바일이 주차("47주"), 태블릿 · 데스크톱이 기간("11월 17일~23일")이다(시안대로).
 * - 받은 발표가 없으면(`empty`) 단계 · 요약 · 원문 보기 대신 "아직 받은 발표가 없어요" 를 보인다(시안 없음).
 * - 뒤로: 앱 안에서 거쳐 왔으면(홈 · 동네 안내 등 어느 화면이든) 기록을 되돌리고, 주소로 바로 들어왔으면 홈으로 기록을 바꿔 간다(`useNavTrail`).
 * - 머리줄 보고 버튼은 내 정보와 같이 회원 상태로 갈 곳을 고른다(`reportHrefFor`). 알림은 "준비하고 있어요" 알림이다.
 * - 머리줄 동네 이름은 둘러보기 동네(없으면 회원의 내 동네)이고, 누르면 둘러볼 동네 고르기(`?next=/official`)로 간다(#141).
 */
export function OfficialScreen({
  official,
  regionName,
  regionCode = null,
}: {
  official: OfficialReport
  /** 데스크톱 머리줄의 동네 이름 */
  regionName: string
  /** 둘러보기(`?region=`)로 고른 행정동 코드. 탭바 · 메뉴 · 뒤로 · 보고 진입 주소에 남긴다 */
  regionCode?: string | null
}) {
  const router = useRouter()
  const navTrail = useNavTrail()
  const auth = useMockAuth()
  const { toast, show, dismiss } = useToast()
  const navSearch = regionSearch(regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const openBrowseRegion = useBrowseRegion(OFFICIAL_PATH, regionCode)
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)

  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })
  const openReport = () => router.push(reportHrefFor(auth, regionCode))
  const goBack = () => navTrail.goBack(navHref(HOME_PATH, navSearch))
  // 알림(종)은 회원에게만 그린다 — 데스크톱 머리줄(AppHeader)과 모바일 · 태블릿 머리줄이 같은 규칙이다 (#123)
  const notify = auth === 'guest' ? undefined : () => notReady('알림 설정')

  return (
    <div className="flex min-h-dvh flex-col">
      {/* 데스크톱 머리줄은 홈과 같다. AppHeader 의 flex 와 다투지 않게 감싸는 요소로 숨긴다 */}
      <div className="hidden desktop:block">
        <AppHeader
          regionName={shownRegionName}
          current="home"
          onRegionClick={openBrowseRegion}
          onNotificationClick={notify}
          onReportClick={openReport}
          reportLabel={reportLabel}
          navSearch={navSearch}
        />
      </div>

      <header className="flex h-14 shrink-0 items-center gap-1 pr-4 pl-2 tablet:h-header-tablet tablet:gap-2 tablet:border-b tablet:border-divider tablet:pr-5 tablet:pl-3 desktop:hidden">
        <IconButton label="뒤로" icon={<ChevronLeftIcon />} onClick={goBack} />
        {/* 화면 제목(h1)은 폭마다 하나만 읽힌다. 모바일은 머리줄, 태블릿 · 데스크톱은 본문 위 제목 28 */}
        <h1 className="grow text-section-title font-bold text-fg tablet:hidden">{TITLE}</h1>
        <span
          aria-hidden="true"
          className="hidden text-screen-title font-bold text-fg tablet:block"
        >
          {TITLE}
        </span>
        <span className="hidden grow tablet:block" />
        <span className="hidden tablet:contents">
          {notify && <IconButton label="알림 설정" icon={<BellIcon />} onClick={notify} />}
          <Button size="sm" onClick={openReport}>
            {reportLabel}
          </Button>
        </span>
      </header>

      <div className="flex grow flex-col tablet:items-center tablet:px-10 tablet:py-7 desktop:px-8 desktop:py-6">
        <div className="flex w-full flex-col tablet:max-w-160 desktop:max-w-270 desktop:gap-4">
          <button
            type="button"
            // 보이는 글자는 화면 이름(시안)이다. 무엇을 하는 버튼인지 이름 앞에 붙이고 보이는 글자를 그대로 둔다
            aria-label={`뒤로, ${TITLE}`}
            onClick={goBack}
            className="hidden min-h-touch cursor-pointer items-center gap-1 self-start text-body font-semibold text-fg-sub desktop:flex"
          >
            <ChevronLeftIcon className="text-fg" />
            <span>{TITLE}</span>
          </button>

          <main className="flex flex-col gap-4.5 px-page-mobile pt-1 pb-6 tablet:gap-5.5 tablet:p-0 desktop:flex-row desktop:gap-12">
            <article className="flex flex-col gap-4.5 tablet:gap-5.5 desktop:grow">
              <SourceLine official={official} />
              <h1 className="hidden text-status-desktop font-bold text-fg tablet:block">{TITLE}</h1>

              {official.status === 'published' ? (
                <PublishedBody official={official} />
              ) : (
                <EmptyBody />
              )}

              {/* 모바일은 원문 보기 위에 짧은 안내. 태블릿 · 데스크톱은 아래 aside 의 긴 안내 */}
              <p className="rounded-button border-hairline border-divider px-4 py-3.5 text-sub leading-[1.6] text-fg-sub tablet:hidden">
                <DifferenceText official={official} />
              </p>

              {official.status === 'published' && (
                <a
                  href={official.sourceUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  // 새 창으로 열린다는 것을 이름에 알린다. 보이는 글자를 이름 앞에 그대로 둔다
                  aria-label="원문 보기 (새 창)"
                  className="flex min-h-touch items-center self-start text-body font-semibold text-brand tablet:text-body-large"
                >
                  원문 보기
                </a>
              )}
            </article>

            <aside
              aria-label="시민 자가보고와 다른 점"
              className="hidden flex-col gap-4 tablet:flex desktop:w-90 desktop:shrink-0"
            >
              <div className="rounded-card border-hairline border-divider px-5 py-4.5 text-body-strong leading-[1.65] text-fg-sub">
                <strong className="mb-1.5 block text-fg">시민 자가보고와 무엇이 다른가요?</strong>
                <DifferenceText official={official} /> 두 정보는 화면에서 섞지 않아요.
              </div>
            </aside>
          </main>
        </div>
      </div>

      {/* 탭바는 태블릿만. 모바일 시안은 탭바가 없고, 데스크톱은 머리줄 메뉴를 쓴다 */}
      <div className="sticky bottom-0 hidden tablet:block">
        <TabBar current="home" navSearch={navSearch} />
      </div>

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-8 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </div>
  )
}

/** 출처 · 집계 단위 · 기준 주 · 발표일. 발표가 없으면 집계 단위만 둔다 */
function SourceLine({ official }: { official: OfficialReport }) {
  return (
    <div className="flex items-center gap-2">
      <Badge kind="official" />
      <span className="text-sub text-fg-sub">
        {official.regionLabel}
        {official.status === 'published' && (
          <>
            {' · '}
            <span className="tablet:hidden">{official.weekLabel}</span>
            <span className="hidden tablet:inline">{official.periodLabel}</span>
            {' · '}
            {official.announcedLabel}
          </>
        )}
      </span>
    </div>
  )
}

function PublishedBody({ official }: { official: PublishedOfficial }) {
  return (
    <>
      <dl className="flex items-center justify-between gap-3 rounded-button bg-section p-4.5 tablet:p-5">
        <dt className="text-body font-semibold text-fg tablet:text-body-large">
          {official.disease}
        </dt>
        <dd className="text-section-title font-bold text-brand tablet:text-screen-title">
          {official.stage}
        </dd>
      </dl>

      <section aria-labelledby="official-summary" className="flex flex-col gap-0.5 tablet:gap-0">
        <h2
          id="official-summary"
          className="mb-1.5 text-section-title font-bold text-fg tablet:text-screen-title"
        >
          쉬운 요약
        </h2>
        {official.summary.map((line) => (
          <p
            key={line}
            className="py-1.5 text-body leading-[1.6] text-fg tablet:py-2 tablet:text-section-title"
          >
            {line}
          </p>
        ))}
        <span className="pt-1 text-caption text-fg-sub tablet:text-sub">
          수치와 표현은 발표 원문을 기준으로 해요
        </span>
      </section>
    </>
  )
}

/** 받은 발표가 없을 때 (시안 없음). 단계 · 요약 자리에 놓는다 */
function EmptyBody() {
  return (
    <div className="flex flex-col gap-1 rounded-button bg-section p-4.5 tablet:p-5">
      <p className="text-body font-semibold text-fg tablet:text-body-large">
        아직 받은 발표가 없어요
      </p>
      <p className="text-sub leading-[1.6] text-fg-sub tablet:text-body-strong">
        질병관리청이 주간 발표를 내면 여기에서 보여 드려요.
      </p>
    </div>
  )
}

/** 시민 자가보고와 무엇이 다른지. 조사 방식은 발표가 있을 때만 밝힌다 */
function DifferenceText({ official }: { official: OfficialReport }) {
  return official.status === 'published' ? (
    <>
      {official.surveyLabel} 자료예요. 우리동네체온계의 시민 자가보고와는 조사 대상·지역 단위·발표
      시점이 달라요.
    </>
  ) : (
    <>질병관리청 발표는 우리동네체온계의 시민 자가보고와 조사 대상·지역 단위·발표 시점이 달라요.</>
  )
}
