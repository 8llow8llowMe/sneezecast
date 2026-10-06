'use client'

import { useEffect, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { ToastRegion, useToast } from '@/components/toast'
import { reportButtonLabel } from '@/features/home/report-gate'
import { useBrowseRegion } from '@/features/onboarding/use-browse-region'
import {
  refreshCurrentReportIfWeekChanged,
  retryCurrentReport,
} from '@/features/report/current-report'
import {
  listPastReports,
  MOCK_REPORTS_PARAM,
  type PastReport,
  REPORT_RETENTION_WEEKS,
} from '@/features/report/report-history'
import { summarizeAnswer } from '@/features/report/symptoms'
import {
  useSubmittedReport,
  useSubmittedReportStatus,
  useSubmittedReportWeek,
} from '@/features/report/use-submitted-report'
import { formatIsoWeekOfMonth, formatIsoWeekRange, kstIsoWeek, parseIsoWeek } from '@/lib/iso-week'
import { navHref } from '@/lib/nav'
import { useDataSource } from '@/lib/use-data-source'

import { AccountPageLayout } from './account-page-layout'
import { ME_PATH, ME_REPORTS_PATH, meSearch, regionSearch, reportHrefFor } from './me-paths'
import { useMeTrail } from './me-trail'
import { useMemberGate } from './member-gate'
import { useShownRegionName } from './member-region'

/**
 * S10 최근 보고 내역 (`/me/reports`, #194). 회원만 본다(`useMemberGate`). **시안이 없어** 계정 화면(Settings-devices)의 틀을 쓴다.
 *
 * - 안내(52주 보관 · 같은 주는 마지막 보고만) → 보낸 보고 목록(최근 주부터). 한 줄은 주(`이번 주` · `10월 1주` · 기간)와
 *   보고(`증상 없음` · `증상 있음 · 발열·기침·인후통`)다. 보고하지 않은 주는 줄이 없다
 * - **건강정보에 동의하지 않은 회원에게는 이 화면이 없다** — 동의하지 않으면 보고가 없고(철회하면 지운다) 내 정보에도 `내 보고` 섹션이
 *   없다. 그리지 않고 내 정보로 돌려보낸다(비밀번호가 없는 회원의 `/me/password` 와 같다)
 * - 이번 주 보고는 보고 버튼 · 내 정보와 같은 값(`useSubmittedReport` — 실데이터는 `GET /api/v1/reports/current` 저장소, 목은 목 보고)이다.
 *   실데이터에서 읽는 동안은 "불러오고 있어요", 읽지 못하면 빨강 상자 + `다시 시도` 다. 실데이터 줄의 주는 서버가 정한 주(저장소 `week`)이고
 *   화면을 연 때의 KST 주와 같을 때만 `이번 주` 다. 저장소 값이 지난 주 것이면 열 때 다시 읽는다(`refreshCurrentReportIfWeekChanged`)
 * - 지난 보고는 BE 미정이다(`report-history.ts`). **실데이터는 지어낸 이력을 보이지 않고** 회색 상자로 아직 불러올 수 없다고 알린다.
 *   목은 예시 목록이고 `?mock-reports=empty` 면 지난 보고가 없다
 * - 보낸 보고가 하나도 없으면 빈 상태다
 */
export function ReportsScreen({
  regionName,
  regionCode = null,
}: {
  /** 데스크톱 머리줄의 동네 이름(서버가 준 둘러보기 동네 · 목 예시) */
  regionName: string
  /** 둘러보기(`?region=`)로 고른 행정동 코드 */
  regionCode?: string | null
}) {
  const auth = useMemberGate({ next: ME_REPORTS_PATH })
  const searchParams = useSearchParams()
  const { goBack } = useMeTrail()
  const noConsent = auth === 'member-no-consent'
  const backHref = navHref(ME_PATH, meSearch(regionCode, searchParams))
  useEffect(() => {
    // 동의하지 않은 회원에게는 보낸 보고가 없다. 내 정보로 돌려보낸다(알림 없음)
    if (noConsent) goBack(backHref)
  }, [noConsent, goBack, backHref])

  if (auth !== 'member') return null
  return <Reports regionName={regionName} regionCode={regionCode} backHref={backHref} />
}

function Reports({
  regionName,
  regionCode,
  backHref,
}: {
  regionName: string
  regionCode: string | null
  backHref: string
}) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { goBack } = useMeTrail()
  const source = useDataSource()
  const { toast, show, dismiss } = useToast()
  const openBrowseRegion = useBrowseRegion(ME_REPORTS_PATH, regionCode)
  const shownRegionName = useShownRegionName(regionName, regionCode)
  const submitted = useSubmittedReport()
  const currentStatus = useSubmittedReportStatus()
  const storedWeek = useSubmittedReportWeek()
  // 화면을 연 때의 KST 주. `이번 주` 라고 부를지만 정한다 — 화면을 띄운 채 주가 바뀌어도 줄이 갑자기 옮겨 가지 않게 붙잡는다
  const [now] = useState(() => new Date())
  const thisWeek = kstIsoWeek(now)
  // 보낸 보고의 주. 실데이터는 서버가 정한 주(응답 `isoWeek`)가 정본이고, 목은 서버가 없어 지금 주다
  const submittedWeek = source === 'api' ? storedWeek : thisWeek
  // 저장소 값이 지난 주 것이면(주가 바뀐 뒤 화면이 다시 보이기 전에 이 화면을 열었다) 다시 읽는다 — 화면이 다시 보일 때와 같은 함수다
  useEffect(() => {
    if (source === 'api') refreshCurrentReportIfWeekChanged()
  }, [source])
  const past = listPastReports(source, {
    now,
    empty: searchParams.get(MOCK_REPORTS_PARAM) === 'empty',
  })

  const reports: PastReport[] = [
    ...(submitted && submittedWeek ? [{ isoWeek: submittedWeek, answer: submitted.answer }] : []),
    ...(past.status === 'ready'
      ? past.reports.filter((report) => report.isoWeek !== submittedWeek)
      : []),
  ]
  const empty = currentStatus === 'ready' && reports.length === 0
  const notReady = (screen: string) => show({ message: `${screen} 화면은 준비하고 있어요` })

  return (
    <AccountPageLayout
      title="최근 보고 내역"
      regionName={shownRegionName}
      navSearch={regionSearch(regionCode)}
      onBack={() => goBack(backHref)}
      onRegionClick={openBrowseRegion}
      onNotificationClick={() => notReady('알림 설정')}
      onReportClick={() => router.push(reportHrefFor('member', regionCode))}
      reportLabel={reportButtonLabel('member', submitted !== null)}
    >
      <p className="text-body leading-[1.6] text-fg-sub">
        보낸 보고는 {REPORT_RETENTION_WEEKS}주 동안 보관해요. {REPORT_RETENTION_WEEKS}주가 지나면
        지워요. 같은 주에 고쳐 보낸 보고는 마지막 보고만 남아요.
      </p>

      {/* 이번 주 보고를 읽는 중 안내. 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽으므로 늘 그려 둔다 */}
      <div className="flex flex-col">
        <div role="status">
          {currentStatus === 'loading' && (
            <p className="text-body text-fg-sub">이번 주 보고를 불러오고 있어요</p>
          )}
        </div>

        {currentStatus === 'failed' && (
          <AlertBox
            tone="danger"
            className="mb-3"
            action={
              <Button
                variant="secondary"
                size="sm"
                className="self-start"
                onClick={retryCurrentReport}
              >
                다시 시도
              </Button>
            }
          >
            이번 주 보고를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
          </AlertBox>
        )}

        {reports.length > 0 && (
          <ul aria-label="보낸 보고 목록" className="flex flex-col border-t border-divider">
            {reports.map((report) => (
              <ReportRow
                key={report.isoWeek}
                report={report}
                current={report.isoWeek === thisWeek}
                thisYear={parseIsoWeek(thisWeek)?.year ?? null}
              />
            ))}
          </ul>
        )}

        {empty && (
          <div className="flex flex-col items-center gap-2 px-3 py-8 text-center">
            <p className="text-section-title font-semibold text-fg">
              {past.status === 'ready'
                ? '아직 보낸 보고가 없어요'
                : '이번 주에는 아직 보고하지 않았어요'}
            </p>
            <p className="text-body-strong leading-[1.55] text-fg-sub">
              주간 보고를 보내면 여기에서 볼 수 있어요.
            </p>
          </div>
        )}
      </div>

      {past.status === 'unavailable' && (
        <AlertBox tone="neutral">
          지난 보고는 아직 불러올 수 없어요. 지금은 이번 주 보고만 보여요.
        </AlertBox>
      )}

      <ToastRegion
        toast={toast}
        onAction={dismiss}
        className="fixed inset-x-0 bottom-8 px-page-mobile tablet:bottom-20 tablet:mx-auto tablet:w-dialog-tablet tablet:px-0 desktop:bottom-8"
      />
    </AccountPageLayout>
  )
}

/**
 * 보낸 보고 한 줄. 위는 주(`이번 주` 또는 `10월 1주` · 기간), 아래는 보고다. 올해가 아닌 주는 연도를 붙인다 —
 * 52주를 보관하면 같은 `10월 2주` 가 두 번 나올 수 있다
 */
function ReportRow({
  report,
  current,
  thisYear,
}: {
  report: PastReport
  current: boolean
  thisYear: number | null
}) {
  const year = parseIsoWeek(report.isoWeek)?.year ?? null
  const week = current
    ? '이번 주'
    : `${year !== null && year !== thisYear ? `${year}년 ` : ''}${formatIsoWeekOfMonth(report.isoWeek) ?? ''}`
  return (
    <li className="flex min-h-17 flex-col justify-center gap-0.5 border-b border-divider py-2.5">
      <span className="text-sub text-fg-sub">
        {week} · {formatIsoWeekRange(report.isoWeek)}
      </span>
      <span className="text-body font-semibold text-fg">{summarizeAnswer(report.answer)}</span>
    </li>
  )
}
