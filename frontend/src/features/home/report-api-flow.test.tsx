// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMemberInfoForTests, startMemberInfo } from '@/features/auth/member-info'
import { resetCurrentReportForTests, startCurrentReport } from '@/features/report/current-report'
import { writeSessionHint } from '@/lib/session/session-hint'
import {
  getSessionSnapshot,
  restoreSession,
  setSession,
  startSession,
} from '@/lib/session/session-store'
import { SESSION_CHANNEL_NAME } from '@/lib/session/session-sync'
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
import { HOME_MOCKS } from './mock'

// 테스트에는 Next 라우터가 없다. 주소는 jsdom 의 지금 주소를 읽는다(보고 흐름 단계는 history 로 바뀐다)
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
  useRouter: () => router,
  usePathname: () => window.location.pathname,
}))

/* 실데이터 모드의 홈 보고 진입 · 보고 흐름 (#165). 세션 · 회원 정보 · 이번 주 보고 저장소를 루트 레이아웃처럼 켜고 가짜 서버로 답한다 */

const INFO = 'GET /api/v1/members/me'
const REGION = 'GET /api/v1/members/me/region'
const CURRENT = 'GET /api/v1/reports/current'
const PUT = 'PUT /api/v1/reports/current'
const DELETE = 'DELETE /api/v1/reports/current'
const REISSUE = 'POST /api/v1/auth/token/reissue'
const YEOKSAM1 = {
  code: '11680640',
  name: '역삼1동',
  sigungu: '서울특별시 강남구',
  abolished: false,
}
const SENT_NONE = {
  isoWeek: '2026-W40',
  districtCode: '11680640',
  symptomGroups: [],
  reportedAt: '2026-10-01T15:30:00Z',
  updatedAt: '2026-10-01T15:30:00Z',
}

const REPORT = '이번 주 건강 보고하기'
const REPORTED = '이번 주 보고 완료 · 수정하기'

/** 요청이 나가고(API 계층은 비동기다) 기다리던 응답의 then 이 돌 때까지 */
const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))

let server: ReturnType<typeof holdRequests>
let stops: (() => void)[] = []

beforeEach(() => {
  vi.clearAllMocks()
  selectApiSource()
  resetMemberInfoForTests()
  resetCurrentReportForTests()
  server = holdRequests()
  stops = [startSession(), startMemberInfo(), startCurrentReport()]
})

afterEach(() => {
  stops.forEach((stop) => stop())
  resetMemberInfoForTests()
  resetCurrentReportForTests()
  resetApiSession()
  vi.restoreAllMocks()
})

/** 동의한 회원으로 로그인해 내 동네(역삼1동)와 이번 주 보고를 읽은 상태 */
async function signedIn(current: unknown = null) {
  setSession(memberToken())
  await flush()
  server.reply(INFO, okResponse(myInfoBody()))
  server.reply(REGION, okResponse(YEOKSAM1))
  server.reply(CURRENT, okResponse(current))
  await flush()
}

function renderHome(path = '/') {
  window.history.replaceState(null, '', path)
  const view = render(<HomeScreen week={HOME_MOCKS.normal} />)
  return { ...view, refresh: () => view.rerender(<HomeScreen week={HOME_MOCKS.normal} />) }
}

function dialogTitle() {
  return document.querySelector('dialog[open] h2')?.textContent ?? null
}

/** 보낸 요청의 본문 */
function sentBody(key: string): unknown {
  const calls = server.fetchMock.mock.calls as [string, RequestInit | undefined][]
  const call = calls.find(
    ([url, init]) => `${init?.method ?? 'GET'} ${new URL(url).pathname}` === key,
  )
  const body = call?.[1]?.body
  return typeof body === 'string' ? JSON.parse(body) : undefined
}

describe('실데이터 홈 보고 버튼 — 이번 주 보고', () => {
  it('읽는 동안에는 완료로 보이지 않고(보고 흐름은 확인 중 · 고르기 꺼짐), 보낸 보고를 읽으면 완료 · 수정하기다', async () => {
    setSession(memberToken())
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, okResponse(YEOKSAM1))
    renderHome('/?report=start')
    await flush()
    expect(screen.getAllByRole('button', { name: REPORT })).toHaveLength(2)
    expect(await screen.findByText('이번 주 보고를 확인하고 있어요.')).toBeTruthy()
    expect(screen.getByRole('button', { name: '증상 없었어요' })).toHaveProperty('disabled', true)

    server.reply(CURRENT, okResponse(SENT_NONE))
    await flush()
    expect(screen.getAllByRole('button', { name: REPORTED })).toHaveLength(2)
    expect(screen.queryByText('이번 주 보고를 확인하고 있어요.')).toBeNull()
    // 보고한 날은 첫 보고 시각(UTC 15:30)의 KST 날짜다
    expect(screen.getByText(/10월 2일에 보고했어요/)).toBeTruthy()
  })

  it('읽지 못하면 보고하기이고, 보고 흐름은 불러오지 못했다고 알리고 다시 불러올 수 있다', async () => {
    setSession(memberToken())
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, okResponse(YEOKSAM1))
    server.reply(CURRENT, errorResponse('GATEWAY_003', 503))
    await flush()
    const user = userEvent.setup()
    renderHome('/?report=start')

    expect(screen.getAllByRole('button', { name: REPORT })).toHaveLength(2)
    expect(await screen.findByText(/이번 주에 보낸 보고를 불러오지 못했어요/)).toBeTruthy()

    await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
    await flush()
    expect(server.requests().filter((key) => key === CURRENT)).toHaveLength(2)
  })

  it('보고 권한이 없는 회원(reportWritable false)은 이번 주 보고를 읽지 않는다', async () => {
    setSession(memberToken({ reportWritable: false }))
    await flush()
    renderHome()
    expect(server.requests()).not.toContain(CURRENT)
    expect(screen.getAllByRole('button', { name: REPORT })).toHaveLength(2)
  })
})

describe('실데이터 새로고침한 보고 완료 (?report=done)', () => {
  it('세션을 되살리는 동안은 로그인 시트로 바꾸지 않고 기다렸다가, 회원이 되면 이번 주 보고를 읽어 완료를 그대로 보인다', async () => {
    writeSessionHint(true)
    void restoreSession()
    expect(getSessionSnapshot()).toEqual({ status: 'restoring' })
    const { refresh } = renderHome('/?report=done')
    // 보고 진입 정리(setTimeout 0)가 돌 틈을 준다
    await flush()
    await flush()

    expect(window.location.search).toBe('?report=done')
    expect(dialogTitle()).toBeNull()
    expect(router.replace).not.toHaveBeenCalled()

    server.reply(REISSUE, okResponse(memberToken()))
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, okResponse(YEOKSAM1))
    // 이번 주 보고를 읽는 동안은 시작 단계(확인 중)로 보인다 — 주소는 그대로다
    refresh()
    expect(await screen.findByText('이번 주 보고를 확인하고 있어요.')).toBeTruthy()

    server.reply(CURRENT, okResponse(SENT_NONE))
    await flush()
    await flush()
    refresh()
    expect(window.location.search).toBe('?report=done')
    expect(dialogTitle()).toBe('이번 주 보고를 받았어요')
  })

  it('되살린 세션이 비회원이면(재로그인) 그때 로그인 시트로 바꾼다', async () => {
    writeSessionHint(true)
    void restoreSession()
    const { refresh } = renderHome('/?report=done')
    await flush()
    expect(window.location.search).toBe('?report=done')

    server.reply(REISSUE, errorResponse('AUTH_014', 401))
    await flush()
    await flush()
    refresh()
    expect(window.location.search).toBe('?report=login')
  })
})

describe('실데이터 보고 흐름 — 보내기 · 되돌리기 · 오류', () => {
  it('증상 없음은 내 동네 코드 · 빈 증상군으로 PUT 하고, 받은 보고로 완료가 된다', async () => {
    await signedIn()
    const user = userEvent.setup()
    const { refresh } = renderHome('/?report=start')

    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()
    expect(sentBody(PUT)).toEqual({ districtCode: '11680640', symptomGroups: [] })

    server.reply(PUT, okResponse(SENT_NONE))
    await flush()
    refresh()
    expect(dialogTitle()).toBe('이번 주 보고를 받았어요')
    expect(screen.getAllByRole('button', { name: REPORTED })).toHaveLength(2)
  })

  it('이 탭은 미보고로 알았어도 응답이 수정(reportedAt ≠ updatedAt)이면 되돌리기를 주지 않는다 — 다른 기기의 보고를 지우지 않게', async () => {
    await signedIn()
    const user = userEvent.setup()
    const { refresh } = renderHome('/?report=start')

    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()
    server.reply(
      PUT,
      okResponse({
        ...SENT_NONE,
        reportedAt: '2026-09-29T01:00:00Z',
        updatedAt: '2026-10-01T15:30:00Z',
      }),
    )
    await flush()
    refresh()

    expect(dialogTitle()).toBe('이번 주 보고를 받았어요')
    expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull()
    expect(screen.queryByText('증상 없음으로 보냈어요')).toBeNull()
  })

  it('되돌리는 동안 수정하기 · 함께 채우기를 끄고, 그사이 닫았으면 응답 뒤 다시 열지 않는다', async () => {
    await signedIn()
    const user = userEvent.setup()
    const { refresh } = renderHome('/?report=start')
    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()
    server.reply(PUT, okResponse(SENT_NONE))
    await flush()
    refresh()

    await user.click(screen.getByRole('button', { name: '되돌리기' }))
    refresh()
    expect(screen.getByRole('button', { name: '보고 수정하기' })).toHaveProperty('disabled', true)
    expect(screen.getByRole('button', { name: '우리 동네 자료 함께 채우기' })).toHaveProperty(
      'disabled',
      true,
    )

    await user.click(screen.getByRole('button', { name: '우리 동네 변화 보기' }))
    refresh()
    expect(window.location.search).toBe('')

    server.reply(DELETE, okResponse(null))
    await flush()
    refresh()
    expect(window.location.search).toBe('')
    expect(dialogTitle()).toBeNull()
    expect(screen.getAllByRole('button', { name: REPORT })).toHaveLength(2)
  })

  it('되돌리기는 DELETE 이고, 실패하면 완료에 남아 다시 시도를 알린다', async () => {
    await signedIn()
    const user = userEvent.setup()
    const { refresh } = renderHome('/?report=start')
    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()
    server.reply(PUT, okResponse(SENT_NONE))
    await flush()
    refresh()

    await user.click(screen.getByRole('button', { name: '되돌리기' }))
    await flush()
    expect(server.requests()).toContain(DELETE)
    server.reply(DELETE, errorResponse('GATEWAY_004', 504))
    await flush()
    refresh()

    expect(dialogTitle()).toBe('이번 주 보고를 받았어요')
    expect(screen.getByText('되돌리지 못했어요. 잠시 뒤 다시 시도해 주세요.')).toBeTruthy()

    // 다시 시도해 성공하면 지우고 시작 단계로 돌아간다
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    server.reply(DELETE, okResponse(null))
    await flush()
    refresh()
    expect(window.location.search).toBe('?report=start')
    expect(screen.getAllByRole('button', { name: REPORT })).toHaveLength(2)
  })

  it('보고 권한이 없으면(SECURITY_006) 세션 요약을 다시 맞추고, 동의가 없다고 바뀌면 건강정보 동의 시트로 바꾼다', async () => {
    await signedIn()
    const user = userEvent.setup()
    const { refresh } = renderHome('/?report=start')

    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()
    server.reply(PUT, errorResponse('SECURITY_006', 403))
    await flush()
    expect(server.requests()).toContain(REISSUE)

    server.reply(
      REISSUE,
      okResponse(memberToken({ accessToken: 'access-2', reportWritable: false })),
    )
    await flush()
    // 홈의 보고 진입 정리는 그림이 끝난 뒤(setTimeout 0)에 주소를 바꾼다
    await flush()
    refresh()

    expect(getSessionSnapshot()).toMatchObject({ summary: { reportWritable: false } })
    expect(window.location.search).toBe('?report=health-consent')
    // 동의 시트는 처음 열 때 받는다(지연 로드, #184)
    await waitFor(() => expect(dialogTitle()).toBe('증상 보고에 동의해 주세요'))
  })

  it('폐지된 동네(REPORT_003)면 내 동네를 다시 읽고, 폐지로 확인되면 동네 다시 고르기로 보낸다', async () => {
    await signedIn()
    const user = userEvent.setup()
    renderHome('/?report=start')

    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()
    server.reply(PUT, errorResponse('REPORT_003', 400))
    await flush()

    expect(server.requests().filter((key) => key === REGION)).toHaveLength(2)
    expect(screen.getByText('내 동네 정보가 바뀌었어요. 확인한 뒤 다시 보내 주세요.')).toBeTruthy()
    expect(router.replace).not.toHaveBeenCalled()

    server.reply(REGION, okResponse({ ...YEOKSAM1, abolished: true }))
    await flush()
    expect(router.replace).toHaveBeenCalledWith(expect.stringContaining('/setup/region'))
    expect(router.replace).toHaveBeenCalledWith(expect.stringContaining('reselect=1'))
  })

  it('동시 제출(REPORT_001) · 일시 장애면 잠시 뒤 다시 보내라고 알린다', async () => {
    await signedIn()
    const user = userEvent.setup()
    renderHome('/?report=start')

    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()
    server.reply(PUT, errorResponse('REPORT_001', 409))
    await flush()

    expect(screen.getByText('보내지 못했어요. 잠시 뒤 다시 보내 주세요.')).toBeTruthy()
    expect(window.location.search).toBe('?report=start')
  })

  it('내 동네를 읽지 못했으면 보내지 않고 알린 뒤 다시 읽는다', async () => {
    setSession(memberToken())
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, errorResponse('REGION_004', 503))
    server.reply(CURRENT, okResponse(null))
    await flush()
    const user = userEvent.setup()
    renderHome('/?report=start')

    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()

    expect(server.requests()).not.toContain(PUT)
    expect(
      screen.getByText('내 동네를 불러오지 못해 보내지 못했어요. 잠시 뒤 다시 보내 주세요.'),
    ).toBeTruthy()
    expect(server.requests().filter((key) => key === REGION)).toHaveLength(2)
  })

  it('내 동네가 없으면 보내지 않고 고르라고 알린다', async () => {
    setSession(memberToken())
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, okResponse(null))
    server.reply(CURRENT, okResponse(null))
    await flush()
    const user = userEvent.setup()
    renderHome('/?report=start')

    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))

    expect(server.requests()).not.toContain(PUT)
    expect(
      screen.getByText('내 동네를 고르면 보낼 수 있어요. 내 정보의 내 동네에서 골라 주세요.'),
    ).toBeTruthy()
  })
})

describe('실데이터 보고 흐름 — 다른 탭에서 내 동네를 바꿨을 때 (#190)', () => {
  const YEOKSAM2 = {
    code: '11680650',
    name: '역삼2동',
    sigungu: '서울특별시 강남구',
    abolished: false,
  }
  const LOADING_MESSAGE = '내 동네를 불러오고 있어요. 잠시 뒤 다시 보내 주세요.'
  const FAILED_MESSAGE = '내 동네를 불러오지 못해 보내지 못했어요. 잠시 뒤 다시 보내 주세요.'

  /** 같은 브라우저의 다른 탭이 보내는 알림. 이 탭의 세션 통로(가짜 BroadcastChannel)로만 전한다 */
  let postFromOtherTab: (data: unknown) => void = () => {}

  beforeEach(() => {
    const listeners: ((event: MessageEvent) => void)[] = []
    vi.stubGlobal(
      'BroadcastChannel',
      vi.fn(function (name: string) {
        const channel = {
          onmessage: null as ((event: MessageEvent) => void) | null,
          postMessage: () => {},
          close: () => {},
        }
        if (name === SESSION_CHANNEL_NAME) {
          listeners.push((event) => channel.onmessage?.(event))
        }
        return channel
      }),
    )
    stops.push(startSession())
    postFromOtherTab = (data) => {
      listeners.forEach((listener) => listener(new MessageEvent('message', { data })))
    }
  })

  const regionChanged = () => {
    act(() => postFromOtherTab({ type: 'region-changed', memberId: '1843956734582784' }))
  }

  it('알림을 받으면 다시 읽는 동안 · 읽지 못하면 보내지 않고, 다시 읽은 뒤에는 새 동네로 보낸다', async () => {
    await signedIn()
    const user = userEvent.setup()
    renderHome('/?report=start')

    regionChanged()
    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()
    expect(server.requests()).not.toContain(PUT)
    expect(screen.getByText(LOADING_MESSAGE)).toBeTruthy()

    server.reply(REGION, errorResponse('REGION_004', 503))
    await flush()
    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    await flush()
    expect(server.requests()).not.toContain(PUT)
    expect(await screen.findByText(FAILED_MESSAGE)).toBeTruthy()

    // 실패 안내와 함께 다시 읽었다
    server.reply(REGION, okResponse(YEOKSAM2))
    await flush()
    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    await flush()
    expect(sentBody(PUT)).toEqual({ districtCode: '11680650', symptomGroups: [] })
  })

  it('읽지 못한 상태에서 알림을 받아도 다시 읽고, 받은 새 동네로 보낸다', async () => {
    setSession(memberToken())
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, errorResponse('REGION_004', 503))
    server.reply(CURRENT, okResponse(null))
    await flush()
    const user = userEvent.setup()
    renderHome('/?report=start')

    regionChanged()
    await flush()
    expect(server.requests().filter((key) => key === REGION)).toHaveLength(2)
    server.reply(REGION, okResponse(YEOKSAM2))
    await flush()

    await user.click(await screen.findByRole('button', { name: '증상 없었어요' }))
    await flush()
    expect(sentBody(PUT)).toEqual({ districtCode: '11680650', symptomGroups: [] })
  })
})
