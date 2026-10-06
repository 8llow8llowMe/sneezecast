// @vitest-environment jsdom
import type { ReactNode } from 'react'

import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from '@/features/auth/auth-client'
import {
  getMockProfile,
  loginWithEmail,
  resetMockSession,
  saveRegion,
  type SaveRegionResult,
} from '@/features/auth/auth-client'
import { resetMemberInfoForTests, startMemberInfo } from '@/features/auth/member-info'
import { resetSessionForTests, setSession } from '@/lib/session/session-store'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import {
  errorResponse,
  holdRequests,
  memberToken,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { MeScreen } from './me-screen'
import { MeTrailProvider } from './me-trail'
import { MyRegionScreen } from './my-region-screen'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
let pathname = '/me/region'
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => pathname,
  useRouter: () => router,
}))

vi.mock('@/features/auth/auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, saveRegion: vi.fn(actual.saveRegion) }
})

const YEOKSAM1 = { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }

// 앱에서는 루트 레이아웃의 NavTrailProvider(앱 안 이동 기록)가 내 정보 레이아웃을 감싼다
function withTrail(children: ReactNode) {
  return (
    <NavTrailProvider>
      <MeTrailProvider>{children}</MeTrailProvider>
    </NavTrailProvider>
  )
}

const regionScreen = (regionCode?: string) =>
  withTrail(<MyRegionScreen regionName="○○동" {...(regionCode ? { regionCode } : {})} />)

const saveButton = () => screen.getByRole('button', { name: '이 동네로 바꾸기' })

async function pick(user: ReturnType<typeof userEvent.setup>, keyword: string, name: RegExp) {
  await user.type(screen.getByRole('searchbox', { name: '행정동 이름' }), keyword)
  await user.click(await screen.findByRole('radio', { name }))
}

beforeEach(async () => {
  search = ''
  pathname = '/me/region'
  resetMockSession()
  vi.clearAllMocks()
  await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
  await saveRegion(YEOKSAM1, 'mock')
  vi.mocked(saveRegion).mockClear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('MyRegionScreen 내 동네 바꾸기', () => {
  it('머리줄 알림(종)은 동네 · 덮어쓰기를 남긴 채 알림 설정으로 간다', async () => {
    search =
      'region=11440660&mock-auth=member&mock-provider=email&mock-push=supported&confirm=unknown'
    render(regionScreen('11440660'))

    const [bell] = await screen.findAllByRole('button', { name: '알림 설정' })
    await userEvent.setup().click(bell as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(
      '/me/notifications?region=11440660&mock-auth=member&mock-provider=email&mock-push=supported',
    )
  })

  it('제목 · 위치 정보를 쓰지 않는다는 안내 · 지금 내 동네를 보이고, 고르기 전에는 버튼이 꺼져 있다', () => {
    const geolocation = vi.fn()
    Object.defineProperty(window.navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition: geolocation, watchPosition: geolocation },
    })
    render(regionScreen())

    expect(screen.getAllByRole('heading', { level: 1, name: '내 동네 바꾸기' })).toHaveLength(2)
    expect(screen.getByText(/위치 정보는 사용하지 않아요/)).toBeDefined()
    expect(screen.getByText('지금 내 동네').nextElementSibling?.textContent).toBe('역삼1동')
    expect(saveButton().getAttribute('aria-disabled')).toBe('true')
    expect(geolocation).not.toHaveBeenCalled()
  })

  it('지금 내 동네와 같은 동네를 고르면 버튼이 꺼진 채다', async () => {
    render(regionScreen())
    const user = userEvent.setup()
    await pick(user, '역삼', /역삼1동/)

    expect(saveButton().getAttribute('aria-disabled')).toBe('true')
    await user.click(saveButton())
    expect(saveRegion).not.toHaveBeenCalled()
  })

  it('주소로 바로 들어와 다른 동네를 저장하면 내 동네를 바꾸고 내 정보로 기록을 바꿔 간다 (동네 · 덮어쓰기를 남긴다)', async () => {
    search = 'region=11440660&mock-provider=email'
    render(regionScreen('11440660'))
    const user = userEvent.setup()
    await pick(user, '서교', /서교동/)
    expect(saveButton().getAttribute('aria-disabled')).toBeNull()

    await user.click(saveButton())
    expect(saveRegion).toHaveBeenCalledWith(expect.objectContaining({ code: '11440660' }), 'mock')
    expect(getMockProfile()?.region).toEqual({ code: '11440660', name: '서교동' })
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660&mock-provider=email')
    // 이동할 때까지 지금 내 동네는 처음 그린 값이다
    expect(screen.getByText('지금 내 동네').nextElementSibling?.textContent).toBe('역삼1동')
  })

  it('저장하지 못하면 빨강 상자로 알리고 다시 누를 수 있다', async () => {
    vi.mocked(saveRegion).mockRejectedValueOnce(new Error('mock failure'))
    render(regionScreen())
    const user = userEvent.setup()
    await pick(user, '서교', /서교동/)

    await user.click(saveButton())
    expect((await screen.findByRole('alert')).textContent).toBe(
      '내 동네를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(router.replace).not.toHaveBeenCalled()
    expect(getMockProfile()?.region?.code).toBe(YEOKSAM1.code)

    await user.click(saveButton())
    expect(saveRegion).toHaveBeenCalledTimes(2)
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/me'))
  })

  it('저장하는 동안에는 검색 칸이 읽기 전용이고 버튼 · 뒤로가 꺼진다', async () => {
    let finish: () => void = () => {}
    vi.mocked(saveRegion).mockImplementationOnce(
      () => new Promise<SaveRegionResult>((resolve) => (finish = () => resolve({ status: 'ok' }))),
    )
    render(regionScreen())
    const user = userEvent.setup()
    await pick(user, '서교', /서교동/)
    await user.click(saveButton())

    expect(screen.getByRole('searchbox', { name: '행정동 이름' }).hasAttribute('readonly')).toBe(
      true,
    )
    expect(saveButton().getAttribute('aria-disabled')).toBe('true')
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).not.toHaveBeenCalled()
    finish()
  })

  it('머리줄 동네 이름(데스크톱)은 둘러보기 동네가 없으면 내 동네이고, 이 화면으로 돌아올 둘러볼 동네 고르기를 연다', async () => {
    search = 'mock-provider=email'
    render(regionScreen())
    await userEvent.setup().click(screen.getByRole('button', { name: '동네 바꾸기, 현재 역삼1동' }))
    expect(router.push).toHaveBeenCalledWith(
      '/browse/region?next=%2Fme%2Fregion&mock-provider=email',
    )
  })
})

describe('MyRegionScreen 가드 · 내 정보로 돌아가기', () => {
  it('비회원이 주소로 들어오면 로그인(돌아올 곳 /me/region)으로 기록을 바꿔 간다', () => {
    resetMockSession()
    const { container } = render(regionScreen())
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fme%2Fregion')
  })

  it('내 정보에서 왔으면 저장 뒤 기록을 되돌리고, 내 정보가 바뀐 보고 동네와 알림을 보인다', async () => {
    const user = userEvent.setup()
    const meScreen = () => withTrail(<MeScreen regionName="○○동" />)
    pathname = '/me'
    const { rerender } = render(meScreen())
    pathname = '/me/region'
    rerender(regionScreen())

    await pick(user, '서교', /서교동/)
    await user.click(saveButton())
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()

    // 기록을 되돌려 내 정보가 다시 그려진다
    pathname = '/me'
    rerender(meScreen())
    expect(screen.getByText('내 동네를 바꿨어요').closest('[role="status"]')).not.toBeNull()
    expect(screen.getByRole('link', { name: /^보고 동네/ }).textContent).toBe('보고 동네서교동')
  })
})

describe('MyRegionScreen 실데이터 (#164)', () => {
  let stopMemberInfo: () => void = () => {}
  beforeEach(() => {
    resetSessionForTests()
    resetMemberInfoForTests()
    stopMemberInfo = startMemberInfo()
    selectApiSource()
  })
  afterEach(() => {
    stopMemberInfo()
    resetMemberInfoForTests()
    resetApiSession()
  })

  /** 요청이 나가고 응답의 then 이 돌 때까지 */
  const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))
  const currentText = () => screen.getByText('지금 내 동네').nextElementSibling?.textContent

  it('내 동네를 읽는 중 · 읽지 못함을 "지금 내 동네" 에 보이고, 다시 시도해 받으면 그 동네다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    render(regionScreen())
    await flush()
    // 목 프로필(역삼1동)을 쓰지 않는다
    expect(currentText()).toBe('불러오고 있어요')

    act(() => server.reply('GET /api/v1/members/me/region', errorResponse('REGION_004', 503)))
    await flush()
    expect(currentText()).toBe('불러오지 못했어요')

    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    act(() =>
      server.reply(
        'GET /api/v1/members/me/region',
        okResponse({
          code: '11440660',
          name: '서교동',
          sigungu: '서울특별시 마포구',
          abolished: false,
        }),
      ),
    )
    await flush()
    expect(currentText()).toBe('서교동')
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
  })

  it('내 동네를 읽기 전에 저장해 성공해도 이동할 때까지 "불러오고 있어요" 를 그대로 보인다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    render(regionScreen())
    const user = userEvent.setup()
    await user.type(screen.getByRole('searchbox', { name: '행정동 이름' }), '서교')
    await waitFor(() => expect(server.requests()).toContain('GET /api/v1/districts'))
    act(() =>
      server.reply(
        'GET /api/v1/districts',
        okResponse([{ code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' }]),
      ),
    )
    await user.click(await screen.findByRole('radio', { name: /서교동/ }))
    expect(currentText()).toBe('불러오고 있어요')

    await user.click(saveButton())
    await flush()
    act(() =>
      server.reply(
        'PUT /api/v1/members/me/region',
        okResponse({
          code: '11440660',
          name: '서교동',
          sigungu: '서울특별시 마포구',
          abolished: false,
        }),
      ),
    )
    await flush()
    expect(router.replace).toHaveBeenCalledWith('/me')
    // 저장 응답으로 내 동네가 먼저 바뀌어도 "아직 정하지 않았어요" · 새 동네로 뒤집히지 않는다
    expect(currentText()).toBe('불러오고 있어요')
  })

  it('서버가 그 동네를 받지 않으면(invalid) 선택을 지우고 다른 동네를 고르라고 알린다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    vi.mocked(saveRegion).mockResolvedValueOnce({ status: 'invalid' })
    render(regionScreen())
    const user = userEvent.setup()
    // 실데이터는 행정동 검색도 API 다
    await user.type(screen.getByRole('searchbox', { name: '행정동 이름' }), '서교')
    await waitFor(() => expect(server.requests()).toContain('GET /api/v1/districts'))
    act(() =>
      server.reply(
        'GET /api/v1/districts',
        okResponse([{ code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' }]),
      ),
    )
    await user.click(await screen.findByRole('radio', { name: /서교동/ }))
    await user.click(saveButton())

    expect(saveRegion).toHaveBeenCalledWith(expect.objectContaining({ code: '11440660' }), 'api')
    expect((await screen.findByRole('alert')).textContent).toBe(
      '이 동네는 고를 수 없어요. 다른 동네를 골라 주세요.',
    )
    expect(saveButton().getAttribute('aria-disabled')).toBe('true')
    expect(router.replace).not.toHaveBeenCalled()
  })
})
