// @vitest-environment jsdom
import type { ReactNode } from 'react'
import { renderToString } from 'react-dom/server'

import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from '@/features/auth/auth-client'
import {
  listSessions,
  loginWithEmail,
  resetMockSession,
  revokeOtherSessions,
  revokeSession,
} from '@/features/auth/auth-client'
import { submitReport } from '@/features/report/report-client'
import { getSessionSnapshot, resetSessionForTests, setSession } from '@/lib/session/session-store'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import {
  errorResponse,
  holdRequests,
  memberToken,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { DevicesScreen } from './devices-screen'
import { MeTrailProvider } from './me-trail'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
let pathname = '/me/devices'
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => pathname,
  useRouter: () => router,
}))

vi.mock('@/features/auth/auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return {
    ...actual,
    listSessions: vi.fn(actual.listSessions),
    revokeSession: vi.fn(actual.revokeSession),
    revokeOtherSessions: vi.fn(actual.revokeOtherSessions),
  }
})

// 앱에서는 루트 레이아웃의 NavTrailProvider(앱 안 이동 기록)가 내 정보 레이아웃을 감싼다
function withTrail(children: ReactNode) {
  return (
    <NavTrailProvider>
      <MeTrailProvider>{children}</MeTrailProvider>
    </NavTrailProvider>
  )
}

function renderDevices(props: { regionCode?: string } = {}) {
  return render(withTrail(<DevicesScreen regionName="○○동" {...props} />))
}

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  let reject: () => void = () => {}
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = () => fail(new Error('mock failure'))
  })
  return { promise, resolve, reject }
}

/** 기기 행. 목록(ul) 안 항목을 기기 이름으로 찾는다 */
function row(name: string) {
  const item = screen.getByText(name).closest('li')
  if (!item) throw new Error(`${name} 행이 없다`)
  return item
}

beforeEach(async () => {
  search = ''
  pathname = '/me/devices'
  resetMockSession()
  vi.clearAllMocks()
  await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('DevicesScreen 목록', () => {
  it('머리줄 알림(종)은 동네 · 덮어쓰기를 남긴 채 알림 설정으로 간다', async () => {
    search =
      'region=11440660&mock-auth=member&mock-provider=email&mock-push=supported&confirm=unknown'
    renderDevices({ regionCode: '11440660' })

    const [bell] = await screen.findAllByRole('button', { name: '알림 설정' })
    await userEvent.setup().click(bell as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(
      '/me/notifications?region=11440660&mock-auth=member&mock-provider=email&mock-push=supported',
    )
  })

  it('불러오는 동안 안내를 보이고, 받으면 이 기기를 맨 위에 다른 기기를 최근 순서로 그린다', async () => {
    renderDevices()

    // 늘 그려 둔 안내 영역(role=status) 안에 보인다
    expect(
      screen.getByText('기기 목록을 불러오고 있어요').closest('[role="status"]'),
    ).not.toBeNull()
    const list = await screen.findByRole('list', { name: '로그인한 기기 목록' })
    expect(
      within(list)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([
      'iPhone · Safari지금 사용 중이 기기',
      'Mac · Chrome마지막 사용 11월 20일 21:14로그아웃',
      'Galaxy · 삼성 인터넷마지막 사용 11월 2일 08:03로그아웃',
    ])
    expect(screen.queryByText('기기 목록을 불러오고 있어요')).toBeNull()
    expect(screen.getByText('모르는 기기가 있으면 로그아웃해 주세요.')).toBeDefined()
  })

  it('기기 이름과 마지막 사용 시각만 그린다 — IP · 지역은 없다', async () => {
    renderDevices()
    const list = await screen.findByRole('list', { name: '로그인한 기기 목록' })
    expect(list.textContent).not.toMatch(/\d+\.\d+\.\d+\.\d+|서울|위치|IP/)
  })

  it('이 기기에는 로그아웃 버튼이 없다 (로그아웃은 내 정보에서)', async () => {
    renderDevices()
    await screen.findByRole('list', { name: '로그인한 기기 목록' })
    expect(within(row('iPhone · Safari')).queryByRole('button')).toBeNull()
    expect(within(row('iPhone · Safari')).getByText('이 기기')).toBeDefined()
    // 버튼이 여러 개라 기기 이름을 이름에 붙인다
    expect(screen.getByRole('button', { name: 'Mac · Chrome 로그아웃' })).toBeDefined()
  })

  it('불러오지 못하면 빨강 상자로 알리고, 다시 시도하면 목록을 그린다', async () => {
    const failOnce = deferred<authClient.DeviceSession[]>()
    vi.mocked(listSessions).mockReturnValueOnce(failOnce.promise)
    renderDevices()

    await act(async () => {
      failOnce.reject()
      await failOnce.promise.catch(() => {})
    })
    expect(screen.getByRole('alert').textContent).toContain(
      '기기 목록을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(screen.queryByRole('button', { name: '다른 기기에서 모두 로그아웃' })).toBeNull()

    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('list', { name: '로그인한 기기 목록' })).toBeDefined()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(listSessions).toHaveBeenCalledTimes(2)
  })

  it('목 재현 이메일(sessions-fail@example.com)이면 불러오지 못한다', async () => {
    await loginWithEmail('sessions-fail@example.com', 'dongne2026', 'mock')
    renderDevices()
    expect((await screen.findByRole('alert')).textContent).toContain('불러오지 못했어요')
  })
})

describe('DevicesScreen 다른 기기 로그아웃', () => {
  it('한 기기를 로그아웃하면 목록에서 빼고 알린 뒤 목록으로 포커스를 옮긴다', async () => {
    renderDevices()
    await screen.findByRole('list', { name: '로그인한 기기 목록' })

    await userEvent.setup().click(screen.getByRole('button', { name: 'Mac · Chrome 로그아웃' }))
    expect(revokeSession).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'mock-session-mac', current: false }),
      'mock',
    )
    expect(screen.queryByText('Mac · Chrome')).toBeNull()
    expect(
      screen.getByText('Mac · Chrome에서 로그아웃했어요').closest('[role="status"]'),
    ).not.toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('list', { name: '로그인한 기기 목록' }))
    // 목 서버에서도 빠졌다
    expect((await listSessions('mock')).map((session) => session.deviceName)).toEqual([
      'iPhone · Safari',
      'Galaxy · 삼성 인터넷',
    ])
  })

  it('다른 기기에서 모두 로그아웃하면 이 기기만 남고 버튼이 사라진다', async () => {
    renderDevices()
    await screen.findByRole('list', { name: '로그인한 기기 목록' })

    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: '다른 기기에서 모두 로그아웃' }))
    expect(revokeOtherSessions).toHaveBeenCalledTimes(1)
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    expect(screen.getByText('다른 기기에서 모두 로그아웃했어요')).toBeDefined()
    expect(screen.queryByRole('button', { name: '다른 기기에서 모두 로그아웃' })).toBeNull()
  })

  it('보내는 중에는 로그아웃 버튼이 모두 꺼지고 두 번 보내지 않는다', async () => {
    const pending = deferred<void>()
    vi.mocked(revokeSession).mockReturnValueOnce(pending.promise)
    renderDevices()
    await screen.findByRole('list', { name: '로그인한 기기 목록' })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Mac · Chrome 로그아웃' }))
    const buttons = [
      screen.getByRole('button', { name: 'Mac · Chrome 로그아웃' }),
      screen.getByRole('button', { name: 'Galaxy · 삼성 인터넷 로그아웃' }),
      screen.getByRole('button', { name: '다른 기기에서 모두 로그아웃' }),
    ]
    buttons.forEach((button) => expect(button.getAttribute('aria-disabled')).toBe('true'))
    for (const button of buttons) await user.click(button)
    expect(revokeSession).toHaveBeenCalledTimes(1)
    expect(revokeOtherSessions).not.toHaveBeenCalled()

    await act(async () => {
      pending.resolve()
      await pending.promise.catch(() => {})
    })
    expect(screen.queryByText('Mac · Chrome')).toBeNull()
    expect(
      screen
        .getByRole('button', { name: 'Galaxy · 삼성 인터넷 로그아웃' })
        .getAttribute('aria-disabled'),
    ).toBeNull()
  })

  it('로그아웃하지 못하면 목록을 그대로 두고 빨강 상자로 알린다 — 다시 누를 수 있다', async () => {
    await loginWithEmail('session-revoke-fail@example.com', 'dongne2026', 'mock')
    renderDevices()
    await screen.findByRole('list', { name: '로그인한 기기 목록' })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Mac · Chrome 로그아웃' }))
    expect(screen.getByRole('alert').textContent).toContain(
      '로그아웃하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(screen.getByText('Mac · Chrome')).toBeDefined()

    await user.click(screen.getByRole('button', { name: '다른 기기에서 모두 로그아웃' }))
    expect(revokeOtherSessions).toHaveBeenCalledTimes(1)
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
  })
})

describe('DevicesScreen 회원 가드', () => {
  it('비회원이 주소로 들어오면 아무 것도 그리지 않고 로그인으로 기록을 바꿔 간다', () => {
    resetMockSession()
    const { container } = renderDevices()
    expect(container.textContent).toBe('')
    // 로그인 뒤 이 화면으로 돌아오게 next 를 싣는다(#140)
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fme%2Fdevices')
    expect(listSessions).not.toHaveBeenCalled()
  })

  it('하이드레이션 첫 그림(서버와 같은 비회원)으로 회원을 튕기지 않는다', async () => {
    const ui = withTrail(<DevicesScreen regionName="○○동" />)
    const container = document.createElement('div')
    document.body.appendChild(container)
    // 서버는 목 세션을 몰라 비회원으로 그린다 — 아무 것도 없다
    container.innerHTML = renderToString(ui)
    expect(container.textContent).toBe('')

    render(ui, { container, hydrate: true })
    expect(await screen.findByRole('list', { name: '로그인한 기기 목록' })).toBeDefined()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('?mock-auth= 덮어쓰기로도 회원 화면을 연다', async () => {
    resetMockSession()
    search = 'mock-auth=member'
    renderDevices()
    expect(await screen.findByRole('list', { name: '로그인한 기기 목록' })).toBeDefined()
    expect(router.replace).not.toHaveBeenCalled()
  })
})

describe('DevicesScreen 뒤로 가기 · 셸', () => {
  it('주소로 바로 들어왔으면 뒤로가 동네 · 덮어쓰기를 남긴 내 정보로 기록을 바꿔 간다', async () => {
    search = 'region=11440660&mock-auth=member&mock-provider=email&other=1'
    renderDevices({ regionCode: '11440660' })
    await screen.findByRole('list', { name: '로그인한 기기 목록' })

    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith(
      '/me?region=11440660&mock-auth=member&mock-provider=email',
    )
    expect(router.back).not.toHaveBeenCalled()
  })

  it('앱 안에서 내 정보를 거쳐 왔으면 기록을 되돌린다 (데스크톱 "내 정보" 도 같다)', async () => {
    pathname = '/me'
    const { rerender } = render(withTrail(<div />))
    pathname = '/me/devices'
    rerender(withTrail(<DevicesScreen regionName="○○동" />))
    await screen.findByRole('list', { name: '로그인한 기기 목록' })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '뒤로' }))
    await user.click(screen.getByRole('button', { name: '내 정보로 돌아가기' }))
    expect(router.back).toHaveBeenCalledTimes(2)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('탭바 · 데스크톱 메뉴에 동네를 남기고, 머리줄 보고 버튼은 홈의 보고 진입으로 간다', async () => {
    renderDevices({ regionCode: '11440660' })
    await screen.findByRole('list', { name: '로그인한 기기 목록' })

    // 데스크톱 머리줄(가운데 홈 · 지도 + 오른쪽 끝 내 정보)과 태블릿 탭바
    const [desktopMenu, tabBar] = screen.getAllByRole('navigation', {
      name: '주요 메뉴',
      hidden: true,
    })
    const hrefs = (nav: Element | undefined) =>
      [...(nav?.querySelectorAll('a') ?? [])].map((a) => a.getAttribute('href'))
    expect(hrefs(desktopMenu)).toEqual(['/?region=11440660', '/map?region=11440660'])
    expect(hrefs(screen.getByRole('navigation', { name: '계정 메뉴', hidden: true }))).toEqual([
      '/me?region=11440660',
    ])
    expect(hrefs(tabBar)).toEqual([
      '/?region=11440660',
      '/map?region=11440660',
      '/me?region=11440660',
    ])
    // 태블릿 머리줄 · 데스크톱 머리줄 두 곳에 있다 (폭마다 하나만 보인다)
    const reports = screen.getAllByRole('button', { name: '이번 주 건강 보고하기' })
    expect(reports).toHaveLength(2)
    await userEvent.setup().click(reports[0] as HTMLElement)
    // 이메일 로그인은 건강정보 동의 전이다
    expect(router.push).toHaveBeenCalledWith('/?region=11440660&report=health-consent')
  })

  it('동의한 회원이 이번 주 보고를 보냈으면 머리줄 보고 버튼(태블릿 · 데스크톱)이 완료 · 수정하기다', async () => {
    // 로그인한 세션에 보낸 보고를 둔다. 다음 테스트의 resetMockSession 이 세션을 바꾸며 지운다
    await submitReport({ kind: 'none' }, null, 'mock')
    search = 'mock-auth=member'
    renderDevices({ regionCode: '11440660' })
    await screen.findByRole('list', { name: '로그인한 기기 목록' })

    const reports = screen.getAllByRole('button', { name: '이번 주 보고 완료 · 수정하기' })
    expect(reports).toHaveLength(2)
    await userEvent.setup().click(reports[0] as HTMLElement)
    expect(router.push).toHaveBeenCalledWith('/?region=11440660&report=start')
  })

  it('화면 제목(h1)은 폭마다 하나다 — 모바일 · 태블릿은 머리줄, 데스크톱은 본문 위', async () => {
    renderDevices()
    await screen.findByRole('list', { name: '로그인한 기기 목록' })
    const titles = screen.getAllByRole('heading', { level: 1, name: '로그인한 기기' })
    expect(titles).toHaveLength(2)
    expect(titles[0]?.closest('header')?.classList).toContain('desktop:hidden')
    expect(titles[1]?.parentElement?.classList).toContain('desktop:flex')
  })
})

describe('DevicesScreen 실데이터 (auth API, #166)', () => {
  beforeEach(() => {
    resetSessionForTests()
    selectApiSource()
  })
  afterEach(() => resetApiSession())

  /** 요청이 나가고 응답의 then 이 돌 때까지 */
  const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))

  const SESSIONS = {
    sessions: [
      {
        sessionId: '9b1e7c22-4d5f-4b6a-8c7d-1e2f3a4b5c6d',
        deviceLabel: 'Mac · Chrome',
        createdAt: '2026-09-20T01:00:00Z',
        // UTC 15:30 은 한국 시각으로 다음 날 00:30 이다
        lastUsedAt: '2026-09-30T15:30:00Z',
        current: false,
      },
      {
        sessionId: '3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61',
        deviceLabel: 'iPhone · Safari',
        createdAt: '2026-10-01T00:30:00Z',
        lastUsedAt: '2026-10-01T05:12:00Z',
        current: true,
      },
    ],
    totalCount: 2,
  }

  it('GET /api/v1/auth/sessions 를 그린다 — 마지막 사용은 한국 시각, 예시 기기는 없다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderDevices()
    await flush()
    expect(server.requests()).toEqual(['GET /api/v1/auth/sessions'])
    expect(listSessions).toHaveBeenCalledWith('api')

    act(() => server.reply('GET /api/v1/auth/sessions', okResponse(SESSIONS)))
    const list = await screen.findByRole('list', { name: '로그인한 기기 목록' })
    expect(
      within(list)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([
      'iPhone · Safari지금 사용 중이 기기',
      'Mac · Chrome마지막 사용 10월 1일 00:30로그아웃',
    ])
    expect(screen.queryByText('Galaxy · 삼성 인터넷')).toBeNull()
  })

  it('불러오지 못하면 예시 목록으로 채우지 않고 빨강 상자로 알린다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderDevices()
    await flush()
    act(() => server.reply('GET /api/v1/auth/sessions', errorResponse('GATEWAY_003', 503)))
    expect((await screen.findByRole('alert')).textContent).toContain('불러오지 못했어요')
    expect(screen.queryByText('iPhone · Safari')).toBeNull()
  })

  it('다른 기기를 로그아웃하면 그 세션 id 로 DELETE 하고, 이 기기 세션은 그대로다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderDevices()
    await flush()
    act(() => server.reply('GET /api/v1/auth/sessions', okResponse(SESSIONS)))
    await screen.findByRole('list', { name: '로그인한 기기 목록' })

    await userEvent.setup().click(screen.getByRole('button', { name: 'Mac · Chrome 로그아웃' }))
    await flush()
    expect(server.requests()).toContain(
      'DELETE /api/v1/auth/sessions/9b1e7c22-4d5f-4b6a-8c7d-1e2f3a4b5c6d',
    )
    act(() =>
      server.reply(
        'DELETE /api/v1/auth/sessions/9b1e7c22-4d5f-4b6a-8c7d-1e2f3a4b5c6d',
        okResponse(null),
      ),
    )
    await flush()
    expect(screen.queryByText('Mac · Chrome')).toBeNull()
    expect(screen.getByText('Mac · Chrome에서 로그아웃했어요')).toBeDefined()
    expect(getSessionSnapshot().status).toBe('member')
  })
})
