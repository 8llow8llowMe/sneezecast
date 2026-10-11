// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react'
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

import { HomeScreen } from './home-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
  useRouter: () => router,
  usePathname: () => window.location.pathname,
}))

/* 실데이터 홈의 동네 현황 (#252). 서버 페이지가 목 주를 넘기지 않으면(week={null}) 화면이 행정동 주간 집계를 읽는다 */

const YEOKSAM1_CODE = '11680640'
const AGGREGATE = `GET /api/v1/districts/${YEOKSAM1_CODE}/weekly-aggregate`
const INFO = 'GET /api/v1/members/me'
const REGION = 'GET /api/v1/members/me/region'

const code = (value: string) => ({ code: value, name: value, description: value })

function aggregate(district: Record<string, unknown>) {
  return {
    isoWeek: '2025-W47',
    finalized: false,
    publicThreshold: 100,
    slightDeltaPp: 3,
    highDeltaPp: 8,
    district: {
      code: YEOKSAM1_CODE,
      name: '역삼1동',
      sigungu: '서울특별시 강남구',
      active: true,
      calculatedAt: '2025-11-20T00:00:00Z',
      ...district,
    },
  }
}

const HIGH = aggregate({
  level: code('HIGH'),
  participantCount: 128,
  symptomaticCount: 23,
  symptomRate: 18,
  baselineRate: 9,
  groups: [
    { group: code('RESPIRATORY'), rate: 14.1, trend: code('HIGH') },
    { group: code('ENTERIC'), rate: 3.9 },
  ],
  publishedAdvisory: {
    isoWeek: '2025-W46',
    title: '호흡기 증상 보고가 늘었어요',
    publishedAt: '2025-11-17T15:30:00Z',
  },
})

const INSUFFICIENT = aggregate({
  level: code('INSUFFICIENT'),
  insufficientReason: code('NO_BASELINE'),
  participantCount: 41,
})

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

function renderHome(regionCode: string | null = null) {
  window.history.replaceState(null, '', regionCode ? `/?region=${regionCode}` : '/')
  return render(<HomeScreen week={null} regionName="역삼1동" regionCode={regionCode} />)
}

function aggregateRequests() {
  return server.requests().filter((key) => key === AGGREGATE)
}

describe('실데이터 홈 — 행정동 주간 집계', () => {
  it('둘러보는 동네의 집계를 읽는 동안에는 수치 없이 불러오는 중이고, 받으면 상태 카드 · 증상군 · 안내를 그린다', async () => {
    renderHome(YEOKSAM1_CODE)
    await flush()

    expect(screen.getByText('이번 주 동네 상태를 불러오고 있어요')).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/%/)
    expect(aggregateRequests()).toHaveLength(1)

    server.reply(AGGREGATE, okResponse(HIGH))
    await flush()

    expect(screen.queryByText('이번 주 동네 상태를 불러오고 있어요')).toBeNull()
    expect(screen.getByText('참여 128명 · 증상 보고 18%')).toBeTruthy()
    expect(screen.getByText('증상 보고가 지난 4주보다 많이 늘었어요')).toBeTruthy()
    // 주별 비율이 없어 작은 막대 대신 이번 주 비율 글자다. 전주 대비 변화가 없는 장관은 변화 글자가 없다
    expect(screen.queryAllByRole('img')).toHaveLength(0)
    expect(screen.getByText('이번 주 14.1%')).toBeTruthy()
    expect(screen.getByText('이번 주 3.9%')).toBeTruthy()
    expect(screen.queryByText('비슷해요')).toBeNull()
    // 안내는 제목 · 발행일만. 할 일 목록 · 근거는 지어내지 않고, 동네 안내 화면 링크는 그 화면이 아직 목이라 열지 않는다(#256)
    expect(screen.getByText('호흡기 증상 보고가 늘었어요')).toBeTruthy()
    expect(screen.getByText('11월 18일 발행')).toBeTruthy()
    expect(
      screen
        .queryAllByRole('link')
        .some((link) => link.getAttribute('href')?.startsWith('/notice/')),
    ).toBe(false)
    expect(screen.queryByText(/근거:/)).toBeNull()
    // 공식 정보는 단계 없이 공식 정보 화면으로 가는 행만
    const official = screen
      .getAllByRole('link')
      .filter((link) => link.textContent?.includes('질병관리청 공식 정보'))
    expect(official.map((link) => link.getAttribute('href'))).toEqual([
      `/official?region=${YEOKSAM1_CODE}`,
      `/official?region=${YEOKSAM1_CODE}`,
    ])
    expect(document.body.textContent).not.toMatch(/유행주의보/)
    expect(screen.getByText(/^11월 3주 · /)).toBeTruthy()
  })

  it('보이는 동네가 바뀌면 앞 동네의 늦은 응답은 버리고, 머리줄 이름은 집계 응답의 이름이다', async () => {
    const seogyo = '11440660'
    const SEOGYO_AGGREGATE = `GET /api/v1/districts/${seogyo}/weekly-aggregate`
    const { rerender } = render(
      <HomeScreen week={null} regionName="○○동" regionCode={YEOKSAM1_CODE} />,
    )
    await flush()
    rerender(<HomeScreen week={null} regionName="○○동" regionCode={seogyo} />)
    await flush()

    server.reply(
      SEOGYO_AGGREGATE,
      okResponse(aggregate({ ...INSUFFICIENT.district, code: seogyo, name: '서교동' })),
    )
    await flush()
    server.reply(AGGREGATE, okResponse(HIGH))
    await flush()

    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('서교동')
    expect(screen.getByText('41 / 100명')).toBeTruthy()
    expect(screen.queryByText('참여 128명 · 증상 보고 18%')).toBeNull()
  })

  it('자료 부족이면 이유와 무관하게 수치 · 비율 없이 참여 진행만 보인다', async () => {
    renderHome(YEOKSAM1_CODE)
    await flush()
    server.reply(AGGREGATE, okResponse(INSUFFICIENT))
    await flush()

    expect(screen.getByText('아직 변화를 판단할 자료가 부족해요')).toBeTruthy()
    expect(screen.getByText('41 / 100명')).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/%/)
  })

  it('읽지 못하면 빨강 상자와 다시 시도이고, 다시 시도하면 다시 읽는다', async () => {
    const user = userEvent.setup()
    renderHome(YEOKSAM1_CODE)
    await flush()
    server.reply(AGGREGATE, errorResponse('GATEWAY_004', 504))
    await flush()

    expect(screen.getByRole('alert').textContent).toContain('이번 주 동네 상태를 불러오지 못했어요')
    expect(document.body.textContent).not.toMatch(/%/)

    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    expect(aggregateRequests()).toHaveLength(2)
    expect(screen.getByText('이번 주 동네 상태를 불러오고 있어요')).toBeTruthy()

    server.reply(AGGREGATE, okResponse(HIGH))
    await flush()
    expect(screen.getByText('참여 128명 · 증상 보고 18%')).toBeTruthy()
  })

  it('집계가 모르는 동네(AGGREGATE_001)면 동네를 고르라고 안내한다', async () => {
    renderHome(YEOKSAM1_CODE)
    await flush()
    server.reply(AGGREGATE, errorResponse('AGGREGATE_001', 404))
    await flush()

    expect(screen.getByText('동네를 고르면 이번 주 동네 상태를 보여 드려요.')).toBeTruthy()
    expect(screen.getByRole('button', { name: '동네 고르기' })).toBeTruthy()
  })

  it('둘러보는 동네가 없는 비회원은 요청 없이 동네를 고르라고 안내한다', async () => {
    await act(() => restoreSession())
    renderHome()
    await flush()

    expect(screen.getByText('동네를 고르면 이번 주 동네 상태를 보여 드려요.')).toBeTruthy()
    expect(aggregateRequests()).toHaveLength(0)
  })

  it('둘러보는 동네가 없는 회원은 내 동네를 읽은 뒤 그 동네의 집계를 읽는다', async () => {
    renderHome()
    setSession(memberToken())
    await flush()
    // 내 동네를 읽는 동안에는 동네를 고르라고 하지 않는다
    expect(screen.getByText('이번 주 동네 상태를 불러오고 있어요')).toBeTruthy()
    expect(aggregateRequests()).toHaveLength(0)

    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(
      REGION,
      okResponse({
        code: YEOKSAM1_CODE,
        name: '역삼1동',
        sigungu: '서울특별시 강남구',
        abolished: false,
      }),
    )
    await flush()
    expect(aggregateRequests()).toHaveLength(1)

    server.reply(AGGREGATE, okResponse(HIGH))
    await flush()
    expect(screen.getByText('참여 128명 · 증상 보고 18%')).toBeTruthy()
  })

  it('내 동네를 읽지 못하면 빨강 상자이고, 다시 시도하면 내 동네를 다시 읽은 뒤 집계를 읽는다', async () => {
    const user = userEvent.setup()
    renderHome()
    setSession(memberToken())
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, errorResponse('REGION_004', 503))
    await flush()

    expect(screen.getByRole('alert').textContent).toContain('이번 주 동네 상태를 불러오지 못했어요')
    expect(aggregateRequests()).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    expect(server.requests().filter((key) => key === REGION)).toHaveLength(2)

    server.reply(
      REGION,
      okResponse({
        code: YEOKSAM1_CODE,
        name: '역삼1동',
        sigungu: '서울특별시 강남구',
        abolished: false,
      }),
    )
    await flush()
    expect(aggregateRequests()).toHaveLength(1)
  })
})
