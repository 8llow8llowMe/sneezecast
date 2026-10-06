// @vitest-environment jsdom
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { loginWithEmail, resetMockSession, saveRegion } from '@/features/auth/auth-client'
import {
  addInterestRegion,
  resetMockInterestRegions,
} from '@/features/region/interest-region-client'
import { setSession } from '@/lib/session/session-store'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import { holdRequests, memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

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

describe('InterestRegionsScreen 실데이터', () => {
  afterEach(() => {
    resetApiSession()
  })

  it('관심 동네 API 가 없어 요청하지 않고, 목록 · 검색 없이 아직 저장할 수 없다고 알린다', async () => {
    search = 'mock-interest-regions=full'
    selectApiSource()
    const server = holdRequests()
    act(() => setSession(memberToken()))
    await renderScreen()

    expect(
      screen.getByText('관심 동네는 아직 저장할 수 없어요. 준비되면 여기에서 고를 수 있어요.'),
    ).toBeDefined()
    expect(screen.queryByRole('list', { name: '관심 동네 목록' })).toBeNull()
    // 고를 수 있다는 안내는 두지 않는다 — 회색 상자와 다른 말을 하지 않게
    expect(screen.queryByText(/3곳까지 고를 수 있어요/)).toBeNull()
    expect(screen.queryByRole('searchbox')).toBeNull()
    expect(screen.queryByRole('button', { name: '관심 동네로 더하기' })).toBeNull()
    expect(server.requests().filter((request) => request.includes('interest'))).toEqual([])
  })
})
