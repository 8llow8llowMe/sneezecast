// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { NavTrailProvider } from '@/lib/use-nav-trail'

import { OFFICIAL_MOCKS } from './mock'
import { OFFICIAL_PATH, OfficialScreen } from './official-screen'

// 테스트에는 Next 라우터가 없다. 주소 쿼리 · 경로는 이 값으로 흉내 낸다
let search = ''
const location = vi.hoisted(() => ({ pathname: '/official' }))
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => location.pathname,
  useRouter: () => router,
}))

/**
 * 공식 정보를 루트의 앱 안 이동 기록(`NavTrailProvider`) 안에 그린다.
 * `from` 을 주면 그 화면에서 앱 안 이동으로 온 것으로, 생략하면 주소로 바로 들어온 것으로 그린다.
 */
function renderOfficial(
  official: (typeof OFFICIAL_MOCKS)[keyof typeof OFFICIAL_MOCKS],
  { from, ...props }: { regionCode?: string; from?: string } = {},
) {
  if (from === undefined) {
    location.pathname = OFFICIAL_PATH
    return render(
      <NavTrailProvider>
        <OfficialScreen official={official} regionName="○○동" {...props} />
      </NavTrailProvider>,
    )
  }
  location.pathname = from
  const result = render(
    <NavTrailProvider>
      <div />
    </NavTrailProvider>,
  )
  location.pathname = OFFICIAL_PATH
  result.rerender(
    <NavTrailProvider>
      <OfficialScreen official={official} regionName="○○동" {...props} />
    </NavTrailProvider>,
  )
  return result
}

describe('OfficialScreen 발표가 있을 때', () => {
  beforeEach(() => {
    search = ''
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('출처 · 집계 단위 · 기준 주 · 발표일을 `공식` 배지와 함께 밝힌다', () => {
    renderOfficial(OFFICIAL_MOCKS.published)

    // 화면 제목(h1)은 폭마다 하나만 보인다 — 모바일 머리줄 · 태블릿 이상 본문 위
    const titles = screen.getAllByRole('heading', { level: 1 })
    expect(titles.map((title) => title.textContent)).toEqual(['질병관리청 발표', '질병관리청 발표'])
    expect(titles[0]?.classList.contains('tablet:hidden')).toBe(true)
    expect(titles[1]?.classList.contains('hidden')).toBe(true)
    expect(titles[1]?.classList.contains('tablet:block')).toBe(true)

    const line = screen.getByText('공식').parentElement
    // 기준 주는 모바일이 주차, 태블릿 이상이 기간이다 (시안)
    expect(line?.textContent).toBe('공식전국 · 47주11월 17일~23일 · 11월 21일 발표')
    expect(
      within(line as HTMLElement)
        .getByText('47주')
        .classList.contains('tablet:hidden'),
    ).toBe(true)
    expect(
      within(line as HTMLElement)
        .getByText('11월 17일~23일')
        .classList.contains('tablet:inline'),
    ).toBe(true)
  })

  it('단계 · 쉬운 요약 · 원문 보기(새 창)를 보인다', () => {
    renderOfficial(OFFICIAL_MOCKS.published)

    expect(screen.getByRole('term').textContent).toBe('인플루엔자')
    expect(screen.getByRole('definition').textContent).toBe('유행주의보 발령 중')

    const summary = screen.getByRole('region', { name: '쉬운 요약' })
    expect(summary.querySelectorAll('p')).toHaveLength(3)
    expect(summary.textContent).toContain('수치와 표현은 발표 원문을 기준으로 해요')

    const source = screen.getByRole('link', { name: '원문 보기 (새 창)' })
    expect(source.getAttribute('href')).toBe(OFFICIAL_MOCKS.published.sourceUrl)
    expect(source.getAttribute('target')).toBe('_blank')
    expect(source.getAttribute('rel')).toBe('noopener noreferrer')
  })

  it('시민 자가보고 값은 그리지 않고 두 정보가 다르다는 것만 알린다', () => {
    const { container } = renderOfficial(OFFICIAL_MOCKS.published)

    const text = container.textContent ?? ''
    expect(text).not.toMatch(/참여|%|평소 수준|늘었어요|자료 부족/)
    expect(text).toContain('의료기관 표본감시 자료예요.')

    const aside = screen.getByRole('complementary', { name: '시민 자가보고와 다른 점' })
    expect(aside.textContent).toContain('시민 자가보고와 무엇이 다른가요?')
    expect(aside.textContent).toContain('두 정보는 화면에서 섞지 않아요.')
  })
})

describe('OfficialScreen 받은 발표가 없을 때', () => {
  beforeEach(() => {
    search = ''
  })

  it('단계 · 요약 · 원문 보기 대신 아직 없다는 안내를 보인다', () => {
    const { container } = renderOfficial(OFFICIAL_MOCKS.empty)

    expect(screen.getByText('아직 받은 발표가 없어요')).not.toBeNull()
    expect(screen.queryByRole('region', { name: '쉬운 요약' })).toBeNull()
    expect(screen.queryByRole('definition')).toBeNull()
    expect(screen.queryByRole('link', { name: /원문 보기/ })).toBeNull()

    // 집계 단위만 밝히고 기준 주 · 발표일 · 조사 방식은 지어내지 않는다
    expect(screen.getByText('공식').parentElement?.textContent).toBe('공식전국')
    expect(container.textContent).not.toContain('표본감시')
  })
})

describe('OfficialScreen 이동', () => {
  beforeEach(() => {
    search = ''
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('주소로 바로 들어왔으면 뒤로가 홈으로 기록을 바꿔 간다', async () => {
    renderOfficial(OFFICIAL_MOCKS.published)

    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/')
    expect(router.back).not.toHaveBeenCalled()
  })

  it('앱 안에서 거쳐 왔으면 뒤로가 기록을 되돌린다 (데스크톱 뒤로도 같다)', async () => {
    renderOfficial(OFFICIAL_MOCKS.published, { from: '/' })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '뒤로' }))
    await user.click(screen.getByRole('button', { name: '뒤로, 질병관리청 발표' }))
    expect(router.back).toHaveBeenCalledTimes(2)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('동네 안내 등 다른 앱 안 화면에서 왔어도 뒤로가 기록을 되돌린다', async () => {
    renderOfficial(OFFICIAL_MOCKS.published, { from: '/notice/11680640/2025-W47' })

    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('주소로 바로 들어와 홈으로 갈 때 둘러보기 동네를 남긴다', async () => {
    renderOfficial(OFFICIAL_MOCKS.empty, { regionCode: '1111051500' })

    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/?region=1111051500')
  })

  it('둘러보기 동네를 탭바 · 메뉴 링크에 남긴다', () => {
    renderOfficial(OFFICIAL_MOCKS.published, { regionCode: '1111051500' })

    const hrefs = [...document.querySelectorAll('nav[aria-label="주요 메뉴"] a')].map((link) =>
      link.getAttribute('href'),
    )
    expect(hrefs).toHaveLength(6)
    hrefs.forEach((href) => expect(href).toMatch(/\?region=1111051500$/))
  })

  it('비회원의 보고 버튼은 로그인으로 간다', async () => {
    renderOfficial(OFFICIAL_MOCKS.published)

    const buttons = screen.getAllByRole('button', { name: '로그인하고 보고하기' })
    // 태블릿 머리줄 · 데스크톱 머리줄에 하나씩 있다
    expect(buttons).toHaveLength(2)
    await userEvent.setup().click(buttons[0] as HTMLElement)
    expect(router.push).toHaveBeenCalledWith('/login')
  })

  it('동의한 회원의 보고 버튼은 홈의 보고 흐름으로 간다', async () => {
    search = 'mock-auth=member'
    renderOfficial(OFFICIAL_MOCKS.published, { regionCode: '1111051500' })

    const buttons = screen.getAllByRole('button', { name: '이번 주 건강 보고하기' })
    await userEvent.setup().click(buttons[0] as HTMLElement)
    expect(router.push).toHaveBeenCalledWith('/?region=1111051500&report=start')
  })

  it('알림 설정은 준비 중 알림을 띄운다', async () => {
    renderOfficial(OFFICIAL_MOCKS.published)

    const [bell] = screen.getAllByRole('button', { name: '알림 설정' })
    await userEvent.setup().click(bell as HTMLElement)
    expect(screen.getByRole('status').textContent).toContain('알림 설정 화면은 준비하고 있어요')
  })
})
