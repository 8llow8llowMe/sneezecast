'use client'

import { useRouter, useSearchParams } from 'next/navigation'

import { useMockAuth } from '@/features/auth/use-mock-auth'
import { reportButtonLabel } from '@/features/home/report-gate'
import { HOME_PATH } from '@/features/onboarding/paths'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { navHref } from '@/lib/nav'
import { useNavTrail } from '@/lib/use-nav-trail'

import { AccountPageLayout } from './account-page-layout'
import { INFO_PAGES, type InfoPageKind, type InfoSection } from './info-pages'
import { ME_PATH, meSearch, notificationsHref, regionSearch, reportHrefFor } from './me-paths'
import { useShownRegionName } from './member-region'

/** 비회원의 데스크톱 돌아가기. 비회원의 뒤로는 내 정보로 가지 않는다(내 정보는 비회원을 로그인으로 보낸다) */
const GUEST_BACK = { text: '뒤로', label: '뒤로 가기' }

/**
 * S10 서비스 안내 화면 (#193, 시안 없음): 모으는 정보와 보관 기간(`/me/privacy`) · 데이터 출처(`/me/data-sources`) ·
 * AI 사용 방식(`/me/ai`). 문구는 `info-pages.ts` 에 두고 이 화면은 틀만 맡는다.
 *
 * - **비회원도 본다.** 회원 가드(`useMemberGate`)를 걸지 않는다. 비회원이면 머리줄 알림(종)을 그리지 않고(내 정보와 같다, #123)
 *   보고 버튼은 보고하려던 로그인으로 간다(`reportHrefFor`)
 * - 틀은 계정 화면(`AccountPageLayout`)이고 아래 버튼은 없다. 본문은 판단 기준(S11 `explain-sheet.tsx`)처럼
 *   섹션 제목 + 1px 구분선 행 + 회색 덧붙임이다(카드 없음 — docs/design-guide.md "디자인 규칙")
 * - **뒤로**: 여러 화면에서 들어올 수 있는 화면이라 앞 화면 후보 없이 앱 안 어디서 왔든 되돌린다(공식 정보와 같다,
 *   docs/conventions.md "화면의 뒤로"). 주소로 바로 들어왔으면 회원은 내 정보(동네 · 덮어쓰기를 남김), 비회원은 홈(동네를 남김)으로
 *   기록을 바꿔 간다. 회원 상태는 누를 때 읽는다 — 정해지기 전(하이드레이션 첫 그림 · 세션 복원 중)이면 비회원처럼 홈이다
 * - 머리줄 동네 이름은 둘러볼 동네 고르기를 홈을 돌아갈 곳으로 연다(동네 안내와 같다 — 안내 내용이 동네와 무관하다).
 *   그래서 돌아갈 곳 허용 목록(`BROWSE_NEXT_PATHS`)에 넣지 않는다
 */
export function InfoScreen({
  kind,
  regionName,
  regionCode = null,
}: {
  kind: InfoPageKind
  /** 데스크톱 머리줄의 동네 이름(서버가 준 둘러보기 동네 · 목 예시) */
  regionName: string
  /** 둘러보기(`?region=`)로 고른 행정동 코드 */
  regionCode?: string | null
}) {
  const page = INFO_PAGES[kind]
  const router = useRouter()
  const searchParams = useSearchParams()
  const { goBack } = useNavTrail()
  const auth = useMockAuth()
  const guest = auth === 'guest'
  const reportLabel = reportButtonLabel(auth, useSubmittedReport() !== null)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const openBrowseRegion = useBrowseRegion(HOME_PATH, regionCode)
  const navSearch = regionSearch(regionCode)
  const backHref = guest
    ? navHref(HOME_PATH, navSearch)
    : navHref(ME_PATH, meSearch(regionCode, searchParams))

  return (
    <AccountPageLayout
      title={page.title}
      regionName={shownRegionName}
      navSearch={navSearch}
      onBack={() => goBack(backHref)}
      desktopBack={guest ? GUEST_BACK : undefined}
      onRegionClick={openBrowseRegion}
      onNotificationClick={
        guest ? undefined : () => router.push(notificationsHref(regionCode, searchParams))
      }
      onReportClick={() => router.push(reportHrefFor(auth, regionCode))}
      reportLabel={reportLabel}
    >
      <div className="flex flex-col gap-7 pb-10">
        <p className="text-body leading-[1.6] text-fg-sub">{page.lead}</p>
        {page.sections.map((section) => (
          <InfoSectionBlock key={section.title} section={section} />
        ))}
      </div>
    </AccountPageLayout>
  )
}

/** 섹션 제목 17 굵게(내 정보 섹션과 같다) → 1px 구분선 행 → 회색 덧붙임 */
function InfoSectionBlock({ section }: { section: InfoSection }) {
  return (
    <section className="flex flex-col">
      <h2 className="mb-1 text-section-title font-bold text-fg">{section.title}</h2>
      <ul className="flex flex-col border-t border-divider">
        {section.items.map((item) => (
          <li key={item} className="border-b border-divider py-3 text-body leading-[1.6] text-fg">
            {item}
          </li>
        ))}
      </ul>
      {section.notes && (
        <div className="flex flex-col gap-1 pt-3">
          {section.notes.map((note) => (
            <p key={note} className="text-sub leading-[1.55] text-fg-sub">
              {note}
            </p>
          ))}
        </div>
      )}
    </section>
  )
}
