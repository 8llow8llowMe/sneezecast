'use client'

import { useRouter, useSearchParams } from 'next/navigation'

import { AppHeader } from '@/components/app-header'
import { Badge } from '@/components/badge'
import { Button } from '@/components/button'
import { IconButton } from '@/components/icon-button'
import { BellIcon, ChevronLeftIcon } from '@/components/icons'
import { TabBar } from '@/components/tab-bar'
import { useMockAuth } from '@/features/auth/use-mock-auth'
import { reportButtonLabel } from '@/features/home/report-gate'
import { notificationsHref, regionSearch, reportHrefFor } from '@/features/me/me-paths'
import { useShownRegionName } from '@/features/me/member-region'
import { HOME_PATH } from '@/features/onboarding/paths'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { navHref } from '@/lib/nav'
import { useNavTrail } from '@/lib/use-nav-trail'

import type { EmptyOfficial, OfficialProgramView, OfficialReport, PublishedOfficial } from './types'

/** 공식 정보(S08) 주소 (docs/design/SCREENS.md) */
export const OFFICIAL_PATH = '/official'

const TITLE = '질병관리청 발표'

/**
 * S08 공식 정보 (`/official`, 시안 Official · Official-T · Official-D). 비회원도 본다. 폭에 따라 구성이 바뀐다.
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 머리줄 56(뒤로 · 제목 17) → 출처 줄 → 프로그램 묶음 → 원값 안내 → 차이 안내. 탭바 · 아래 버튼 없음 |
 * | 태블릿 | 머리줄 72(뒤로 · 제목 20 · 알림 · 보고 버튼, 아래 구분선) → 가운데 640: 출처 줄 → 제목 28 → 프로그램 묶음 → 원값 안내 → 차이 안내 → 탭바 |
 * | 데스크톱 | 홈과 같은 머리줄 → 가운데 1080: 뒤로 → 왼쪽 본문(태블릿과 같은 순서) + 오른쪽 360 차이 안내 |
 *
 * - **질병관리청 자료만 그린다.** 시민 자가보고 값(참여 · 증상 비율 · 상태)은 이 화면에 없다 — 차이 안내가 두 정보가 다르다는 것만 알린다.
 * - 응답에 발표 단계 · 쉬운 요약 · 발표일이 없어 **수치 · 기준 주 · 출처** 로 그린다(시안 없음, #253). 표본감시 프로그램마다 한 묶음이고,
 *   묶음마다 출처 이름 · 집계 단위 · 기준 주(질병관리청 주차와 기간) · 원값 줄 · 단위 · 받은 시각 · `원문 보기`(주소가 있을 때만)를 둔다.
 *   무엇을 고르는지는 `official-map.ts`.
 * - 보일 묶음이 없으면(`empty`) 묶음 대신 "아직 보여 드릴 표본감시 자료가 없어요" 를 보인다(시안 없음, 이유마다 설명이 다르다).
 * - 뒤로: 앱 안에서 거쳐 왔으면(홈 · 동네 안내 등 어느 화면이든) 기록을 되돌리고, 주소로 바로 들어왔으면 홈으로 기록을 바꿔 간다(`useNavTrail`).
 * - 머리줄 보고 버튼은 내 정보와 같이 회원 상태로 갈 곳을 고른다(`reportHrefFor`). 알림(종)은 알림 설정으로 간다(#201).
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
  const searchParams = useSearchParams()
  const navTrail = useNavTrail()
  const auth = useMockAuth()
  const navSearch = regionSearch(regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const openBrowseRegion = useBrowseRegion(OFFICIAL_PATH, regionCode)
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)

  const openReport = () => router.push(reportHrefFor(auth, regionCode))
  const goBack = () => navTrail.goBack(navHref(HOME_PATH, navSearch))
  // 알림(종)은 회원에게만 그린다 — 데스크톱 머리줄(AppHeader)과 모바일 · 태블릿 머리줄이 같은 규칙이다 (#123)
  const notify =
    auth === 'guest' ? undefined : () => router.push(notificationsHref(regionCode, searchParams))

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
                <EmptyBody official={official} />
              )}

              {/* 모바일은 본문 끝에 짧은 안내. 태블릿 · 데스크톱은 아래 aside 의 긴 안내 */}
              <p className="rounded-button border-hairline border-divider px-4 py-3.5 text-sub leading-[1.6] text-fg-sub tablet:hidden">
                <DifferenceText official={official} />
              </p>
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
    </div>
  )
}

/** 출처 표시(`공식` 배지)와 집계 단위. 출처 이름 · 기준 주는 묶음마다 밝힌다. 받은 자료가 없으면 배지만 둔다 */
function SourceLine({ official }: { official: OfficialReport }) {
  return (
    <div className="flex items-center gap-2">
      <Badge kind="official" />
      {official.status === 'published' && (
        <span className="text-sub text-fg-sub">
          {distinct(official.programs.map((program) => program.regionLabel)).join(' · ')}
        </span>
      )}
    </div>
  )
}

function PublishedBody({ official }: { official: PublishedOfficial }) {
  return (
    <>
      {official.programs.map((program) => (
        <ProgramSection key={program.code} program={program} />
      ))}
      <p className="text-caption leading-[1.6] text-fg-sub tablet:text-sub">
        수치는 발표 원문을 기준으로 해요. 질병관리청 자료를 바꾸지 않고 옮겼어요(공공누리 제4유형).
      </p>
    </>
  )
}

/**
 * 표본감시 프로그램 하나. 출처 · 집계 단위 · 기준 주(질병관리청 주차 · 기간)를 묶음 맨 위에 밝히고, 값은 원값 그대로 줄마다 둔다.
 * 값에는 상태색을 쓰지 않는다 — 공식 수치로 위험을 암시하지 않는다. 받은 시각은 우리가 받은 시각이라 발표일처럼 쓰지 않는다.
 */
function ProgramSection({ program }: { program: OfficialProgramView }) {
  const headingId = `official-${program.code}`
  return (
    <section
      aria-labelledby={headingId}
      className="flex flex-col gap-3 rounded-button bg-section p-4.5 tablet:p-5"
    >
      <div className="flex flex-col gap-1">
        <h2
          id={headingId}
          className="text-section-title font-bold text-fg tablet:text-screen-title"
        >
          {program.name}
        </h2>
        <p className="text-sub text-fg-sub tablet:text-body-strong">
          {program.sourceName} · {program.regionLabel}
        </p>
        <p className="text-sub text-fg-sub tablet:text-body-strong">
          기준 주 {program.weekLabel}
          {program.periodLabel && ` (${program.periodLabel})`}
        </p>
      </div>

      <dl className="flex flex-col">
        {program.rows.map((row) => (
          <div key={row.label} className="flex items-baseline justify-between gap-3 py-1.5">
            <dt className="text-body text-fg tablet:text-body-large">{row.label}</dt>
            <dd className="text-body font-semibold text-fg tabular-nums tablet:text-body-large">
              {row.value}
            </dd>
          </div>
        ))}
      </dl>

      <div className="flex flex-col gap-0.5 text-caption leading-[1.6] text-fg-sub tablet:text-sub">
        <p>
          단위: {program.metricLabel}
          {program.syncedLabel && ` · ${program.syncedLabel}에 받은 자료`}
        </p>
        {program.note && <p>{program.note}</p>}
      </div>

      {program.sourceUrl && (
        <a
          href={program.sourceUrl}
          target="_blank"
          rel="noopener noreferrer"
          // 묶음마다 링크가 있어 어느 원문인지와 새 창으로 열린다는 것을 이름에 알린다. 보이는 글자를 이름 앞에 그대로 둔다
          aria-label={`원문 보기, ${program.name} (새 창)`}
          className="flex min-h-touch items-center self-start text-body font-semibold text-brand tablet:text-body-large"
        >
          원문 보기
        </a>
      )}
    </section>
  )
}

/**
 * 보일 묶음이 없을 때 (시안 없음). 원인을 질병관리청에 돌리지 않는다 — 우리가 아직 받지 않았거나(`none`),
 * 받은 자료가 이 화면이 보이지 않는 종류뿐이다(`unshown`, 전수신고 등)
 */
function EmptyBody({ official }: { official: EmptyOfficial }) {
  return (
    <div className="flex flex-col gap-1 rounded-button bg-section p-4.5 tablet:p-5">
      <p className="text-body font-semibold text-fg tablet:text-body-large">
        아직 보여 드릴 표본감시 자료가 없어요
      </p>
      <p className="text-sub leading-[1.6] text-fg-sub tablet:text-body-strong">
        {official.reason === 'unshown'
          ? '지금 받은 자료(전수신고 등)는 이 화면에서 아직 보여 드리지 않아요.'
          : '질병관리청 표본감시 자료를 받으면 여기에서 보여 드려요.'}
      </p>
    </div>
  )
}

/** 시민 자가보고와 무엇이 다른지. 출처(조사 방식)는 받은 자료가 있을 때만 밝힌다 */
function DifferenceText({ official }: { official: OfficialReport }) {
  return official.status === 'published' ? (
    <>
      {distinct(official.programs.map((program) => program.sourceName)).join(' · ')} 자료예요.
      우리동네체온계의 시민 자가보고와는 조사 대상·지역 단위·발표 시점이 달라요.
    </>
  ) : (
    <>질병관리청 발표는 우리동네체온계의 시민 자가보고와 조사 대상·지역 단위·발표 시점이 달라요.</>
  )
}

function distinct(values: readonly string[]): string[] {
  return [...new Set(values)]
}
