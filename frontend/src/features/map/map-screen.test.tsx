// @vitest-environment jsdom
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from '@/features/auth/auth-client'
import {
  getMockProfile,
  loginWithEmail,
  MOCK_RESELECT_FAIL_EMAIL,
  resetMockSession,
  saveRegion,
} from '@/features/auth/auth-client'
import { NOTICE_EXAMPLE_DISTRICT } from '@/features/notice/mock'
import { DISTRICT_MOCKS } from '@/features/region/mock'

import { MapScreen } from './map-screen'
import { pickMapMock } from './mock'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/map',
  useRouter: () => router,
}))

vi.mock('@/features/auth/auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, saveRegion: vi.fn(actual.saveRegion) }
})

const SEOGYO = '11440660'
const MANGWON1 = '11440690'

function districtButton(name: RegExp) {
  return within(screen.getByRole('list', { name: '동네 목록' })).getByRole('button', { name })
}

function handle() {
  return screen.getByRole('button', { name: /동네 정보 (자세히 보기|접기)/ })
}

describe('MapScreen', () => {
  beforeEach(() => {
    search = ''
    resetMockSession()
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/map')
  })

  it('처음에는 내 동네를 접어 보이고, 손잡이로 펼치고 접는다', async () => {
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} />)

    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('○○동 (내 동네)')
    expect(handle().getAttribute('aria-expanded')).toBe('false')
    expect(screen.getByText(/위로 밀어 자세히 보기/).textContent).toBe(
      '발열·기침 보고가 지난주보다 조금 늘었어요 · 위로 밀어 자세히 보기',
    )
    // 접힘의 자세한 정보는 모바일에서 숨고 태블릿부터 늘 보인다
    const details = document.getElementById(handle().getAttribute('aria-controls') ?? '')
    expect(details?.classList.contains('hidden')).toBe(true)
    expect(details?.classList.contains('tablet:flex')).toBe(true)

    await user.click(handle())
    expect(handle().getAttribute('aria-expanded')).toBe('true')
    expect(details?.classList.contains('hidden')).toBe(false)
    expect(screen.queryByText(/위로 밀어 자세히 보기/)).toBeNull()

    await user.click(handle())
    expect(handle().getAttribute('aria-expanded')).toBe('false')
  })

  it('시트 윗부분을 위로 밀면 펼치고 아래로 밀면 접는다', () => {
    render(<MapScreen map={pickMapMock('example', null)} />)
    const name = screen.getByRole('heading', { level: 2 })

    fireEvent.pointerDown(name, { clientY: 300 })
    fireEvent.pointerUp(name, { clientY: 240 })
    expect(handle().getAttribute('aria-expanded')).toBe('true')

    fireEvent.pointerDown(name, { clientY: 240 })
    fireEvent.pointerUp(name, { clientY: 300 })
    expect(handle().getAttribute('aria-expanded')).toBe('false')

    // 조금 움직인 것은 밀기가 아니다
    fireEvent.pointerDown(name, { clientY: 300 })
    fireEvent.pointerUp(name, { clientY: 290 })
    expect(handle().getAttribute('aria-expanded')).toBe('false')
  })

  it('수치가 있는 동네를 고르면 펼쳐서 참여 · 증상 보고 · 지난 4주 평균과 상태 글자를 보인다', async () => {
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} />)

    await user.click(districtButton(/서교동/))
    expect(districtButton(/서교동/).getAttribute('aria-pressed')).toBe('true')
    expect(handle().getAttribute('aria-expanded')).toBe('true')

    const panel = screen.getByRole('region', { name: '서교동' })
    const word = within(panel).getByText('많이 늘었어요', { selector: 'h2 + span' })
    expect(word.classList.contains('text-status-high-text')).toBe(true)
    expect(panel.textContent).toContain('참여128명')
    expect(panel.textContent).toContain('증상 보고18%')
    expect(panel.textContent).toContain('지난 4주 평균9%')
    expect(within(panel).getByText('발열·기침·인후통')).toBeTruthy()
    expect(within(panel).getByRole('button', { name: '내 동네로 설정' })).toBeTruthy()
    expect(
      within(panel).getByRole('link', { name: '이 동네 안내 보기' }).getAttribute('href'),
    ).toBe(`/notice/${SEOGYO}/2025-W47?mock=published`)

    // 판단 기준(S11)은 주소 ?explain=1 로 연다
    await user.click(within(panel).getByRole('button', { name: '왜 이렇게 보나요?' }))
    expect(window.location.search).toBe('?explain=1')
  })

  it('자료 부족 동네는 수치 · 상태색 없이 자료 부족 글자와 참여 진행 막대만 보인다', async () => {
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} />)

    const row = districtButton(/망원1동/)
    expect(row.textContent).toContain('자료 부족')
    expect(row.querySelector('[aria-hidden="true"]')?.classList.contains('bg-muted-bar')).toBe(true)

    await user.click(row)
    const panel = screen.getByRole('region', { name: '망원1동' })
    const word = within(panel).getByText('자료 부족', { selector: 'h2 + span' })
    expect(word.classList.contains('text-status-insufficient-text')).toBe(true)
    expect(panel.textContent).not.toMatch(/\d+%/)
    expect(panel.textContent).toContain('41 / 100명')
    expect(within(panel).getByRole('progressbar').getAttribute('aria-valuenow')).toBe('41')
    expect(within(panel).queryByRole('button', { name: '왜 이렇게 보나요?' })).toBeNull()
    expect(within(panel).queryByRole('link', { name: '이 동네 안내 보기' })).toBeNull()
    expect(within(panel).getByRole('button', { name: '내 동네로 설정' })).toBeTruthy()
  })

  it('목 기본값(자료 부족)에서는 화면 어디에도 비율이 없다', () => {
    const { container } = render(<MapScreen map={pickMapMock(undefined, null)} />)
    expect(container.textContent).not.toMatch(/\d+%/)
    // 상태색은 범례에만 있다 — 동네 목록 · 동네 정보에는 없다
    const list = screen.getByRole('list', { name: '동네 목록' })
    const panel = screen.getByRole('region', { name: '○○동 (내 동네)' })
    for (const area of [list, panel]) {
      expect(area.querySelector('[class*="status-normal"], [class*="status-slight"]')).toBeNull()
      expect(area.querySelector('[class*="status-high"]')).toBeNull()
    }
  })

  it('자료 없음이면 동네 목록 · 동네 정보 없이 안내만 보인다', () => {
    render(<MapScreen map={pickMapMock('empty', null)} />)

    expect(
      screen.getByText('이번 주 동네별 자료가 아직 없어요. 집계가 끝나면 보여 드려요.'),
    ).toBeTruthy()
    expect(screen.queryByRole('list', { name: '동네 목록' })).toBeNull()
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
  })

  it('범례는 네 단계를 색과 글자로 보인다', () => {
    render(<MapScreen map={pickMapMock('example', null)} />)
    const items = within(screen.getByRole('list', { name: '범례' })).getAllByRole('listitem')

    expect(items.map((item) => item.textContent)).toEqual([
      '평소 수준',
      '조금 늘었어요',
      '많이 늘었어요',
      '자료 부족',
    ])
    expect(items[3]?.querySelector('span')?.classList.contains('bg-muted-bar')).toBe(true)
  })

  it('탭바 · 데스크톱 메뉴에서 지도가 지금 메뉴이고, 둘러보기 동네(?region=)를 모든 메뉴 링크에 남긴다', () => {
    const browse = DISTRICT_MOCKS.find((district) => district.code === MANGWON1) ?? null
    render(<MapScreen map={pickMapMock('example', browse)} regionCode={MANGWON1} />)

    const menus = screen.getAllByRole('navigation', { name: '주요 메뉴' })
    expect(menus).toHaveLength(2)
    menus.forEach((menu) => {
      const current = within(menu).getByRole('link', { current: 'page' })
      expect(current.textContent).toContain('지도')
      expect(current.getAttribute('href')).toBe(`/map?region=${MANGWON1}`)
      expect(within(menu).getByRole('link', { name: /홈/ }).getAttribute('href')).toBe(
        `/?region=${MANGWON1}`,
      )
    })
    const account = screen.getByRole('navigation', { name: '계정 메뉴' })
    expect(within(account).getByRole('link').getAttribute('href')).toBe(`/me?region=${MANGWON1}`)
    expect(screen.getByRole('link', { name: '우리동네체온계' }).getAttribute('href')).toBe(
      `/?region=${MANGWON1}`,
    )
    // 둘러보기 동네가 처음 고른 동네다
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('망원1동 (내 동네)')
  })

  it('동네 안내 링크에도 둘러보기 동네를 잇는다', async () => {
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} regionCode={MANGWON1} />)

    await user.click(districtButton(/서교동/))
    expect(screen.getByRole('link', { name: '이 동네 안내 보기' }).getAttribute('href')).toBe(
      `/notice/${SEOGYO}/2025-W47?mock=published&region=${MANGWON1}`,
    )
  })

  it('비회원은 알림(종)이 없고 보고 버튼이 로그인으로 간다. 위치 권한을 묻지 않는다', async () => {
    const geolocation = vi.fn()
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition: geolocation, watchPosition: geolocation },
    })
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} />)

    expect(screen.queryByRole('button', { name: '알림 설정' })).toBeNull()
    await user.click(screen.getByRole('button', { name: '로그인하고 보고하기' }))
    expect(router.push).toHaveBeenCalledWith('/login?intent=report')
    expect(geolocation).not.toHaveBeenCalled()
  })

  it('회원은 알림(종)이 있고, 준비 중인 동작은 알림으로 알린다', async () => {
    search = 'mock-auth=member'
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} />)

    await user.click(screen.getByRole('button', { name: '행정동 이름으로 찾기' }))
    expect(screen.getByRole('status').textContent).toContain('행정동 찾기는 준비하고 있어요')
    expect(screen.getByRole('button', { name: '알림 설정' })).toBeTruthy()
  })

  it('머리줄 동네 이름은 지도로 돌아올 둘러볼 동네 고르기를 연다 (동네 · 덮어쓰기를 남기고 목 자료는 뺀다)', async () => {
    search = 'mock=example&mock-auth=member'
    const seogyo = DISTRICT_MOCKS.find((district) => district.code === SEOGYO) ?? null
    render(<MapScreen map={pickMapMock('example', seogyo)} regionCode={SEOGYO} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '동네 바꾸기, 현재 서교동' }))
    expect(router.push).toHaveBeenCalledWith(
      `/browse/region?next=%2Fmap&region=${SEOGYO}&mock-auth=member`,
    )
  })
})

const YEOKSAM1 = { code: '11680640', name: '역삼1동' }
const EXAMPLE = NOTICE_EXAMPLE_DISTRICT

function deferred() {
  let resolve: () => void = () => {}
  let reject: () => void = () => {}
  const promise = new Promise<void>((done, fail) => {
    resolve = done
    reject = () => fail(new Error('mock failure'))
  })
  return { promise, resolve, reject }
}

/** 이메일로 로그인한 목 회원. 내 동네를 주면 저장해 둔다 */
async function loginAsMember(
  region: { code: string; name: string } | null = YEOKSAM1,
  email = 'dong@example.com',
) {
  await loginWithEmail(email, 'dongne2026', 'mock')
  if (region) await saveRegion(region)
  vi.mocked(saveRegion).mockClear()
}

const setMineButton = () => screen.queryByRole('button', { name: '내 동네로 설정' })

describe('MapScreen 내 동네로 설정', () => {
  beforeEach(() => {
    search = ''
    resetMockSession()
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/map')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('비회원은 둘러보기 동네 대신 고른 동네를 실어 로그인으로 간다 (돌아올 곳은 홈, 보고 의도 없음)', async () => {
    const user = userEvent.setup()
    const pushState = vi.spyOn(window.history, 'pushState')
    const browse = DISTRICT_MOCKS.find((district) => district.code === MANGWON1) ?? null
    render(<MapScreen map={pickMapMock('example', browse)} regionCode={MANGWON1} />)

    await user.click(districtButton(/서교동/))
    await user.click(setMineButton() as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(`/login?region=${SEOGYO}`)
    expect(pushState).not.toHaveBeenCalled()
    expect(saveRegion).not.toHaveBeenCalled()
  })

  it('둘러보기 동네가 없는 비회원도 고른 동네를 실어 로그인으로 간다', async () => {
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} />)

    await user.click(districtButton(/서교동/))
    await user.click(setMineButton() as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(`/login?region=${SEOGYO}`)
  })

  it('회원이 누르면 다른 쿼리를 남긴 채 ?confirm=set-mine 을 기록에 쌓는다', async () => {
    await loginAsMember()
    search = 'mock=example'
    const pushState = vi.spyOn(window.history, 'pushState')
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} />)

    await user.click(districtButton(/서교동/))
    await user.click(setMineButton() as HTMLElement)
    expect(pushState.mock.calls.map((call) => call[2])).toEqual(['?mock=example&confirm=set-mine'])
    expect(router.push).not.toHaveBeenCalled()
  })

  it('확인하면 고른 동네를 내 동네로 저장하고 대화상자를 닫은 뒤 알리고, 그 동네는 버튼 대신 (내 동네) 로 보인다', async () => {
    await loginAsMember()
    const replaceState = vi.spyOn(window.history, 'replaceState')
    const user = userEvent.setup()
    const { rerender } = render(<MapScreen map={pickMapMock('example', null)} />)

    await user.click(districtButton(/서교동/))
    search = 'confirm=set-mine'
    rerender(<MapScreen map={pickMapMock('example', null)} />)

    const dialog = screen.getByRole('dialog', { name: '서교동을 내 동네로 설정할까요?' })
    expect([...dialog.querySelectorAll('li')].map((li) => li.textContent)).toEqual([
      '보고와 알림은 내 동네를 기준으로 해요.',
      '내 정보의 보고 동네에서 다시 바꿀 수 있어요.',
    ])
    await user.click(within(dialog).getByRole('button', { name: '설정하기' }))

    expect(saveRegion).toHaveBeenCalledTimes(1)
    expect(saveRegion).toHaveBeenCalledWith({ code: SEOGYO, name: '서교동' })
    expect(getMockProfile()?.region).toEqual({ code: SEOGYO, name: '서교동' })
    expect(replaceState).toHaveBeenCalledWith({ sneezecastModalDepth: 0 }, '', '/map')
    expect(screen.getByRole('status').textContent).toContain('서교동을 내 동네로 설정했어요')
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('서교동 (내 동네)')
    expect(setMineButton()).toBeNull()
    expect(
      within(screen.getByRole('list', { name: '동네 목록' })).getByText('(내 동네)'),
    ).toBeTruthy()
  })

  it('저장에 성공하면 사라진 버튼 대신 동네 이름 제목으로 포커스를 옮긴다', async () => {
    await loginAsMember()
    const user = userEvent.setup()
    const { rerender } = render(<MapScreen map={pickMapMock('example', null)} />)

    await user.click(districtButton(/서교동/))
    search = 'confirm=set-mine'
    rerender(<MapScreen map={pickMapMock('example', null)} />)
    const dialog = screen.getByRole('dialog', { name: '서교동을 내 동네로 설정할까요?' })
    await user.click(within(dialog).getByRole('button', { name: '설정하기' }))

    const heading = screen.getByRole('heading', { level: 2, name: '서교동 (내 동네)' })
    expect(document.activeElement).toBe(heading)
    // 탭 순서에는 들지 않는다
    expect(heading.getAttribute('tabindex')).toBe('-1')
  })

  it('취소하면 저장하지 않고 주소에서 confirm 만 지운다', async () => {
    await loginAsMember()
    search = 'mock=example&confirm=set-mine'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<MapScreen map={pickMapMock('example', null)} />)

    const dialog = screen.getByRole('dialog', { name: `${EXAMPLE.name}을 내 동네로 설정할까요?` })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '취소' }))
    expect(replaceState).toHaveBeenCalledWith({ sneezecastModalDepth: 0 }, '', '?mock=example')
    expect(saveRegion).not.toHaveBeenCalled()
    expect(getMockProfile()?.region).toEqual(YEOKSAM1)
  })

  it('저장하지 못하면 대화상자 안에 알리고 다시 누를 수 있다. 내 동네는 그대로다', async () => {
    await loginAsMember(null, MOCK_RESELECT_FAIL_EMAIL)
    const before = getMockProfile()?.region
    search = 'confirm=set-mine'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<MapScreen map={pickMapMock('example', null)} />)

    const dialog = screen.getByRole('dialog', { name: `${EXAMPLE.name}을 내 동네로 설정할까요?` })
    const action = within(dialog).getByRole('button', { name: '설정하기' })
    await userEvent.setup().click(action)

    expect(within(dialog).getByRole('alert').textContent).toBe(
      '내 동네를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(action.getAttribute('aria-disabled')).toBeNull()
    expect(getMockProfile()?.region).toEqual(before)
    expect(replaceState).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')?.textContent ?? '').not.toContain('설정했어요')
  })

  it('저장하는 중에는 두 번 보내지 않고 취소 · 닫기를 막는다', async () => {
    await loginAsMember()
    const pending = deferred()
    vi.mocked(saveRegion).mockImplementationOnce(() => pending.promise)
    search = 'confirm=set-mine'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} />)

    const dialog = screen.getByRole('dialog', { name: `${EXAMPLE.name}을 내 동네로 설정할까요?` })
    const action = within(dialog).getByRole('button', { name: '설정하기' })
    await user.click(action)
    await user.click(action)
    expect(saveRegion).toHaveBeenCalledTimes(1)
    expect(action.getAttribute('aria-disabled')).toBe('true')
    const cancel = within(dialog).getByRole('button', { name: '취소' })
    expect(cancel.getAttribute('aria-disabled')).toBe('true')
    await user.click(cancel)
    await user.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(replaceState).not.toHaveBeenCalled()

    await act(async () => {
      pending.resolve()
      await pending.promise
    })
    expect(replaceState).toHaveBeenCalledWith({ sneezecastModalDepth: 0 }, '', '/map')
    expect(screen.getByRole('status').textContent).toContain(
      `${EXAMPLE.name}을 내 동네로 설정했어요`,
    )
  })

  it('저장하는 중에 뒤로 가기로 대화상자를 닫은 뒤 실패하면 알림으로 알린다', async () => {
    await loginAsMember()
    const pending = deferred()
    vi.mocked(saveRegion).mockImplementationOnce(() => pending.promise)
    search = 'confirm=set-mine'
    const { rerender } = render(<MapScreen map={pickMapMock('example', null)} />)

    const dialog = screen.getByRole('dialog', { name: `${EXAMPLE.name}을 내 동네로 설정할까요?` })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '설정하기' }))
    // 휴대폰 뒤로 가기: 주소에서 confirm 이 빠진다
    search = ''
    rerender(<MapScreen map={pickMapMock('example', null)} />)

    await act(async () => {
      pending.reject()
      await pending.promise.catch(() => {})
    })
    expect(screen.queryByRole('dialog', { name: /내 동네로 설정할까요/ })).toBeNull()
    expect(screen.getByRole('status').textContent).toContain(
      '내 동네를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
  })

  it('회원의 내 동네는 프로필의 동네다 — 처음 고른 동네라도 내 동네가 아니면 버튼이 있다', async () => {
    const seogyo = DISTRICT_MOCKS.find((district) => district.code === SEOGYO)
    await loginAsMember(seogyo ?? null)
    const user = userEvent.setup()
    render(<MapScreen map={pickMapMock('example', null)} />)

    // 처음 고른 동네(목 예시)는 회원의 내 동네가 아니다
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe(EXAMPLE.name)
    await user.click(handle())
    expect(setMineButton()).toBeTruthy()

    await user.click(districtButton(/서교동/))
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('서교동 (내 동네)')
    expect(setMineButton()).toBeNull()
  })

  it('?confirm=set-mine 은 비회원이거나 고른 동네가 이미 내 동네면 열지 않는다', async () => {
    search = 'confirm=set-mine'
    const { unmount } = render(<MapScreen map={pickMapMock('example', null)} />)
    expect(screen.queryByRole('dialog', { name: /내 동네로 설정할까요/ })).toBeNull()
    unmount()

    await loginAsMember(EXAMPLE)
    render(<MapScreen map={pickMapMock('example', null)} />)
    expect(screen.queryByRole('dialog', { name: /내 동네로 설정할까요/ })).toBeNull()
  })

  it('둘러보기 동네가 없으면 회원의 머리줄은 내 동네 이름이다', async () => {
    await loginAsMember()
    render(<MapScreen map={pickMapMock('example', null)} />)
    expect(screen.getByRole('button', { name: '동네 바꾸기, 현재 역삼1동' })).toBeTruthy()
  })
})
