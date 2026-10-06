// @vitest-environment jsdom
import type { ReactNode } from 'react'

import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'
import { resetCurrentReportForTests, startCurrentReport } from '@/features/report/current-report'
import { cancelReport, submitReport } from '@/features/report/report-client'
import { kstIsoWeek } from '@/lib/iso-week'
import { setSession } from '@/lib/session/session-store'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import {
  errorResponse,
  holdRequests,
  memberToken,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { MeTrailProvider } from './me-trail'
import { ReportsScreen } from './reports-screen'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/me/reports',
  useRouter: () => router,
}))

function withTrail(children: ReactNode) {
  return (
    <NavTrailProvider>
      <MeTrailProvider>{children}</MeTrailProvider>
    </NavTrailProvider>
  )
}

const renderReports = (regionCode?: string) =>
  render(withTrail(<ReportsScreen regionName="○○동" {...(regionCode ? { regionCode } : {})} />))

const rows = () =>
  within(screen.getByRole('list', { name: '보낸 보고 목록' }))
    .getAllByRole('listitem')
    .map((item) => item.textContent)

beforeEach(async () => {
  search = 'mock-auth=member'
  resetMockSession()
  // 목 보고는 목 세션이 바뀔 때만 지워진다. 덮어쓰기(?mock-auth=)만 쓰면 세션이 그대로라 앞 테스트의 보고를 지운다
  await cancelReport('mock')
  vi.clearAllMocks()
  // 2026-10-06(화) 12:00 KST — 이번 주는 2026-W41(10월 5일~11일). 화면이 연 때의 주로 줄을 정한다
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-06T03:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('ReportsScreen 최근 보고 내역 (목)', () => {
  it('머리줄 알림(종)은 동네 · 덮어쓰기를 남긴 채 알림 설정으로 간다', async () => {
    search =
      'region=11440660&mock-auth=member&mock-provider=email&mock-push=supported&confirm=unknown'
    renderReports('11440660')

    const [bell] = await screen.findAllByRole('button', { name: '알림 설정' })
    await userEvent.setup().click(bell as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(
      '/me/notifications?region=11440660&mock-auth=member&mock-provider=email&mock-push=supported',
    )
  })

  it('제목 · 52주 보관 안내 · 지난 주 예시 목록(최근 주부터)을 보인다 — 보고하지 않은 주는 줄이 없다', () => {
    renderReports()

    expect(screen.getAllByRole('heading', { level: 1, name: '최근 보고 내역' })).toHaveLength(2)
    expect(
      screen.getByText(
        '보낸 보고는 52주 동안 보관해요. 52주가 지나면 지워요. 같은 주에 고쳐 보낸 보고는 마지막 보고만 남아요.',
      ),
    ).toBeDefined()
    expect(rows()).toEqual([
      '10월 1주 · 9월 28일~10월 4일증상 없음',
      '9월 4주 · 9월 21일~27일증상 있음 · 발열·기침·인후통',
      '9월 3주 · 9월 14일~20일증상 없음',
      '9월 1주 · 8월 31일~9월 6일증상 있음 · 발열·기침·인후통, 구토·설사',
      '8월 4주 · 8월 24일~30일증상 없음',
    ])
    // 목은 지난 주 보고를 보여 줄 수 있어 불러올 수 없다는 안내가 없다
    expect(screen.queryByText(/아직 불러올 수 없어요/)).toBeNull()
  })

  it('이번 주에 보냈으면 맨 위에 "이번 주" 로 보이고, 되돌리면 사라진다', async () => {
    await submitReport({ kind: 'symptom', symptoms: ['gastrointestinal'] }, null, 'mock')
    renderReports()
    expect(rows()[0]).toBe('이번 주 · 10월 5일~11일증상 있음 · 구토·설사')

    await act(() => cancelReport('mock'))
    expect(rows()[0]).toBe('10월 1주 · 9월 28일~10월 4일증상 없음')
  })

  it('올해가 아닌 주는 연도를 붙인다', () => {
    vi.setSystemTime(new Date('2026-01-14T03:00:00Z'))
    renderReports()
    expect(rows()[2]).toBe('2025년 12월 4주 · 12월 22일~28일증상 없음')
  })

  it('?mock-reports=empty 여도 이번 주에 보냈으면 "이번 주" 한 줄만 보인다', async () => {
    search = 'mock-auth=member&mock-reports=empty'
    await submitReport({ kind: 'none' }, null, 'mock')
    renderReports()
    expect(rows()).toEqual(['이번 주 · 10월 5일~11일증상 없음'])
    expect(screen.queryByText('아직 보낸 보고가 없어요')).toBeNull()
  })

  it('?mock-reports=empty 이고 이번 주도 보내지 않았으면 빈 상태다', () => {
    search = 'mock-auth=member&mock-reports=empty'
    renderReports()
    expect(screen.queryByRole('list', { name: '보낸 보고 목록' })).toBeNull()
    expect(screen.getByText('아직 보낸 보고가 없어요')).toBeDefined()
    expect(screen.getByText('주간 보고를 보내면 여기에서 볼 수 있어요.')).toBeDefined()
  })
})

describe('ReportsScreen 가드 · 돌아가기', () => {
  it('비회원이 주소로 들어오면 로그인(돌아올 곳 /me/reports)으로 기록을 바꿔 간다', () => {
    search = ''
    const { container } = renderReports()
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fme%2Freports')
  })

  it('건강정보에 동의하지 않은 회원은 그리지 않고 내 정보로 돌려보낸다 (동네 · 덮어쓰기를 남긴다)', () => {
    search = 'region=11440660&mock-auth=member-no-consent'
    const { container } = renderReports('11440660')
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660&mock-auth=member-no-consent')
  })

  it('주소로 바로 들어왔으면 뒤로가 내 정보로 기록을 바꿔 간다', async () => {
    vi.useRealTimers()
    renderReports()
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/me?mock-auth=member')
  })
})

describe('ReportsScreen 실데이터', () => {
  const CURRENT = 'GET /api/v1/reports/current'
  /** 요청이 나가고 응답의 then 이 돌 때까지 */
  const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))
  /** `GET /reports/current` 응답 본문(backend `WeeklyReportResponse`) */
  const reportBody = (isoWeek: string, code = 'RESPIRATORY') => ({
    isoWeek,
    districtCode: '11680640',
    symptomGroups: [{ code }],
    reportedAt: '2026-10-05T01:00:00Z',
    updatedAt: '2026-10-05T01:00:00Z',
  })
  let server: ReturnType<typeof holdRequests>
  let stop: () => void = () => {}

  beforeEach(() => {
    // 응답 대기에 setTimeout 을 쓴다 — 날짜만 고정한 가짜 시계와 함께 쓸 수 없어 실제 시계로 둔다
    vi.useRealTimers()
    search = ''
    selectApiSource()
    resetCurrentReportForTests()
    server = holdRequests()
    stop = startCurrentReport()
  })

  afterEach(() => {
    stop()
    resetCurrentReportForTests()
    resetApiSession()
  })

  it('지난 보고는 지어내지 않고 불러올 수 없다고 알린다. 이번 주 보고를 읽는 동안 · 읽은 뒤를 보인다', async () => {
    act(() => setSession(memberToken()))
    renderReports()
    await flush()

    expect(screen.getByText('이번 주 보고를 불러오고 있어요')).toBeDefined()
    expect(
      screen.getByText('지난 보고는 아직 불러올 수 없어요. 지금은 이번 주 보고만 보여요.'),
    ).toBeDefined()
    expect(server.requests()).toEqual([CURRENT])

    const week = kstIsoWeek(new Date())
    act(() =>
      server.reply(
        CURRENT,
        okResponse({
          isoWeek: week,
          districtCode: '11680640',
          symptomGroups: [{ code: 'RESPIRATORY' }],
          reportedAt: '2026-10-05T01:00:00Z',
          updatedAt: '2026-10-05T01:00:00Z',
        }),
      ),
    )
    await flush()
    expect(screen.queryByText('이번 주 보고를 불러오고 있어요')).toBeNull()
    expect(rows()).toHaveLength(1)
    expect(rows()[0]).toMatch(/^이번 주 · .+증상 있음 · 발열·기침·인후통$/)
  })

  it('이번 주 보고가 없으면 이번 주 빈 상태, 읽지 못하면 빨강 상자 · 다시 시도로 다시 읽는다', async () => {
    act(() => setSession(memberToken()))
    renderReports()
    await flush()

    act(() => server.reply(CURRENT, errorResponse('GATEWAY_003', 503)))
    await flush()
    expect(
      screen.getByText('이번 주 보고를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.'),
    ).toBeDefined()
    // 읽지 못했을 때는 보고가 없다고 하지 않는다
    expect(screen.queryByText('이번 주에는 아직 보고하지 않았어요')).toBeNull()

    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    act(() => server.reply(CURRENT, okResponse(null)))
    await flush()
    expect(screen.getByText('이번 주에는 아직 보고하지 않았어요')).toBeDefined()
    expect(screen.queryByRole('list', { name: '보낸 보고 목록' })).toBeNull()
  })

  it('저장소의 보고가 지난 주 것이면(주가 바뀐 뒤 처음 연 화면) "이번 주" 로 그리지 않고 다시 읽는다', async () => {
    act(() => setSession(memberToken()))
    await flush()
    // 주가 바뀌기 전에 읽어 둔 보고 — 서버가 정한 주는 지난 주다
    const lastWeek = kstIsoWeek(new Date(Date.now() - 7 * 86_400_000))
    act(() => server.reply(CURRENT, okResponse(reportBody(lastWeek))))
    await flush()

    renderReports()
    await flush()
    expect(server.requests()).toEqual([CURRENT, CURRENT])
    expect(screen.queryByText(/^이번 주 ·/)).toBeNull()
    expect(screen.getByText('이번 주 보고를 불러오고 있어요')).toBeDefined()

    act(() => server.reply(CURRENT, okResponse(reportBody(kstIsoWeek(new Date()), 'ENTERIC'))))
    await flush()
    expect(rows()).toHaveLength(1)
    expect(rows()[0]).toMatch(/^이번 주 · .+증상 있음 · 구토·설사$/)
  })

  it('저장소의 주가 지금 주면 다시 읽지 않는다', async () => {
    act(() => setSession(memberToken()))
    await flush()
    act(() => server.reply(CURRENT, okResponse(reportBody(kstIsoWeek(new Date())))))
    await flush()

    renderReports()
    await flush()
    expect(server.requests()).toEqual([CURRENT])
    expect(rows()[0]).toMatch(/^이번 주 · /)
  })

  it('건강정보에 동의하지 않은 회원(reportWritable false)은 이번 주 보고를 요청하지 않고 내 정보로 돌아간다', async () => {
    act(() => setSession(memberToken({ reportWritable: false })))
    const { container } = renderReports()
    await flush()
    expect(container.textContent).toBe('')
    expect(server.requests()).toEqual([])
    expect(router.replace).toHaveBeenCalledWith('/me')
  })
})
