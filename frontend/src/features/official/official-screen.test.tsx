// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { cancelReport, submitReport } from '@/features/report/report-client'
import { NavTrailProvider } from '@/lib/use-nav-trail'

import { OFFICIAL_MOCKS } from './mock'
import { toOfficialReport } from './official-map'
import { OFFICIAL_PATH, OfficialScreen } from './official-screen'
import type { OfficialProgramView, OfficialReport } from './types'

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
  official: OfficialReport,
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

  it('`공식` 배지와 집계 단위를 밝히고, 화면 제목은 폭마다 하나만 보인다', () => {
    renderOfficial(OFFICIAL_MOCKS.published)

    // 화면 제목(h1)은 폭마다 하나만 보인다 — 모바일 머리줄 · 태블릿 이상 본문 위
    const titles = screen.getAllByRole('heading', { level: 1 })
    expect(titles.map((title) => title.textContent)).toEqual(['질병관리청 발표', '질병관리청 발표'])
    expect(titles[0]?.classList.contains('tablet:hidden')).toBe(true)
    expect(titles[1]?.classList.contains('hidden')).toBe(true)
    expect(titles[1]?.classList.contains('tablet:block')).toBe(true)

    expect(screen.getByText('공식').parentElement?.textContent).toBe('공식전국')
  })

  it('표본감시 프로그램마다 출처 · 집계 단위 · 기준 주(질병관리청 주차 · 기간) · 원값 · 단위 · 받은 시각을 밝힌다', () => {
    renderOfficial(OFFICIAL_MOCKS.published)

    const sections = screen.getAllByRole('region')
    expect(sections.map((section) => section.getAttribute('aria-labelledby'))).toEqual([
      'official-INFLUENZA_ILI',
      'official-ARI',
      'official-ENTERIC',
    ])

    const ari = screen.getByRole('region', { name: '급성호흡기감염증' })
    expect(ari.textContent).toContain('질병관리청 표본감시 · 전국')
    expect(ari.textContent).toContain('기준 주 2026년 38주 (9월 13일~19일)')
    // 받은 시각은 발표일이 아니라 우리가 받은 시각으로 적는다
    expect(ari.textContent).toContain('단위: 발생 수 · 9월 25일 06:00에 받은 자료')
    expect(ari.textContent).not.toContain('발표일')
    const rows = within(ari)
      .getAllByRole('term')
      .map((term) => `${term.textContent} ${term.nextElementSibling?.textContent}`)
    expect(rows).toEqual([
      '합계 400건',
      '호흡기세포융합바이러스 152건',
      '리노바이러스 101건',
      '인플루엔자 바이러스 64건',
    ])
    expect(ari.textContent).toContain('합계와 신고 수가 많은 병원체 3개만 보여요.')

    // 인플루엔자 분율은 연령대별 값이고 원천 빈 칸은 0 이 아니라 값 없음이다
    const ili = screen.getByRole('region', { name: '인플루엔자 의사환자' })
    expect(within(ili).getAllByRole('term')).toHaveLength(7)
    expect(within(ili).getByText('65세 이상').nextElementSibling?.textContent).toBe('값 없음')
    expect(ili.textContent).toContain('단위: 외래 1,000명당 의사환자 분율')

    expect(screen.getByText(/수치는 발표 원문을 기준으로 해요/).textContent).toContain(
      '공공누리 제4유형',
    )
  })

  it('원문 보기는 묶음마다 원문 주소를 새 창으로 연다', () => {
    renderOfficial(OFFICIAL_MOCKS.published)

    const source = screen.getByRole('link', { name: '원문 보기, 급성호흡기감염증 (새 창)' })
    expect(source.getAttribute('href')).toBe('https://dportal.kdca.go.kr/pot/is/st/ari.do')
    expect(source.getAttribute('target')).toBe('_blank')
    expect(source.getAttribute('rel')).toBe('noopener noreferrer')
    expect(screen.getAllByRole('link', { name: /^원문 보기/ })).toHaveLength(3)
  })

  it('원문 주소가 없는 묶음에는 원문 보기를 그리지 않는다', () => {
    const [first] =
      OFFICIAL_MOCKS.published.status === 'published' ? OFFICIAL_MOCKS.published.programs : []
    renderOfficial({
      status: 'published',
      programs: [{ ...(first as OfficialProgramView), sourceUrl: null }],
    })

    expect(screen.queryByRole('link', { name: /원문 보기/ })).toBeNull()
  })

  it('시민 자가보고 값은 그리지 않고 두 정보가 다르다는 것만 알린다', () => {
    const { container } = renderOfficial(OFFICIAL_MOCKS.published)

    const text = container.textContent ?? ''
    expect(text).not.toMatch(/참여|%|평소 수준|늘었어요|자료 부족/)
    expect(text).toContain('질병관리청 표본감시 자료예요.')

    const aside = screen.getByRole('complementary', { name: '시민 자가보고와 다른 점' })
    expect(aside.textContent).toContain('시민 자가보고와 무엇이 다른가요?')
    expect(aside.textContent).toContain('두 정보는 화면에서 섞지 않아요.')
  })
})

describe('OfficialScreen 보일 자료가 없을 때', () => {
  beforeEach(() => {
    search = ''
  })

  it('받은 자료가 없으면 묶음 · 원문 보기 대신 원인을 질병관리청에 돌리지 않는 안내를 보인다', () => {
    const { container } = renderOfficial(OFFICIAL_MOCKS.empty)

    expect(screen.getByText('아직 보여 드릴 표본감시 자료가 없어요')).not.toBeNull()
    expect(
      screen.getByText('질병관리청 표본감시 자료를 받으면 여기에서 보여 드려요.'),
    ).not.toBeNull()
    expect(screen.queryByRole('region')).toBeNull()
    expect(screen.queryByRole('term')).toBeNull()
    expect(screen.queryByRole('link', { name: /원문 보기/ })).toBeNull()

    // 집계 단위 · 기준 주 · 받은 시각을 지어내지 않는다
    expect(screen.getByText('공식').parentElement?.textContent).toBe('공식')
    expect(container.textContent).not.toMatch(/기준 주|단위:|받은 자료|발표가 없어요/)
  })

  it('전수신고만 받았으면 받은 자료를 이 화면에서 아직 보이지 않는다고 알린다', () => {
    const notifiableOnly = toOfficialReport({
      programs: [
        {
          program: { code: 'NOTIFIABLE', name: '전수신고 감염병' },
          source: { code: 'KDCA_NOTIFIABLE', name: '질병관리청 전수신고' },
          sourceUrl: null,
          diseases: [],
        },
      ],
    })
    renderOfficial(notifiableOnly)

    expect(screen.getByText('아직 보여 드릴 표본감시 자료가 없어요')).not.toBeNull()
    expect(
      screen.getByText('지금 받은 자료(전수신고 등)는 이 화면에서 아직 보여 드리지 않아요.'),
    ).not.toBeNull()
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

    const hrefs = [
      ...document.querySelectorAll('nav[aria-label="주요 메뉴"] a, nav[aria-label="계정 메뉴"] a'),
    ].map((link) => link.getAttribute('href'))
    // 탭바 셋 + 데스크톱 가운데 메뉴 둘 + 오른쪽 끝 내 정보
    expect(hrefs).toHaveLength(6)
    hrefs.forEach((href) => expect(href).toMatch(/\?region=1111051500$/))
    expect(screen.getByRole('link', { name: '우리동네체온계' }).getAttribute('href')).toBe(
      '/?region=1111051500',
    )
  })

  it('머리줄 동네 이름은 공식 정보로 돌아올 둘러볼 동네 고르기를 연다', async () => {
    search = 'region=1111051500&mock=published'
    renderOfficial(OFFICIAL_MOCKS.published, { regionCode: '1111051500' })

    await userEvent.setup().click(screen.getByRole('button', { name: '동네 바꾸기, 현재 ○○동' }))
    expect(router.push).toHaveBeenCalledWith('/browse/region?next=%2Fofficial&region=1111051500')
  })

  it('비회원의 보고 버튼은 보고하려던 로그인(?intent=report)으로 간다', async () => {
    renderOfficial(OFFICIAL_MOCKS.published)

    const buttons = screen.getAllByRole('button', { name: '로그인하고 보고하기' })
    // 태블릿 머리줄 · 데스크톱 머리줄에 하나씩 있다
    expect(buttons).toHaveLength(2)
    await userEvent.setup().click(buttons[0] as HTMLElement)
    expect(router.push).toHaveBeenCalledWith('/login?intent=report')
  })

  it('동의한 회원의 보고 버튼은 홈의 보고 흐름으로 간다', async () => {
    search = 'mock-auth=member'
    renderOfficial(OFFICIAL_MOCKS.published, { regionCode: '1111051500' })

    const buttons = screen.getAllByRole('button', { name: '이번 주 건강 보고하기' })
    await userEvent.setup().click(buttons[0] as HTMLElement)
    expect(router.push).toHaveBeenCalledWith('/?region=1111051500&report=start')
  })

  it('동의한 회원이 이번 주 보고를 보냈으면 보고 버튼이 완료 · 수정하기이고 갈 곳은 같다', async () => {
    await submitReport({ kind: 'none' }, null, 'mock')
    search = 'mock-auth=member'
    renderOfficial(OFFICIAL_MOCKS.published, { regionCode: '1111051500' })

    const buttons = screen.getAllByRole('button', { name: '이번 주 보고 완료 · 수정하기' })
    // 태블릿 머리줄 · 데스크톱 머리줄에 하나씩 있다
    expect(buttons).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '이번 주 건강 보고하기' })).toBeNull()
    await userEvent.setup().click(buttons[0] as HTMLElement)
    expect(router.push).toHaveBeenCalledWith('/?region=1111051500&report=start')
    await cancelReport('mock')
  })

  it('알림(종)은 동네 · 덮어쓰기를 남긴 채 알림 설정으로 간다', async () => {
    search = 'mock-auth=member-no-consent&mock-push=supported&confirm=unknown'
    renderOfficial(OFFICIAL_MOCKS.published)

    // 태블릿 머리줄 · 데스크톱 머리줄에 하나씩 있다
    expect(screen.getAllByRole('button', { name: '알림 설정' })).toHaveLength(2)

    const [bell] = screen.getAllByRole('button', { name: '알림 설정' })
    await userEvent.setup().click(bell as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(
      '/me/notifications?mock-auth=member-no-consent&mock-push=supported',
    )
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('비회원에게는 어느 머리줄에도 알림(종)을 그리지 않는다', () => {
    renderOfficial(OFFICIAL_MOCKS.published)
    expect(screen.queryByRole('button', { name: '알림 설정' })).toBeNull()
  })
})
