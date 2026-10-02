'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'

import { AppHeader } from '@/components/app-header'
import { Badge } from '@/components/badge'
import { Button } from '@/components/button'
import { IconButton } from '@/components/icon-button'
import { BellIcon, ChevronLeftIcon, ChevronRightIcon } from '@/components/icons'
import { ListRow } from '@/components/list-row'
import { ProgressBar } from '@/components/progress-bar'
import { TabBar } from '@/components/tab-bar'
import { ToastRegion, useToast } from '@/components/toast'
import { useMockAuth } from '@/features/auth/use-mock-auth'
import { GROUP_LABEL, scaleMax, TREND_LABEL, TREND_TEXT_CLASS } from '@/features/home/symptom'
import { TrendBars } from '@/features/home/symptom-trends'
import { regionSearch, reportHrefFor } from '@/features/me/me-paths'
import { HOME_PATH } from '@/features/onboarding/paths'
import { formatCount, formatMonthDay } from '@/lib/format'
import { formatIsoWeekOfMonth, formatIsoWeekRange } from '@/lib/iso-week'
import { navHref } from '@/lib/nav'
import { useNavTrail } from '@/lib/use-nav-trail'

import type {
  InsufficientNoticeStats,
  MeasuredNoticeStats,
  PublishedRegionNotice,
  RegionNotice,
} from './types'

/**
 * AI 초안임을 드러내는 문구. **발행된 안내를 보일 때는 늘 함께 보인다** — 안내문은 AI 가 출처가 확인된 집계값으로 쓴 초안을
 * 운영자가 검토해 발행한 것이고, 진단 · 공식 유행 선언이 아니다(루트 CLAUDE.md "공식 정보와 안내").
 */
export const AI_DRAFT_DISCLOSURE =
  '안내문 초안은 AI가 쓰고 운영자가 검토해 발행해요. 진단이 아닌 참고 정보예요. 증상이 심하면 의료기관에 문의하세요.'

/** 안내가 없는 주의 설명. 안내는 운영자가 검토한 주에만 발행된다 */
const NONE_DESCRIPTION =
  '운영자가 표본 규모와 반복·이상 보고를 확인한 변화가 있을 때만 동네 안내를 발행해요.'

/** 비어 있지 않은 조각만 ` · ` 로 잇는다. 날짜를 읽지 못한 조각은 빠진다 */
function joinMeta(parts: readonly (string | null)[]): string {
  return parts.filter((part): part is string => part !== null).join(' · ')
}

const suffix = (value: string | null, word: string) => (value === null ? null : `${value} ${word}`)

/**
 * S07 동네 안내 (`/notice/[region]/[week]`, 시안 Guide-published · Guide-corrected · Guide-none). 폭에 따라 구성이 바뀐다.
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 머리줄 56(뒤로 · 제목 17) → 본문(간격 18): 배지 줄 · 정정 · 제목 20 · 집계 칸 · 증상별 변화 · 할 일 · 공식 정보 행 · AI 고지 → 아래 고정 버튼(발행된 안내만). 탭바 없음 |
 * | 태블릿 | 머리줄 72(뒤로 · 제목 20 · 알림 · 보고 버튼) → 가운데 640(간격 22): 배지 줄 · 정정 · 제목 28 · 할 일 · AI 고지 · 집계 상자 · 공식 정보 상자 · 버튼 → 탭바 |
 * | 데스크톱 | 홈과 같은 머리줄 → 가운데 1080: 뒤로 + 제목 → 왼쪽 본문(배지 줄 ~ AI 고지) · 오른쪽 360(집계 상자 · 공식 정보 상자 · 버튼) |
 *
 * 집계는 모바일과 태블릿 이상에서 놓이는 자리 · 모양이 달라 두 번 그리고 폭에 따라 하나만 보인다(홈의 공식 정보 행과 같다).
 *
 * - **안내는 운영자가 검토 · 발행한 주에만 보인다.** 발행된 안내를 보일 때는 `운영자 검토` 배지와 AI 초안 고지를 늘 함께 보인다.
 * - 정정된 안내는 배지 줄에 마지막 정정일을 적고, 제목 위에 정정 이력(최근 것부터)을 모두 보인다.
 * - 안내가 없는 주는 `시민 자가보고` 배지로 집계만 보인다. `자료 부족` 이면 수치 · 상태색 없이 참여 진행 막대만 보인다.
 * - 질병관리청 정보는 자가보고 집계와 다른 요소(공식 배지가 달린 링크)로 공식 정보 화면(S08)에 잇는다.
 * - "뒤로" 는 앱 안에서 거쳐 왔으면 기록을 되돌리고, 주소로 바로 들어왔거나 새로고침 · 새 탭으로 열었으면
 *   홈으로 기록을 바꿔 간다(`useNavTrail`).
 */
export function NoticeScreen({
  data,
  regionCode = null,
}: {
  data: RegionNotice
  /**
   * 둘러보기(`?region=`)로 고른 행정동 코드. 머리줄 서비스명 · 메뉴 · 탭바 · 뒤로 · 보고 진입 주소에 남긴다(공식 정보와 같다).
   * 주소 경로의 동네(`[region]`, 이 안내의 동네)와는 다른 값이다
   */
  regionCode?: string | null
}) {
  const router = useRouter()
  const navTrail = useNavTrail()
  const auth = useMockAuth()
  const { toast, show, dismiss } = useToast()
  const navSearch = regionSearch(regionCode)

  const title = `${data.regionName} 이번 주 안내`
  const goBack = () => navTrail.goBack(navHref(HOME_PATH, navSearch))
  // 동네 바꾸기 · 알림 설정 · 문 연 곳 찾기 화면이 생기면 각각 연결한다
  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })
  // 알림(종)은 회원에게만 그린다 — 데스크톱 머리줄(AppHeader)과 모바일 · 태블릿 머리줄이 같은 규칙이다 (#123)
  const notify = auth === 'guest' ? undefined : () => notReady('알림 설정')
  const openReport = () => router.push(reportHrefFor(auth, regionCode))
  const reportLabel = auth === 'guest' ? '로그인하고 보고하기' : '이번 주 건강 보고하기'
  const findOpenClinics = () => notReady('야간·휴일 문 연 곳 찾기')

  const weekOfMonth = formatIsoWeekOfMonth(data.isoWeek)
  const weekRange = formatIsoWeekRange(data.isoWeek)
  const statsTitle = `이번 주 ${data.regionName} 집계`

  return (
    <div className="flex min-h-dvh flex-col">
      {/* 데스크톱 머리줄은 홈과 같다. AppHeader 의 flex 와 다투지 않게 감싸는 요소로 숨긴다 */}
      <div className="hidden desktop:block">
        <AppHeader
          regionName={data.regionName}
          current="home"
          onRegionClick={() => notReady('동네 바꾸기')}
          onNotificationClick={notify}
          onReportClick={openReport}
          reportLabel={reportLabel}
          navSearch={navSearch}
        />
      </div>

      <header className="flex h-14 shrink-0 items-center gap-1 pr-4 pl-2 tablet:h-header-tablet tablet:gap-2 tablet:border-b tablet:border-divider tablet:pr-5 tablet:pl-3 desktop:hidden">
        <IconButton label="뒤로" icon={<ChevronLeftIcon />} onClick={goBack} />
        <h1 className="grow text-section-title font-bold text-fg tablet:grow-0 tablet:text-screen-title">
          {title}
        </h1>
        <span className="hidden grow tablet:block" />
        <span className="hidden tablet:contents">
          {notify && <IconButton label="알림 설정" icon={<BellIcon />} onClick={notify} />}
          <Button size="sm" onClick={openReport}>
            {reportLabel}
          </Button>
        </span>
      </header>

      <div className="flex grow flex-col tablet:items-center tablet:px-10 tablet:py-7 desktop:px-8 desktop:py-6">
        <div className="flex w-full grow flex-col tablet:max-w-160 desktop:max-w-270 desktop:gap-4">
          {/* 데스크톱의 뒤로 · 제목 (시안은 `#back` 링크 — 링크는 기록을 쌓아 뒤로 가기가 이 화면으로 돌아온다) */}
          <div className="hidden items-center gap-1 desktop:flex">
            <IconButton label="뒤로" icon={<ChevronLeftIcon />} onClick={goBack} />
            <h1 className="text-body font-semibold text-fg-sub">{title}</h1>
          </div>

          <main className="flex grow flex-col gap-4.5 px-page-mobile pt-1 pb-6 tablet:grow-0 tablet:gap-5.5 tablet:p-0 desktop:flex-row desktop:gap-12">
            <article className="flex flex-col gap-4.5 tablet:gap-5.5 desktop:grow">
              {data.notice ? (
                <PublishedArticle
                  notice={data.notice}
                  stats={data.stats}
                  statsTitle={statsTitle}
                  weekOfMonth={weekOfMonth}
                  weekRange={weekRange}
                />
              ) : (
                <NoneArticle
                  stats={data.stats}
                  statsTitle={statsTitle}
                  officialHref={data.officialHref}
                  weekOfMonth={weekOfMonth}
                  weekRange={weekRange}
                />
              )}
            </article>

            <aside className="flex flex-col gap-4.5 tablet:gap-5.5 desktop:w-90 desktop:shrink-0 desktop:gap-4">
              <section className="hidden flex-col gap-3 rounded-card border border-divider p-5 tablet:flex">
                <h2 className="text-body font-bold text-fg">{statsTitle}</h2>
                {data.stats.status === 'insufficient' ? (
                  <InsufficientStats stats={data.stats} />
                ) : (
                  <>
                    <StatTiles stats={data.stats} baselineLabel="4주 평균" />
                    <GroupRows stats={data.stats} />
                  </>
                )}
              </section>

              <Link
                href={data.officialHref}
                className="flex min-h-14 items-center gap-2.5 border-y border-divider text-fg tablet:rounded-button tablet:border tablet:px-4"
              >
                <Badge kind="official" />
                <span className="grow text-body font-medium">질병관리청 발표 보기</span>
                <ChevronRightIcon className="text-fg-muted" />
              </Link>

              {data.notice && (
                <p className="text-caption leading-[1.55] text-fg-sub tablet:hidden">
                  {AI_DRAFT_DISCLOSURE}
                </p>
              )}

              <div className="hidden tablet:block">
                <Button variant="secondary" fullWidth onClick={findOpenClinics}>
                  야간·휴일 문 연 곳 찾기
                </Button>
              </div>
            </aside>
          </main>
        </div>
      </div>

      {/* 모바일 아래 고정 버튼은 발행된 안내에만 있다 (Guide-none 모바일에는 없다) */}
      {data.notice && (
        <div className="sticky bottom-0 border-t border-divider bg-bg px-page-mobile pt-3 pb-sheet tablet:hidden">
          <Button variant="secondary" fullWidth onClick={findOpenClinics}>
            야간·휴일 문 연 곳 찾기
          </Button>
        </div>
      )}

      {/* 탭바는 태블릿만. 모바일 시안은 탭바가 없고, 데스크톱은 머리줄 메뉴를 쓴다 */}
      <div className="sticky bottom-0 hidden tablet:block">
        <TabBar current="home" navSearch={navSearch} />
      </div>

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-28 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </div>
  )
}

type WeekLabels = { weekOfMonth: string | null; weekRange: string | null }

/** 배지 줄의 날짜. 모바일은 몇째 주(`11월 3주 기준`), 태블릿 이상은 기간(`11월 17일~23일 기준`)이다 (시안) */
function MetaText({ mobile, wide }: { mobile: string; wide: string }) {
  return (
    <>
      <span className="text-sub text-fg-sub tablet:hidden">{mobile}</span>
      <span className="hidden text-sub text-fg-sub tablet:inline">{wide}</span>
    </>
  )
}

function PublishedArticle({
  notice,
  stats,
  statsTitle,
  weekOfMonth,
  weekRange,
}: WeekLabels & { notice: PublishedRegionNotice; stats: MeasuredNoticeStats; statsTitle: string }) {
  const published = suffix(formatMonthDay(notice.publishedOn), '발행')
  const latest = notice.corrections[0]
  // 정정됐으면 기준 주 대신 마지막 정정일을 적는다 (Guide-corrected "11월 18일 발행 · 11월 19일 정정")
  const corrected = latest ? suffix(formatMonthDay(latest.correctedOn), '정정') : null

  return (
    <>
      <div className="flex items-center gap-2">
        <Badge kind="review" />
        <MetaText
          mobile={joinMeta([published, corrected ?? suffix(weekOfMonth, '기준')])}
          wide={joinMeta([published, corrected ?? suffix(weekRange, '기준')])}
        />
      </div>

      {notice.corrections.length > 0 && (
        <section
          aria-label="정정 이력"
          className="rounded-button border border-inactive-bar px-3.5 py-3 text-body-strong leading-normal text-fg tablet:px-4 tablet:py-3.5 tablet:text-body tablet:leading-[1.55]"
        >
          <ul className="flex flex-col gap-2">
            {notice.corrections.map((correction) => (
              <li key={`${correction.correctedOn}-${correction.reason}`}>
                <strong>{suffix(formatMonthDay(correction.correctedOn), '정정') ?? '정정'}</strong>
                {' · '}
                {correction.reason}
              </li>
            ))}
          </ul>
        </section>
      )}

      <h2 className="text-screen-title leading-[1.45] font-bold break-keep text-fg tablet:text-status-desktop tablet:leading-[1.4]">
        {notice.title}
      </h2>

      <section aria-label={statsTitle} className="flex flex-col gap-4.5 tablet:hidden">
        <StatTiles stats={stats} baselineLabel="지난 4주 평균" />
        <GroupRows stats={stats} />
      </section>

      <section className="flex flex-col gap-0.5 tablet:gap-0">
        <h2 className="mb-1.5 text-section-title font-bold text-fg tablet:text-screen-title">
          이렇게 해 주세요
        </h2>
        <ol className="flex flex-col gap-0.5 tablet:gap-0">
          {notice.items.map((item, index) => (
            <li
              key={`${index}-${item}`}
              className="flex gap-2.5 py-1.25 text-body leading-normal text-fg tablet:gap-3 tablet:py-2 tablet:text-section-title"
            >
              <span aria-hidden="true" className="w-5 shrink-0 font-bold text-brand tablet:w-5.5">
                {index + 1}
              </span>
              <span>{item}</span>
            </li>
          ))}
        </ol>
        <span className="pt-1 text-sub text-fg-sub tablet:pt-1.5">근거: {notice.source}</span>
      </section>

      <p className="hidden text-sub leading-[1.55] text-fg-sub tablet:block">
        {AI_DRAFT_DISCLOSURE}
      </p>
    </>
  )
}

function NoneArticle({
  stats,
  statsTitle,
  officialHref,
  weekOfMonth,
  weekRange,
}: WeekLabels & {
  stats: MeasuredNoticeStats | InsufficientNoticeStats
  statsTitle: string
  officialHref: string
}) {
  return (
    <>
      <div className="flex items-center gap-2">
        <Badge kind="citizen" />
        <MetaText
          mobile={joinMeta([suffix(weekOfMonth, '기준')])}
          wide={joinMeta([suffix(weekRange, '기준')])}
        />
      </div>

      <h2 className="text-screen-title leading-[1.45] font-bold break-keep text-fg tablet:text-status-desktop tablet:leading-[1.4]">
        이번 주는 발행된 안내가 없어요
      </h2>
      <p className="text-body leading-[1.6] text-fg-sub tablet:text-body-large">
        {NONE_DESCRIPTION}
      </p>

      <section aria-label={statsTitle} className="tablet:hidden">
        {stats.status === 'insufficient' ? (
          <InsufficientStats stats={stats} />
        ) : (
          <StatTiles stats={stats} baselineLabel="지난 4주 평균" />
        )}
      </section>

      <Link
        href={officialHref}
        className="flex min-h-14 items-center justify-between gap-2.5 border-y border-divider text-fg"
      >
        <span className="text-body font-medium tablet:text-body-large">공식 예방수칙 보기</span>
        <ChevronRightIcon className="text-fg-muted" />
      </Link>
    </>
  )
}

/** 참여 · 증상 보고 · 지난 4주 평균 칸. 수치가 있는 주에만 그린다(타입이 `자료 부족` 을 받지 않는다) */
function StatTiles({
  stats,
  baselineLabel,
}: {
  stats: MeasuredNoticeStats
  /** 시안은 모바일 "지난 4주 평균", 태블릿 이상 "4주 평균" 이다 */
  baselineLabel: string
}) {
  const tiles = [
    { label: '참여', value: `${formatCount(stats.participants)}명` },
    { label: '증상 보고', value: `${stats.symptomRate}%` },
    { label: baselineLabel, value: `${stats.baselineRate}%` },
  ]
  return (
    <dl className="flex gap-2">
      {tiles.map((tile) => (
        <div
          key={tile.label}
          className="flex flex-1 basis-0 flex-col gap-1 rounded-button bg-section p-3.5"
        >
          <dt className="text-caption text-fg-sub">{tile.label}</dt>
          <dd className="text-screen-title font-bold text-fg">{tile.value}</dd>
        </div>
      ))}
    </dl>
  )
}

/** 증상군별 변화. 홈의 증상별 변화와 같은 문구 · 막대(늘어난 경우만 상태색)다 */
function GroupRows({ stats }: { stats: MeasuredNoticeStats }) {
  const max = scaleMax(stats.groups)
  return (
    <div className="flex flex-col">
      {stats.groups.map((group) => (
        <ListRow
          key={group.key}
          title={GROUP_LABEL[group.key]}
          description={
            <span className={TREND_TEXT_CLASS[group.trend]}>{TREND_LABEL[group.trend]}</span>
          }
          trailing={<TrendBars group={group} max={max} />}
          divider
        />
      ))}
    </div>
  )
}

/** 자료 부족. 수치 · 상태색 없이 참여 진행 막대만 보인다 (홈 상태 카드와 같은 문구) */
function InsufficientStats({ stats }: { stats: InsufficientNoticeStats }) {
  return (
    <div className="flex flex-col gap-2">
      <div className="flex justify-between text-body-strong font-semibold text-fg">
        <span>우리 동네 자료를 채우는 중</span>
        <span>
          {formatCount(stats.participants)} / {formatCount(stats.publicThreshold)}명
        </span>
      </div>
      <ProgressBar
        value={stats.participants}
        max={stats.publicThreshold}
        label="우리 동네 참여 인원"
        valueText={`${formatCount(stats.participants)}명 참여, 공개 기준 ${formatCount(stats.publicThreshold)}명`}
      />
      <span className="text-sub leading-normal text-fg-sub">
        참여가 공개 기준을 넘으면 증상 변화를 보여드려요
      </span>
    </div>
  )
}
