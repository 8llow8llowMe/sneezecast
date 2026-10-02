// @vitest-environment jsdom
import { StrictMode } from 'react'

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { NavTrailProvider } from '@/lib/use-nav-trail'

import { NOTICE_MOCKS, type NoticeMockKey } from './mock'
import { AI_DRAFT_DISCLOSURE, NoticeScreen } from './notice-screen'
import type { RegionNotice } from './types'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
const PATH = '/notice/11680640/2025-W47'
const location = vi.hoisted(() => ({ pathname: '/notice/11680640/2025-W47' }))
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => location.pathname,
  useRouter: () => router,
}))

function data(key: NoticeMockKey, overrides: Partial<RegionNotice> = {}): RegionNotice {
  return {
    regionCode: '11680640',
    regionName: '역삼1동',
    isoWeek: '2025-W47',
    officialHref: '/official',
    ...NOTICE_MOCKS[key],
    ...overrides,
  } as RegionNotice
}

beforeEach(() => {
  search = ''
  location.pathname = PATH
  vi.clearAllMocks()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('NoticeScreen — 발행된 안내 (Guide-published)', () => {
  it('운영자 검토 배지 · 발행일 · 기준 주 · 제목 · 할 일 · 근거를 보인다', () => {
    render(<NoticeScreen data={data('published')} />)

    expect(screen.getByText('운영자 검토')).toBeDefined()
    // 모바일은 몇째 주, 태블릿 이상은 기간 (폭에 따라 하나만 보인다)
    expect(screen.getByText('11월 18일 발행 · 11월 3주 기준')).toBeDefined()
    expect(screen.getByText('11월 18일 발행 · 11월 17일~23일 기준')).toBeDefined()
    expect(
      screen.getByRole('heading', { name: '발열·기침 보고가 지난 4주보다 많이 늘었어요' }),
    ).toBeDefined()
    expect(screen.getByRole('heading', { name: '이렇게 해 주세요' })).toBeDefined()
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '1기침할 때 옷소매로 입과 코 가리기',
      '2손 씻기와 실내 환기 자주 하기',
      '3열이 나면 사람 많은 곳 피하기',
    ])
    expect(screen.getByText('근거: 질병관리청 예방수칙')).toBeDefined()
    expect(screen.queryByRole('region', { name: '정정 이력' })).toBeNull()
  })

  it('AI 초안 고지를 늘 함께 보인다 (모바일 · 태블릿 이상 자리에 하나씩)', () => {
    render(<NoticeScreen data={data('published')} />)

    expect(screen.getAllByText(AI_DRAFT_DISCLOSURE)).toHaveLength(2)
    expect(AI_DRAFT_DISCLOSURE).toContain('AI가 쓰고 운영자가 검토해 발행해요')
    expect(AI_DRAFT_DISCLOSURE).toContain('진단이 아닌 참고 정보예요')
  })

  it('운영자가 인용한 집계를 보이고 공식 정보는 따로 잇는다', () => {
    render(<NoticeScreen data={data('published')} />)

    expect(screen.getAllByText('128명')).toHaveLength(2)
    expect(screen.getAllByText('18%')).toHaveLength(2)
    expect(screen.getByText('지난 4주 평균')).toBeDefined()
    expect(screen.getByText('4주 평균')).toBeDefined()
    expect(screen.getAllByText('많이 늘었어요')[0]?.className).toContain('text-status-high-text')

    const official = screen.getByRole('link', { name: /질병관리청 발표 보기/ })
    expect(official.getAttribute('href')).toBe('/official')
    // 공식 정보 링크 안에 시민 자가보고 수치를 섞지 않는다
    expect(official.textContent).not.toMatch(/\d/)
  })

  it('머리줄 제목은 동네 이름이 든 이번 주 안내다', () => {
    render(<NoticeScreen data={data('published')} />)

    expect(screen.getAllByRole('heading', { level: 1, name: '역삼1동 이번 주 안내' })).toHaveLength(
      2,
    )
  })
})

describe('NoticeScreen — 정정된 안내 (Guide-corrected)', () => {
  it('배지 줄에 마지막 정정일을 적고 정정 이력을 보인다', () => {
    render(<NoticeScreen data={data('corrected')} />)

    expect(screen.getAllByText('11월 18일 발행 · 11월 19일 정정')).toHaveLength(2)
    const history = screen.getByRole('region', { name: '정정 이력' })
    expect(history.textContent).toBe(
      '11월 19일 정정 · 중복 보고를 제외해 참여자 수를 131명에서 128명으로 바로잡았어요. 안내 내용은 같아요.',
    )
    expect(screen.getAllByText(AI_DRAFT_DISCLOSURE)).toHaveLength(2)
  })

  it('정정이 여러 번이면 최근 것부터 모두 보이고 배지 줄은 마지막 정정일이다', () => {
    const corrected = NOTICE_MOCKS.corrected
    if (!corrected.notice) throw new Error('정정 목에는 안내가 있다')
    render(
      <NoticeScreen
        data={data('corrected', {
          notice: {
            ...corrected.notice,
            corrections: [
              { correctedOn: '2025-11-21', reason: '증상군 이름을 바로잡았어요.' },
              { correctedOn: '2025-11-19', reason: '참여자 수를 바로잡았어요.' },
            ],
          },
        })}
      />,
    )

    const items = within(screen.getByRole('region', { name: '정정 이력' }))
      .getAllByRole('listitem')
      .map((item) => item.textContent)
    expect(items).toEqual([
      '11월 21일 정정 · 증상군 이름을 바로잡았어요.',
      '11월 19일 정정 · 참여자 수를 바로잡았어요.',
    ])
    expect(screen.getAllByText('11월 18일 발행 · 11월 21일 정정')).toHaveLength(2)
  })

  it('날짜를 읽지 못하면 그 조각을 빼고 그린다', () => {
    const corrected = NOTICE_MOCKS.corrected
    if (!corrected.notice) throw new Error('정정 목에는 안내가 있다')
    render(
      <NoticeScreen
        data={data('corrected', {
          notice: {
            ...corrected.notice,
            publishedOn: '2025-13-40',
            corrections: [{ correctedOn: '', reason: '참여자 수를 바로잡았어요.' }],
          },
        })}
      />,
    )

    expect(screen.getAllByText('11월 3주 기준')).toHaveLength(1)
    expect(screen.getByRole('region', { name: '정정 이력' }).textContent).toBe(
      '정정 · 참여자 수를 바로잡았어요.',
    )
  })
})

describe('NoticeScreen — 안내 없음 (Guide-none)', () => {
  it('시민 자가보고 배지 · 안내 없음 설명 · 집계 · 공식 예방수칙 링크를 보이고 AI 고지는 없다', () => {
    render(<NoticeScreen data={data('none')} />)

    expect(screen.getByText('시민 자가보고')).toBeDefined()
    expect(screen.queryByText('운영자 검토')).toBeNull()
    expect(screen.getByText('11월 3주 기준')).toBeDefined()
    expect(screen.getByText('11월 17일~23일 기준')).toBeDefined()
    expect(screen.getByRole('heading', { name: '이번 주는 발행된 안내가 없어요' })).toBeDefined()
    expect(screen.queryByText(AI_DRAFT_DISCLOSURE)).toBeNull()
    expect(screen.queryByRole('heading', { name: '이렇게 해 주세요' })).toBeNull()
    expect(screen.getAllByText('136명')).toHaveLength(2)
    expect(screen.getByRole('link', { name: '공식 예방수칙 보기' }).getAttribute('href')).toBe(
      '/official',
    )
  })

  it('자료 부족이면 수치 · 상태색 없이 참여 진행 막대만 보인다', () => {
    const { container } = render(<NoticeScreen data={data('insufficient')} />)

    expect(screen.getByRole('heading', { name: '이번 주는 발행된 안내가 없어요' })).toBeDefined()
    expect(screen.getAllByRole('progressbar', { name: '우리 동네 참여 인원' })).toHaveLength(2)
    expect(container.textContent).not.toContain('%')
    expect(screen.queryByText('증상 보고')).toBeNull()
    expect(container.innerHTML).not.toMatch(/(text|bg)-status-(normal|slight|high|insufficient)/)
  })
})

describe('NoticeScreen — 이동', () => {
  /**
   * 루트의 앱 안 이동 기록(`NavTrailProvider`) 안에서 주소를 차례로 지나며 그린다. 안내 경로(`PATH`)에서는 안내를,
   * 다른 경로에서는 빈 화면을 그린다. 첫 주소가 이 문서를 처음 연 주소다
   */
  function visit(paths: readonly string[], { strict = false } = {}) {
    // 같은 요소 객체를 다시 넘기면 React 가 다시 그리지 않아 매번 새로 만든다
    const tree = () => {
      const ui = (
        <NavTrailProvider>
          {location.pathname === PATH ? <NoticeScreen data={data('published')} /> : <div />}
        </NavTrailProvider>
      )
      return strict ? <StrictMode>{ui}</StrictMode> : ui
    }
    const [first = PATH, ...rest] = paths
    location.pathname = first
    const { rerender } = render(tree())
    for (const path of rest) {
      location.pathname = path
      rerender(tree())
    }
  }

  it('앱 안 링크(홈의 안내 전체 보기)로 들어왔으면 뒤로가 기록을 되돌린다', async () => {
    visit(['/', PATH])

    await userEvent.click(screen.getAllByRole('button', { name: '뒤로' })[0]!)
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('StrictMode 이중 렌더 · 이중 effect 에서도 앱 안 진입을 잃지 않는다', async () => {
    visit(['/', PATH], { strict: true })

    await userEvent.click(screen.getAllByRole('button', { name: '뒤로' })[0]!)
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('주소로 연 안내 → 홈 → 링크로 같은 안내 → 휴대폰 뒤로 두 번이면 홈으로 replace 한다 (앱 밖으로 나가지 않는다)', async () => {
    // 휴대폰 뒤로 두 번으로 처음(주소로 연) 안내 기록에 돌아와 다시 그린다. 앞에 앱 기록이 없다
    visit([PATH, '/', PATH, '/', PATH])

    await userEvent.click(screen.getAllByRole('button', { name: '뒤로' })[0]!)
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('주소로 바로 들어왔으면 홈으로 기록을 바꿔 간다 (데스크톱 뒤로도 같다)', async () => {
    visit([PATH])

    await userEvent.click(screen.getAllByRole('button', { name: '뒤로' })[1]!)
    expect(router.replace).toHaveBeenCalledWith('/')
    expect(router.back).not.toHaveBeenCalled()
  })

  it('보고 버튼은 비회원이면 로그인, 동의한 회원이면 홈의 보고 흐름으로 간다', async () => {
    const { unmount } = render(<NoticeScreen data={data('published')} />)
    await userEvent.click(screen.getAllByRole('button', { name: '로그인하고 보고하기' })[0]!)
    expect(router.push).toHaveBeenLastCalledWith('/login')
    unmount()

    search = 'mock-auth=member'
    render(<NoticeScreen data={data('published')} />)
    await userEvent.click(screen.getAllByRole('button', { name: '이번 주 건강 보고하기' })[0]!)
    expect(router.push).toHaveBeenLastCalledWith('/?report=start')
  })

  it('문 연 곳 찾기는 아직 없어 준비 중 알림을 띄운다', async () => {
    render(<NoticeScreen data={data('published')} />)

    await userEvent.click(screen.getAllByRole('button', { name: '야간·휴일 문 연 곳 찾기' })[0]!)
    expect(screen.getByRole('status').textContent).toContain(
      '야간·휴일 문 연 곳 찾기 화면은 준비하고 있어요',
    )
  })

  it('둘러보기 동네(regionCode)를 서비스명 · 메뉴 · 탭바 · 보고 진입 · 뒤로 주소에 남긴다', async () => {
    search = 'mock-auth=member'
    render(
      <NavTrailProvider>
        <NoticeScreen data={data('published')} regionCode="1111051500" />
      </NavTrailProvider>,
    )

    const hrefs = [
      ...document.querySelectorAll('nav[aria-label="주요 메뉴"] a, nav[aria-label="계정 메뉴"] a'),
    ].map((link) => link.getAttribute('href'))
    // 데스크톱 가운데 메뉴 둘(홈 · 지도) + 오른쪽 끝 내 정보 + 태블릿 탭바 셋
    expect(hrefs).toEqual([
      '/?region=1111051500',
      '/map?region=1111051500',
      '/me?region=1111051500',
      '/?region=1111051500',
      '/map?region=1111051500',
      '/me?region=1111051500',
    ])
    expect(screen.getByRole('link', { name: '우리동네체온계' }).getAttribute('href')).toBe(
      '/?region=1111051500',
    )

    await userEvent.click(screen.getAllByRole('button', { name: '이번 주 건강 보고하기' })[0]!)
    expect(router.push).toHaveBeenLastCalledWith('/?region=1111051500&report=start')
    // 주소로 바로 들어왔으면 동네를 남긴 홈으로 간다
    await userEvent.click(screen.getAllByRole('button', { name: '뒤로' })[0]!)
    expect(router.replace).toHaveBeenCalledWith('/?region=1111051500')
  })

  it.each([
    ['비회원', '', 0],
    ['동의 전 회원', 'mock-auth=member-no-consent', 2],
    ['동의한 회원', 'mock-auth=member', 2],
  ])('%s: 알림(종)은 비회원에게만 그리지 않는다 (태블릿 · 데스크톱 머리줄)', (_, base, count) => {
    search = base
    render(<NoticeScreen data={data('published')} />)
    expect(screen.queryAllByRole('button', { name: '알림 설정' })).toHaveLength(count)
  })
})
