// @vitest-environment jsdom
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { loginWithEmail, resetMockSession, saveRegion } from '@/features/auth/auth-client'
import { resetMemberInfoForTests, startMemberInfo } from '@/features/auth/member-info'
import {
  addInterestRegion,
  resetMockInterestRegions,
} from '@/features/region/interest-region-client'
import { setSession } from '@/lib/session/session-store'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import {
  errorResponse,
  holdRequests,
  memberToken,
  myInfoBody,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { InterestRegionsScreen } from './interest-regions-screen'
import { MeTrailProvider } from './me-trail'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/me/interest-regions',
  useRouter: () => router,
}))

/** 목 목록을 읽은 뒤(마이크로태스크)까지 그린다 */
async function renderScreen(regionCode?: string) {
  const view = render(
    <NavTrailProvider>
      <MeTrailProvider>
        <InterestRegionsScreen regionName="○○동" {...(regionCode ? { regionCode } : {})} />
      </MeTrailProvider>
    </NavTrailProvider>,
  )
  await act(async () => {})
  return view
}

const rows = () =>
  within(screen.getByRole('list', { name: '관심 동네 목록' }))
    .getAllByRole('listitem')
    .map((item) => item.textContent)
const addButton = () => screen.getByRole('button', { name: '관심 동네로 더하기' })

async function pick(user: ReturnType<typeof userEvent.setup>, keyword: string, name: RegExp) {
  const input = screen.getByRole('searchbox', { name: '행정동 이름' })
  await user.clear(input)
  await user.type(input, keyword)
  await user.click(await screen.findByRole('radio', { name }))
}

beforeEach(() => {
  search = 'mock-auth=member'
  resetMockSession()
  resetMockInterestRegions()
  vi.clearAllMocks()
})

describe('InterestRegionsScreen 관심 동네 (목)', () => {
  it('머리줄 알림(종)은 동네 · 덮어쓰기를 남긴 채 알림 설정으로 간다', async () => {
    search =
      'region=11440660&mock-auth=member&mock-provider=email&mock-push=supported&confirm=unknown'
    await renderScreen('11440660')

    const [bell] = await screen.findAllByRole('button', { name: '알림 설정' })
    await userEvent.setup().click(bell as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(
      '/me/notifications?region=11440660&mock-auth=member&mock-provider=email&mock-push=supported',
    )
  })

  it('제목 · 상한 안내 · 예시 2곳을 보이고, 고르기 전에는 더하기가 꺼져 있다', async () => {
    await renderScreen()

    expect(screen.getAllByRole('heading', { level: 1, name: '관심 동네' })).toHaveLength(2)
    expect(
      screen.getByText(
        '내 동네 말고 지켜볼 동네를 3곳까지 고를 수 있어요. 행정동을 직접 골라 주세요. 위치 정보는 사용하지 않아요.',
      ),
    ).toBeDefined()
    expect(rows()).toEqual(['망원1동서울특별시 마포구삭제', '삼청동서울특별시 종로구삭제'])
    expect(addButton().getAttribute('aria-disabled')).toBe('true')
    expect(screen.queryByText(/아직 저장할 수 없어요/)).toBeNull()
  })

  it('고른 동네를 더하면 목록 끝에 붙고 알린다. 3곳이 차면 검색 대신 상한 안내다', async () => {
    await renderScreen()
    const user = userEvent.setup()
    await pick(user, '서교', /서교동/)
    expect(addButton().getAttribute('aria-disabled')).toBeNull()
    await user.click(addButton())

    expect(rows()).toHaveLength(3)
    expect(rows()[2]).toBe('서교동서울특별시 마포구삭제')
    expect(screen.getByText('서교동을 관심 동네에 더했어요')).toBeDefined()
    expect(
      screen.getByText('관심 동네를 3곳 모두 골랐어요. 다른 동네를 더하려면 하나를 삭제해 주세요.'),
    ).toBeDefined()
    expect(screen.queryByRole('searchbox')).toBeNull()
    expect(screen.queryByRole('button', { name: '관심 동네로 더하기' })).toBeNull()
    // 누른 버튼이 사라졌다. 포커스는 문서 처음이 아니라 목록 자리다
    expect(document.activeElement?.contains(screen.getByRole('list'))).toBe(true)
  })

  it('다른 곳에서 이미 더한 동네를 더하면 받은 목록으로 맞추고, 선택은 남아 이유가 보인다', async () => {
    search = 'mock-auth=member&mock-interest-regions=empty'
    await renderScreen()
    // 화면을 그린 뒤 목 서버 목록만 바뀐다(다른 탭 흉내)
    await addInterestRegion(
      { code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' },
      'mock',
    )
    const user = userEvent.setup()
    await pick(user, '서교', /서교동/)
    await user.click(addButton())

    expect(rows()).toEqual(['서교동서울특별시 마포구삭제'])
    expect(screen.getByRole('radio', { name: /서교동.*관심 동네/ })).toHaveProperty('checked', true)
    expect(screen.getByText('이미 고른 관심 동네예요. 다른 동네를 골라 주세요.')).toBeDefined()
    expect(screen.queryByText(/관심 동네에 더했어요/)).toBeNull()
  })

  it('다른 곳에서 상한이 찼으면 받은 목록으로 맞추고 상한 안내로 바꾼 뒤 목록 자리로 포커스를 옮긴다', async () => {
    await renderScreen()
    await addInterestRegion(
      { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' },
      'mock',
    )
    const user = userEvent.setup()
    await pick(user, '서교', /서교동/)
    await user.click(addButton())

    expect(rows()).toHaveLength(3)
    expect(rows()[2]).toBe('역삼1동서울특별시 강남구삭제')
    expect(screen.getByText(/3곳 모두 골랐어요/)).toBeDefined()
    expect(screen.queryByText(/관심 동네에 더했어요/)).toBeNull()
    expect(document.activeElement?.contains(screen.getByRole('list'))).toBe(true)
  })

  it('내 동네 · 이미 고른 동네는 결과에 적고, 고르면 이유를 보이며 더하지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await saveRegion({ code: '11680640', name: '역삼1동' }, 'mock')
    search = ''
    await renderScreen()
    const user = userEvent.setup()

    await pick(user, '역삼', /역삼1동.*강남구 · 내 동네/)
    expect(
      screen.getByText('내 동네는 이미 지켜보고 있어요. 다른 동네를 골라 주세요.'),
    ).toBeDefined()
    expect(addButton().getAttribute('aria-disabled')).toBe('true')
    await user.click(addButton())
    expect(rows()).toHaveLength(2)

    await pick(user, '망원', /망원1동.*마포구 · 관심 동네/)
    expect(screen.getByText('이미 고른 관심 동네예요. 다른 동네를 골라 주세요.')).toBeDefined()
    expect(addButton().getAttribute('aria-disabled')).toBe('true')
  })

  it('내 동네를 관심 동네 중 하나로 바꿨으면 그 줄에 내 동네를 붙이고 지우지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await saveRegion({ code: '11440690', name: '망원1동' }, 'mock')
    search = ''
    await renderScreen()

    expect(rows()).toEqual([
      '망원1동서울특별시 마포구 · 내 동네삭제',
      '삼청동서울특별시 종로구삭제',
    ])
  })

  it('삭제하면 목록에서 빼고 알린 뒤 목록 자리로 포커스를 옮긴다. 모두 빼면 빈 상태다', async () => {
    await renderScreen()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '망원1동 삭제' }))

    expect(rows()).toEqual(['삼청동서울특별시 종로구삭제'])
    // 이름이 같은 동을 가리게 시군구 줄을 설명으로 잇는다
    const remove = screen.getByRole('button', { name: '삼청동 삭제' })
    expect(
      document.getElementById(remove.getAttribute('aria-describedby') ?? '')?.textContent,
    ).toBe('서울특별시 종로구')
    expect(screen.getByText('망원1동을 관심 동네에서 뺐어요')).toBeDefined()
    expect(document.activeElement?.contains(screen.getByRole('list'))).toBe(true)

    await user.click(screen.getByRole('button', { name: '삼청동 삭제' }))
    expect(screen.queryByRole('list', { name: '관심 동네 목록' })).toBeNull()
    expect(screen.getByText('아직 고른 관심 동네가 없어요')).toBeDefined()
  })

  it('?mock-interest-regions=empty 면 빈 상태, full 이면 상한 안내다', async () => {
    search = 'mock-auth=member&mock-interest-regions=empty'
    const { unmount } = await renderScreen()
    expect(screen.getByText('아직 고른 관심 동네가 없어요')).toBeDefined()
    expect(screen.getByText('아래에서 동네를 찾아 더해 주세요.')).toBeDefined()
    unmount()

    search = 'mock-auth=member&mock-interest-regions=full'
    await renderScreen()
    expect(rows()).toHaveLength(3)
    expect(screen.getByText(/3곳 모두 골랐어요/)).toBeDefined()
    expect(screen.queryByRole('searchbox')).toBeNull()
  })

  it('?mock-interest-regions=fail 이면 더하기 · 삭제가 실패해 빨강 상자로 알리고 목록은 그대로다', async () => {
    search = 'mock-auth=member&mock-interest-regions=fail'
    await renderScreen()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '망원1동 삭제' }))
    expect(screen.getByRole('alert').textContent).toBe(
      '관심 동네를 빼지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(rows()).toHaveLength(2)

    await pick(user, '서교', /서교동/)
    await user.click(addButton())
    expect(screen.getByRole('alert').textContent).toBe(
      '관심 동네를 더하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(rows()).toHaveLength(2)
    // 선택은 남아 다시 누를 수 있다
    expect(addButton().getAttribute('aria-disabled')).toBeNull()
  })
})

describe('InterestRegionsScreen 가드 · 돌아가기', () => {
  it('비회원이 주소로 들어오면 로그인(돌아올 곳 /me/interest-regions)으로 기록을 바꿔 간다', async () => {
    search = ''
    const { container } = await renderScreen()
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fme%2Finterest-regions')
  })

  it('주소로 바로 들어왔으면 뒤로가 내 정보로 기록을 바꿔 간다 (동네 · 덮어쓰기를 남긴다)', async () => {
    search = 'region=11440660&mock-auth=member&mock-interest-regions=full'
    await renderScreen('11440660')
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660&mock-auth=member')
  })
})

describe('InterestRegionsScreen 실데이터 (#237)', () => {
  afterEach(() => {
    resetApiSession()
  })

  const LIST = 'GET /api/v1/members/me/interest-regions'
  const ADD = 'POST /api/v1/members/me/interest-regions'
  /** 요청이 나가고 응답의 then 이 돌 때까지 */
  const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))
  /** 백엔드 목록 응답(`SliceResponse<MemberRegionResponse>`) */
  const slice = (
    ...contents: {
      code: string
      name: string | null
      sigungu: string | null
      abolished?: boolean
    }[]
  ) =>
    okResponse({
      contents: contents.map((region) => ({ abolished: false, ...region })),
      hasNext: false,
    })
  const MANGWON = { code: '11440690', name: '망원1동', sigungu: '서울특별시 마포구' }
  const SEOGYO = { code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' }
  const ABOLISHED = { code: '99990110', name: '○○1동', sigungu: '○○시 ○○구', abolished: true }
  const UNKNOWN = { code: '99990120', name: null, sigungu: null, abolished: true }

  /** 실데이터 회원으로 그린다. 목 재현 쿼리는 듣지 않는다 */
  async function renderApi() {
    search = 'mock-interest-regions=full'
    selectApiSource()
    const server = holdRequests()
    act(() => setSession(memberToken()))
    await renderScreen()
    return server
  }

  /** 실데이터 행정동 검색으로 고른다 */
  async function pickApi(
    user: ReturnType<typeof userEvent.setup>,
    server: ReturnType<typeof holdRequests>,
  ) {
    await user.type(screen.getByRole('searchbox', { name: '행정동 이름' }), '서교')
    await waitFor(() => expect(server.requests()).toContain('GET /api/v1/districts'))
    act(() =>
      server.reply(
        'GET /api/v1/districts',
        okResponse([{ code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' }]),
      ),
    )
    await user.click(await screen.findByRole('radio', { name: /서교동/ }))
  }

  it('읽는 동안은 "불러오고 있어요" 이고, 받은 목록의 폐지 · 이름 모름 줄을 지어낸 이름 없이 보이며 지우게 안내한다', async () => {
    const server = await renderApi()
    expect(
      screen.getByText('관심 동네를 불러오고 있어요').closest('[role="status"]'),
    ).not.toBeNull()
    expect(screen.queryByRole('searchbox')).toBeNull()
    expect(server.requests()).toEqual([LIST])

    act(() => server.reply(LIST, slice(MANGWON, ABOLISHED, UNKNOWN)))
    await flush()

    expect(screen.queryByText('관심 동네를 불러오고 있어요')).toBeNull()
    expect(rows()).toEqual([
      '망원1동서울특별시 마포구삭제',
      '○○1동○○시 ○○구 · 없어진 동네삭제',
      '없어진 동네행정구역 개편으로 바뀌었어요삭제',
    ])
    expect(screen.getByRole('button', { name: '○○1동 삭제' })).toBeDefined()
    expect(screen.getByRole('button', { name: '없어진 동네 삭제' })).toBeDefined()
    // 코드는 보이지 않는다
    expect(document.body.textContent).not.toContain('99990120')
    expect(
      screen.getByText('행정구역 개편으로 없어진 동네가 있어요. 삭제하고 새 동네를 골라 주세요.'),
    ).toBeDefined()
    // 목 재현(full)은 실데이터에서 듣지 않는다 — 받은 3곳이라 상한 안내다
    expect(screen.getByText(/3곳 모두 골랐어요/)).toBeDefined()
  })

  it('이름을 모르는 동네도 같은 코드로 지우고, 받은 목록으로 바꾼 뒤 알린다', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, slice(ABOLISHED, UNKNOWN)))
    await flush()

    await userEvent.setup().click(screen.getByRole('button', { name: '없어진 동네 삭제' }))
    expect(server.requests()).toEqual([LIST, 'DELETE /api/v1/members/me/interest-regions/99990120'])
    act(() => server.reply('DELETE /api/v1/members/me/interest-regions/99990120', slice(ABOLISHED)))
    await flush()

    expect(rows()).toEqual(['○○1동○○시 ○○구 · 없어진 동네삭제'])
    expect(screen.getByText('없어진 동네를 관심 동네에서 뺐어요')).toBeDefined()
    // 다시 읽지 않는다
    expect(server.requests()).toHaveLength(2)
  })

  it('읽지 못하면 빨강 상자와 다시 시도만 두고, 다시 시도하면 다시 읽는다', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, errorResponse('REGION_004', 503)))
    await flush()

    expect(screen.getByRole('alert').textContent).toContain(
      '관심 동네를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(screen.queryByRole('list', { name: '관심 동네 목록' })).toBeNull()
    expect(screen.queryByRole('searchbox')).toBeNull()
    expect(screen.queryByRole('button', { name: '관심 동네로 더하기' })).toBeNull()

    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    expect(server.requests()).toEqual([LIST, LIST])
    act(() => server.reply(LIST, slice(MANGWON)))
    await flush()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(rows()).toEqual(['망원1동서울특별시 마포구삭제'])
  })

  it('더하면 코드만 보내고 받은 목록을 그대로 그린다', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, slice(MANGWON)))
    await flush()
    const user = userEvent.setup()
    await pickApi(user, server)
    await user.click(addButton())

    const calls = server.fetchMock.mock.calls as [string, RequestInit | undefined][]
    const [, init] = calls.at(-1) ?? []
    expect(init?.body).toBe(JSON.stringify({ code: '11440660' }))
    act(() =>
      server.reply(
        ADD,
        slice(MANGWON, { code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' }),
      ),
    )
    await flush()
    expect(rows()).toEqual(['망원1동서울특별시 마포구삭제', '서교동서울특별시 마포구삭제'])
    expect(screen.getByText('서교동을 관심 동네에 더했어요')).toBeDefined()
    expect(server.requests().filter((request) => request === LIST)).toHaveLength(1)
  })

  it('서버가 내 동네와 같다고 거절하면(REGION_007) 다시 읽은 목록으로 맞추고 내 동네 이유를 보인다', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, slice(MANGWON)))
    await flush()
    const user = userEvent.setup()
    // 이 화면은 내 동네를 모른다(회원 정보를 읽지 않음) — 서버만 안다
    await pickApi(user, server)
    expect(addButton().getAttribute('aria-disabled')).toBeNull()
    await user.click(addButton())
    act(() => server.reply(ADD, errorResponse('REGION_007', 409)))
    await flush()

    expect(server.requests().slice(-2)).toEqual([ADD, LIST])
    act(() => server.reply(LIST, slice(MANGWON)))
    await flush()

    expect(rows()).toEqual(['망원1동서울특별시 마포구삭제'])
    expect(
      screen.getByText('내 동네는 이미 지켜보고 있어요. 다른 동네를 골라 주세요.'),
    ).toBeDefined()
    expect(screen.getByRole('radio', { name: /서교동.*내 동네/ })).toHaveProperty('checked', true)
    expect(addButton().getAttribute('aria-disabled')).toBe('true')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByText(/관심 동네에 더했어요/)).toBeNull()
  })

  it('서버가 그 동네를 받지 않으면(REGION_002) 선택을 지우고 다른 동네를 고르게 한다', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, slice()))
    await flush()
    const user = userEvent.setup()
    await pickApi(user, server)
    await user.click(addButton())
    act(() => server.reply(ADD, errorResponse('REGION_002', 400)))
    await flush()

    expect(screen.getByRole('alert').textContent).toBe(
      '이 동네는 고를 수 없어요. 다른 동네를 골라 주세요.',
    )
    expect(screen.getByRole('radio', { name: /서교동/ })).toHaveProperty('checked', false)
    expect(addButton().getAttribute('aria-disabled')).toBe('true')
    // 목록은 다시 읽지 않는다
    expect(server.requests().filter((request) => request === LIST)).toHaveLength(1)
  })

  it('이름을 모르는 줄이 둘 이상이면 삭제 이름에 순번을 붙인다 — 지어낸 이름 · 코드 없이', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, slice(UNKNOWN, MANGWON, { ...UNKNOWN, code: '99990121' })))
    await flush()

    expect(
      screen
        .getAllByRole('button', { name: /삭제$/ })
        .map((button) => button.getAttribute('aria-label')),
    ).toEqual(['없어진 동네 1 삭제', '망원1동 삭제', '없어진 동네 2 삭제'])
    expect(document.body.textContent).not.toMatch(/9999012/)
  })

  it('추가가 REGION_004 여도 다시 읽은 목록에 그 동네가 있으면 더한 것으로 알린다', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, slice(MANGWON)))
    await flush()
    const user = userEvent.setup()
    await pickApi(user, server)
    await user.click(addButton())
    act(() => server.reply(ADD, errorResponse('REGION_004', 503)))
    await flush()
    act(() => server.reply(LIST, slice(MANGWON, SEOGYO)))
    await flush()

    expect(rows()).toEqual(['망원1동서울특별시 마포구삭제', '서교동서울특별시 마포구삭제'])
    expect(screen.getByText('서교동을 관심 동네에 더했어요')).toBeDefined()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('거절이 다시 읽은 목록과 맞지 않으면(이미 있음인데 목록에 없음) 목록을 맞추고 더하지 못했다고 알린다', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, slice()))
    await flush()
    const user = userEvent.setup()
    await pickApi(user, server)
    await user.click(addButton())
    act(() => server.reply(ADD, errorResponse('REGION_006', 409)))
    await flush()
    act(() => server.reply(LIST, slice(MANGWON)))
    await flush()

    expect(rows()).toEqual(['망원1동서울특별시 마포구삭제'])
    expect(screen.getByRole('alert').textContent).toBe(
      '관심 동네를 더하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    // 선택은 남아 다시 누를 수 있다
    expect(screen.getByRole('radio', { name: /서교동/ })).toHaveProperty('checked', true)
    expect(addButton().getAttribute('aria-disabled')).toBeNull()
  })

  it('삭제가 실패하면 빨강 상자로 알리고 목록은 그대로다', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, slice(MANGWON, SEOGYO)))
    await flush()
    await userEvent.setup().click(screen.getByRole('button', { name: '망원1동 삭제' }))
    act(() =>
      server.reply(
        'DELETE /api/v1/members/me/interest-regions/11440690',
        errorResponse('REGION_004', 503),
      ),
    )
    await flush()

    expect(screen.getByRole('alert').textContent).toBe(
      '관심 동네를 빼지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(rows()).toHaveLength(2)
    expect(screen.queryByText(/관심 동네에서 뺐어요/)).toBeNull()
  })

  it('내 동네와 같다고 거절됐는데 이 화면의 내 동네가 다르면 내 동네를 다시 읽고, 그 값이 바뀌면 저장소 값을 따른다', async () => {
    search = ''
    selectApiSource()
    resetMemberInfoForTests()
    const stop = startMemberInfo()
    try {
      const server = holdRequests()
      act(() => setSession(memberToken()))
      await renderScreen()
      const region = (code: string, name: string) =>
        okResponse({ code, name, sigungu: '서울특별시 마포구', abolished: false })
      act(() => {
        server.reply('GET /api/v1/members/me', okResponse(myInfoBody()))
        // 이 탭이 아는 내 동네는 합정동(낡음). 서버의 내 동네는 다른 곳에서 바꾼 서교동이다
        server.reply('GET /api/v1/members/me/region', region('11440680', '합정동'))
        server.reply(LIST, slice(MANGWON))
      })
      await flush()
      const user = userEvent.setup()
      await pickApi(user, server)
      await user.click(addButton())
      act(() => server.reply(ADD, errorResponse('REGION_007', 409)))
      await flush()
      act(() => server.reply(LIST, slice(MANGWON)))
      await flush()

      // 다시 읽는 동안은 서버 쪽(서교동)을 내 동네로 삼는다
      expect(
        server.requests().filter((request) => request === 'GET /api/v1/members/me/region'),
      ).toHaveLength(2)
      expect(
        screen.getByText('내 동네는 이미 지켜보고 있어요. 다른 동네를 골라 주세요.'),
      ).toBeDefined()

      // 저장소가 새 값을 받으면 그 값을 따른다(여기서는 그사이 또 바뀐 망원2동)
      act(() => server.reply('GET /api/v1/members/me/region', region('11440700', '망원2동')))
      await flush()
      expect(screen.queryByText(/내 동네는 이미 지켜보고 있어요/)).toBeNull()
      expect(addButton().getAttribute('aria-disabled')).toBeNull()
    } finally {
      stop()
      resetMemberInfoForTests()
    }
  })

  it('이 화면이 아는 내 동네와 같으면 보내지 않고 내 동네를 다시 읽지도 않는다', async () => {
    search = ''
    selectApiSource()
    resetMemberInfoForTests()
    const stop = startMemberInfo()
    try {
      const server = holdRequests()
      act(() => setSession(memberToken()))
      await renderScreen()
      act(() => {
        server.reply('GET /api/v1/members/me', okResponse(myInfoBody()))
        server.reply('GET /api/v1/members/me/region', okResponse({ ...SEOGYO, abolished: false }))
        server.reply(LIST, slice(MANGWON))
      })
      await flush()
      // 화면이 먼저 막는다 — 보내지 않는다
      const user = userEvent.setup()
      await pickApi(user, server)
      expect(
        screen.getByText('내 동네는 이미 지켜보고 있어요. 다른 동네를 골라 주세요.'),
      ).toBeDefined()
      await user.click(addButton())
      expect(server.requests()).not.toContain(ADD)
      expect(
        server.requests().filter((request) => request === 'GET /api/v1/members/me/region'),
      ).toHaveLength(1)
    } finally {
      stop()
      resetMemberInfoForTests()
    }
  })

  it('더하기가 일시 장애로 실패하면 빨강 상자로 알리고 선택은 남는다', async () => {
    const server = await renderApi()
    act(() => server.reply(LIST, slice()))
    await flush()
    const user = userEvent.setup()
    await pickApi(user, server)
    await user.click(addButton())
    act(() => server.reply(ADD, new Response('bad gateway', { status: 502 })))
    await flush()

    expect(screen.getByRole('alert').textContent).toBe(
      '관심 동네를 더하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(addButton().getAttribute('aria-disabled')).toBeNull()
    expect(screen.getByText('아직 고른 관심 동네가 없어요')).toBeDefined()
  })
})
