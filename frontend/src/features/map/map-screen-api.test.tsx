// @vitest-environment jsdom
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMemberInfoForTests, startMemberInfo } from '@/features/auth/member-info'
import { restoreSession, setSession, startSession } from '@/lib/session/session-store'
import {
  errorResponse,
  holdRequests,
  memberToken,
  myInfoBody,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { MapScreen } from './map-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
  useRouter: () => router,
  usePathname: () => window.location.pathname,
}))

/* 실데이터 지도 (#257). 서버 페이지가 목 지도를 넘기지 않으면(map={null}) 화면이 시군구의 행정동 주간 집계를 읽는다 */

const SEOGYO = '11440660'
const HAPJEONG = '11440680'
const YEOKSAM1 = '11680640'
const AGGREGATES = 'GET /api/v1/districts/weekly-aggregates'
const INFO = 'GET /api/v1/members/me'
const REGION = 'GET /api/v1/members/me/region'

const code = (value: string) => ({ code: value, name: value, description: value })

function district(districtCode: string, name: string, fields: Record<string, unknown> = {}) {
  return {
    code: districtCode,
    name,
    sigungu: '서울특별시 마포구',
    active: true,
    level: code('INSUFFICIENT'),
    insufficientReason: code('LOW_SAMPLE'),
    participantCount: 0,
    ...fields,
  }
}

const HIGH = {
  level: code('HIGH'),
  participantCount: 128,
  calculatedAt: '2025-11-20T00:00:00Z',
  symptomaticCount: 23,
  symptomRate: 18,
  baselineRate: 9,
  groups: [{ group: code('RESPIRATORY'), rate: 14.1, trend: code('HIGH') }],
}

const ADVISORY = {
  isoWeek: '2025-W46',
  title: '호흡기 증상 보고가 늘었어요',
  publishedAt: '2025-11-17T15:30:00Z',
}

function aggregates(districts: Record<string, unknown>[]) {
  return {
    isoWeek: '2025-W47',
    finalized: false,
    publicThreshold: 100,
    slightDeltaPp: 3,
    highDeltaPp: 8,
    districts,
  }
}

/** 서교동 많이 늘었어요(발행 안내 있음) · 합정동 자료 부족 41명 */
const MAPO = aggregates([
  district(SEOGYO, '서교동', { ...HIGH, publishedAdvisory: ADVISORY }),
  district(HAPJEONG, '합정동', { participantCount: 41 }),
])

const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))

let server: ReturnType<typeof holdRequests>
let stops: (() => void)[] = []

beforeEach(() => {
  vi.clearAllMocks()
  selectApiSource()
  resetMemberInfoForTests()
  server = holdRequests()
  stops = [startSession(), startMemberInfo()]
})

afterEach(() => {
  stops.forEach((stop) => stop())
  resetMemberInfoForTests()
  resetApiSession()
  vi.restoreAllMocks()
})

function renderMap(regionCode: string | null = null) {
  window.history.replaceState(null, '', regionCode ? `/map?region=${regionCode}` : '/map')
  return render(<MapScreen map={null} regionName="○○동" regionCode={regionCode} />)
}

/** 보낸 지도 집계 요청의 쿼리 */
function aggregateQueries(): Record<string, string>[] {
  return server.fetchMock.mock.calls
    .map(([url]) => new URL(url as string))
    .filter((url) => `GET ${url.pathname}` === AGGREGATES)
    .map((url) => Object.fromEntries(url.searchParams))
}

function districtList() {
  return screen.getByRole('list', { name: '동네 목록' })
}

describe('실데이터 지도 — 행정동 주간 집계', () => {
  it('처음 고른 동네의 시군구를 그 동네를 include 로 담아 읽고, 읽는 동안에는 수치 없이 불러오는 중이다', async () => {
    renderMap(HAPJEONG)
    await flush()

    expect(screen.getByText('이번 주 동네 상태를 불러오고 있어요')).toBeTruthy()
    expect(screen.queryByRole('list', { name: '동네 목록' })).toBeNull()
    expect(aggregateQueries()).toEqual([{ sigunguCode: '11440', include: HAPJEONG }])

    server.reply(AGGREGATES, okResponse(MAPO))
    await flush()

    expect(screen.queryByText('이번 주 동네 상태를 불러오고 있어요')).toBeNull()
    expect(screen.getByText('시민 자가보고 · 11월 3주')).toBeTruthy()
    const rows = within(districtList()).getAllByRole('button')
    expect(rows.map((row) => row.textContent)).toEqual([
      '서교동많이 늘었어요',
      '합정동 (내 동네)자료 부족',
    ])
    // 처음 고른 동네(목록 첫 동네가 아님)를 접어 보이고, 자료 부족이라 수치 · 비율 없이 참여 진행만 보인다
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('합정동 (내 동네)')
    expect(screen.getByText('41 / 100명')).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/%/)
  })

  it('수치가 있는 동네를 고르면 수치를 보이고, 발행 안내는 링크 없이 운영자 검토 · 발행일 · 제목만 보인다', async () => {
    const user = userEvent.setup()
    renderMap(HAPJEONG)
    await flush()
    server.reply(AGGREGATES, okResponse(MAPO))
    await flush()

    await user.click(within(districtList()).getByRole('button', { name: /서교동/ }))

    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('서교동')
    expect(screen.getAllByText('18%').length).toBeGreaterThan(0)
    expect(screen.getByText('운영자 검토')).toBeTruthy()
    expect(screen.getByText('11월 18일 발행')).toBeTruthy()
    expect(screen.getByText('호흡기 증상 보고가 늘었어요')).toBeTruthy()
    // 동네 안내 화면은 아직 목이라 열지 않는다(#256)
    expect(screen.queryByRole('link', { name: '이 동네 안내 보기' })).toBeNull()
    expect(
      screen
        .queryAllByRole('link')
        .some((link) => link.getAttribute('href')?.startsWith('/notice/')),
    ).toBe(false)
  })

  it('모든 동네의 참여가 0 이면 자료 없음이다 — 동네 목록 · 동네 정보 없이 안내와 찾기만 남는다', async () => {
    renderMap(YEOKSAM1)
    await flush()
    server.reply(
      AGGREGATES,
      okResponse(aggregates([district(YEOKSAM1, '역삼1동'), district('11680650', '역삼2동')])),
    )
    await flush()

    expect(
      screen.getByText('이번 주 동네별 자료가 아직 없어요. 집계가 끝나면 보여 드려요.'),
    ).toBeTruthy()
    expect(screen.queryByRole('list', { name: '동네 목록' })).toBeNull()
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
    expect(screen.getByRole('button', { name: '행정동 이름으로 찾기' })).toBeTruthy()
  })

  it('읽지 못하면 빨강 상자와 다시 시도이고, 다시 시도하면 다시 읽는다', async () => {
    const user = userEvent.setup()
    renderMap(HAPJEONG)
    await flush()
    server.reply(AGGREGATES, errorResponse('GATEWAY_004', 504))
    await flush()

    expect(screen.getByRole('alert').textContent).toContain('이번 주 동네 상태를 불러오지 못했어요')
    expect(screen.queryByRole('list', { name: '동네 목록' })).toBeNull()
    // 모바일 지도는 머리줄 · 찾기 칸이 없어 실패 상자에서도 동네를 바꿀 수 있다
    expect(screen.getByRole('button', { name: '동네 고르기' })).toBeTruthy()

    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    expect(aggregateQueries()).toHaveLength(2)
    expect(screen.getByText('이번 주 동네 상태를 불러오고 있어요')).toBeTruthy()

    server.reply(AGGREGATES, okResponse(MAPO))
    await flush()
    expect(districtList()).toBeTruthy()
  })

  it('집계가 모르는 동네(AGGREGATE_001)면 동네를 고르라고 안내한다', async () => {
    renderMap(HAPJEONG)
    await flush()
    server.reply(AGGREGATES, errorResponse('AGGREGATE_001', 404))
    await flush()

    expect(screen.getByText('동네를 고르면 이번 주 동네 상태를 보여 드려요.')).toBeTruthy()
    expect(screen.getByRole('button', { name: '동네 고르기' })).toBeTruthy()
  })

  it('처음 고른 동네가 바뀌면 앞 동네의 늦은 응답은 버리고 새 동네를 접어 보인다', async () => {
    const { rerender } = renderMap(HAPJEONG)
    await flush()
    rerender(<MapScreen map={null} regionName="○○동" regionCode={YEOKSAM1} />)
    await flush()
    expect(aggregateQueries().map((query) => query.include)).toEqual([HAPJEONG, YEOKSAM1])

    // 앞 동네의 응답은 동네가 바뀐 뒤 와서 버린다(먼저 붙잡힌 요청부터 답한다)
    server.reply(AGGREGATES, okResponse(MAPO))
    await flush()
    expect(screen.getByText('이번 주 동네 상태를 불러오고 있어요')).toBeTruthy()
    expect(screen.queryByRole('list', { name: '동네 목록' })).toBeNull()

    server.reply(AGGREGATES, okResponse(aggregates([district(YEOKSAM1, '역삼1동', HIGH)])))
    await flush()

    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('역삼1동 (내 동네)')
    expect(screen.queryByText('합정동')).toBeNull()
  })

  it('둘러보는 동네가 없는 비회원은 요청 없이 동네를 고르라고 안내한다', async () => {
    await act(() => restoreSession())
    renderMap()
    await flush()

    expect(screen.getByText('동네를 고르면 이번 주 동네 상태를 보여 드려요.')).toBeTruthy()
    expect(aggregateQueries()).toHaveLength(0)
  })

  it('둘러보는 동네가 없는 회원은 내 동네를 읽은 뒤 그 동네를 include 로 읽는다', async () => {
    renderMap()
    setSession(memberToken())
    await flush()
    expect(screen.getByText('이번 주 동네 상태를 불러오고 있어요')).toBeTruthy()
    expect(aggregateQueries()).toHaveLength(0)

    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(
      REGION,
      okResponse({ code: SEOGYO, name: '서교동', sigungu: '서울특별시 마포구', abolished: false }),
    )
    await flush()
    expect(aggregateQueries()).toEqual([{ sigunguCode: '11440', include: SEOGYO }])

    server.reply(AGGREGATES, okResponse(MAPO))
    await flush()
    // 회원의 내 동네라 `내 동네로 설정` 이 없다
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('서교동 (내 동네)')
    expect(screen.queryByRole('button', { name: '내 동네로 설정' })).toBeNull()
  })

  it('둘러보는 동네가 없는 회원이 다른 동네를 내 동네로 설정하면 목록을 그대로 둔 채 다시 읽고, 포커스는 동네 이름 제목이다', async () => {
    const user = userEvent.setup()
    const { rerender } = renderMap()
    setSession(memberToken())
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(
      REGION,
      okResponse({ code: SEOGYO, name: '서교동', sigungu: '서울특별시 마포구', abolished: false }),
    )
    await flush()
    server.reply(AGGREGATES, okResponse(MAPO))
    await flush()

    await user.click(within(districtList()).getByRole('button', { name: /합정동/ }))
    await user.click(screen.getByRole('button', { name: '내 동네로 설정' }))
    rerender(<MapScreen map={null} regionName="○○동" regionCode={null} />)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '설정하기' }))
    server.reply(
      'PUT /api/v1/members/me/region',
      okResponse({
        code: HAPJEONG,
        name: '합정동',
        sigungu: '서울특별시 마포구',
        abolished: false,
      }),
    )
    await flush()

    // 처음 고른 동네(내 동네)가 바뀌어 다시 읽는 동안에도 받은 지도를 그대로 그린다
    expect(aggregateQueries().map((query) => query.include)).toEqual([SEOGYO, HAPJEONG])
    expect(screen.queryByText('이번 주 동네 상태를 불러오고 있어요')).toBeNull()
    expect(districtList()).toBeTruthy()
    const heading = screen.getByRole('heading', { level: 2 })
    expect(heading.textContent).toBe('합정동 (내 동네)')
    expect(document.activeElement).toBe(heading)

    server.reply(AGGREGATES, okResponse(MAPO))
    await flush()
    // 새 지도를 받아도 보던 동네 · 펼침 · 포커스가 그대로다
    expect(screen.getByRole('heading', { level: 2 })).toBe(heading)
    expect(document.activeElement).toBe(heading)
    expect(
      screen
        .getByRole('button', { name: /동네 정보 (자세히 보기|접기)/ })
        .getAttribute('aria-expanded'),
    ).toBe('true')
  })
})
