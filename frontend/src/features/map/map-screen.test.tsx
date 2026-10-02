// @vitest-environment jsdom
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

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
    expect(router.push).toHaveBeenCalledWith('/login')
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
})
