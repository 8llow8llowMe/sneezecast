// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from '@/features/auth/auth-client'
import {
  agreeHealthConsent,
  getMockSession,
  loginWithEmail,
  resetMockSession,
  saveRegion,
} from '@/features/auth/auth-client'
import { consentFor } from '@/features/auth/legal'
import { leaveHomeNotice, takeHomeNotice } from '@/features/me/leave-notice'
import { cancelReport, submitReport } from '@/features/report/report-client'

import { HomeScreen } from './home-screen'
import { HOME_MOCKS } from './mock'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  useRouter: () => router,
}))

vi.mock('@/features/auth/auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, agreeHealthConsent: vi.fn(actual.agreeHealthConsent) }
})

/** 닫힌 dialog 는 보조기술 트리에서 빠지므로 제목으로 찾는다 (홈에는 보고 · 판단 기준 두 개가 있다) */
function explainDialog() {
  return (
    [...document.querySelectorAll('dialog')].find(
      (dialog) => dialog.querySelector('h2')?.textContent === '이렇게 판단했어요',
    ) ?? null
  )
}

describe('HomeScreen', () => {
  beforeEach(() => {
    search = ''
    window.history.replaceState(null, '', '/')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('내 정보가 남긴 알림(동의 철회 뒤 로그아웃)을 한 번 띄우고 비운다', () => {
    takeHomeNotice()
    leaveHomeNotice('건강정보 동의를 철회하고 로그아웃했어요')
    render(<HomeScreen week={HOME_MOCKS.normal} />)

    expect(
      screen.getByText('건강정보 동의를 철회하고 로그아웃했어요').closest('[role="status"]'),
    ).not.toBeNull()
    expect(takeHomeNotice()).toBeNull()
  })

  it('화면 제목(h1)에 동네 이름을 담는다', () => {
    render(<HomeScreen week={HOME_MOCKS.normal} />)
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
      '○○동 이번 주 우리 동네 건강',
    )
  })

  it('공식 정보는 상태 카드와 다른 요소에 `공식` 배지와 함께 놓인다', () => {
    render(<HomeScreen week={HOME_MOCKS.high} />)
    const card = screen.getByRole('region', { name: '우리 동네 이번 주 상태' })

    expect(card.textContent).not.toContain('질병관리청')
    // 공식 정보 행은 모바일 자리와 태블릿 이상 자리에 하나씩 있고 폭에 따라 하나만 보인다
    const officialLinks = screen
      .getAllByRole('link')
      .filter((link) => link.textContent?.includes('전국 인플루엔자 유행주의보'))
    expect(officialLinks).toHaveLength(2)
    officialLinks.forEach((link) => expect(link.textContent).toContain('공식'))
  })

  it('아직 없는 화면으로 가는 버튼은 준비 중 알림을 띄운다', async () => {
    search = 'mock-auth=member'
    render(<HomeScreen week={HOME_MOCKS.normal} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '알림 설정' }))
    expect(
      screen.getByText('알림 설정 화면은 준비하고 있어요').closest('[role="status"]'),
    ).not.toBeNull()
  })

  it.each([
    ['비회원', '', false],
    ['동의 전 회원', 'mock-auth=member-no-consent', true],
    ['동의한 회원', 'mock-auth=member', true],
  ])('%s 머리줄의 알림(종): 비회원에게만 그리지 않는다', (_, base, shown) => {
    search = base
    render(<HomeScreen week={HOME_MOCKS.normal} />)

    expect(screen.queryByRole('button', { name: '알림 설정' }) !== null).toBe(shown)
  })

  it('데스크톱 서비스명은 둘러보기 동네를 남긴 채 홈으로 가는 링크다', () => {
    render(<HomeScreen week={HOME_MOCKS.normal} regionCode="1111051500" />)

    expect(screen.getByRole('link', { name: '우리동네체온계' }).getAttribute('href')).toBe(
      '/?region=1111051500',
    )
  })
})

describe('HomeScreen 판단 기준', () => {
  beforeEach(() => {
    search = ''
    window.history.replaceState(null, '', '/')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('"왜 이렇게 보나요?" 는 다른 쿼리를 남긴 채 ?explain=1 을 기록에 쌓는다', async () => {
    search = 'mock=high'
    const pushState = vi.spyOn(window.history, 'pushState')
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '왜 이렇게 보나요?' }))
    expect(pushState).toHaveBeenCalledWith({ sneezecastModalDepth: 1 }, '', '?mock=high&explain=1')
  })

  it('?explain=1 이면 판단 기준이 열린다', () => {
    search = 'explain=1'
    render(<HomeScreen week={HOME_MOCKS.high} />)

    expect(explainDialog()?.open).toBe(true)
    expect(screen.getByRole('dialog', { name: '이렇게 판단했어요' })).toBeDefined()
  })

  it('주소로 바로 열린 판단 기준을 닫으면 뒤로 가지 않고 주소에서 explain 만 지운다', async () => {
    search = 'mock=high&explain=1'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    const go = vi.spyOn(window.history, 'go')
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '확인' }))
    expect(replaceState).toHaveBeenCalledWith({ sneezecastModalDepth: 0 }, '', '?mock=high')
    expect(go).not.toHaveBeenCalled()
  })

  it('앱 안에서 연 판단 기준을 닫으면 쌓은 기록을 되돌린다 (휴대폰 뒤로 가기와 같다)', async () => {
    const go = vi.spyOn(window.history, 'go').mockImplementation(() => {})
    const user = userEvent.setup()
    const { rerender } = render(<HomeScreen week={HOME_MOCKS.high} />)

    await user.click(screen.getByRole('button', { name: '왜 이렇게 보나요?' }))
    // 테스트의 useSearchParams 는 주소를 따라가지 않으므로, 열린 뒤 다시 그린 상태를 흉내 낸다
    search = 'explain=1'
    rerender(<HomeScreen week={{ ...HOME_MOCKS.high }} />)
    await user.click(screen.getByRole('button', { name: '확인' }))

    expect(go).toHaveBeenCalledWith(-1)
  })

  it('자료 부족이면 버튼이 없고, ?explain=1 로 들어와도 열지 않는다', () => {
    search = 'explain=1'
    render(<HomeScreen week={HOME_MOCKS.insufficient} />)

    expect(screen.queryByRole('button', { name: '왜 이렇게 보나요?' })).toBeNull()
    expect(explainDialog()).toBeNull()
  })
})

/** 닫힌 dialog 는 보조기술 트리에서 빠지므로 제목으로 찾는다 */
function dialogTitled(title: string) {
  return (
    [...document.querySelectorAll('dialog')].find((dialog) =>
      dialog.querySelector('h2')?.textContent?.includes(title),
    ) ?? null
  )
}

const REPORT_BUTTONS = {
  guest: '로그인하고 보고하기',
  member: '이번 주 건강 보고하기',
  reported: '이번 주 보고 완료 · 수정하기',
} as const

describe('HomeScreen 보고 진입 (목 회원 상태)', () => {
  beforeEach(async () => {
    search = ''
    window.history.replaceState(null, '', '/')
    resetMockSession()
    // 덮어쓰기(?mock-auth=)로 보낸 보고는 세션이 바뀌지 않아 남는다
    await cancelReport()
    router.push.mockClear()
    vi.mocked(agreeHealthConsent).mockClear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('기본은 비회원 홈이다: 보고 영역 · 머리줄 버튼 문구가 바뀌고 안내를 보인다', () => {
    render(<HomeScreen week={HOME_MOCKS.insufficient} />)

    // 하단(모바일) · 머리줄(태블릿 · 데스크톱) 두 버튼
    expect(screen.getAllByRole('button', { name: REPORT_BUTTONS.guest })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: REPORT_BUTTONS.member })).toBeNull()
    expect(screen.getByText('로그인하면 이번 주 보고를 할 수 있어요')).toBeDefined()
    expect(
      screen
        .getByText('로그인하면 이번 주 보고를 할 수 있어요. 동네 현황은 지금처럼 볼 수 있어요.')
        .closest('[role="status"]'),
    ).not.toBeNull()
  })

  it('비회원 홈도 자료 부족이면 수치 없이 참여 진행만 보인다', () => {
    render(<HomeScreen week={HOME_MOCKS.insufficient} />)
    const card = screen.getByRole('region', { name: '우리 동네 이번 주 상태' })

    expect(card.textContent).not.toMatch(/%/)
    expect(screen.getByRole('progressbar')).toBeDefined()
  })

  it.each([0, 1])(
    '비회원이 보고 버튼(%i번째)을 누르면 로그인 안내 시트를 기록에 쌓는다 — 다른 쿼리는 남긴다',
    async (index) => {
      search = 'mock=high&region=1111051500'
      const pushState = vi.spyOn(window.history, 'pushState')
      render(<HomeScreen week={HOME_MOCKS.high} regionCode="1111051500" />)

      const buttons = screen.getAllByRole('button', { name: REPORT_BUTTONS.guest })
      await userEvent.setup().click(buttons[index]!)
      expect(pushState).toHaveBeenCalledWith(
        { sneezecastModalDepth: 1 },
        '',
        '?mock=high&region=1111051500&report=login',
      )
    },
  )

  it.each([
    ['member-no-consent', '?mock-auth=member-no-consent&report=health-consent'],
    ['member', '?mock-auth=member&report=start'],
  ])('%s 가 보고 버튼(하단 · 머리줄)을 누르면 맞는 단계를 연다', async (auth, url) => {
    search = `mock-auth=${auth}`
    const pushState = vi.spyOn(window.history, 'pushState')
    const user = userEvent.setup()
    render(<HomeScreen week={HOME_MOCKS.high} />)

    const buttons = screen.getAllByRole('button', { name: REPORT_BUTTONS.member })
    expect(buttons).toHaveLength(2)
    for (const button of buttons) await user.click(button)
    expect(pushState).toHaveBeenNthCalledWith(1, { sneezecastModalDepth: 1 }, '', url)
    expect(pushState).toHaveBeenCalledTimes(2)
  })

  it('목 세션이 회원이면 그 상태로 나뉜다 (덮어쓰기가 없을 때)', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    const pushState = vi.spyOn(window.history, 'pushState')
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await userEvent
      .setup()
      .click(screen.getAllByRole('button', { name: REPORT_BUTTONS.member })[0]!)
    expect(pushState).toHaveBeenCalledWith(
      { sneezecastModalDepth: 1 },
      '',
      '?report=health-consent',
    )
  })

  it('?report=login 이면 비회원에게 로그인 안내 시트가 열리고 보고 흐름은 없다', () => {
    search = 'report=login'
    render(<HomeScreen week={HOME_MOCKS.high} />)

    expect(dialogTitled('보고는 회원만 할 수 있어요')?.open).toBe(true)
    expect(dialogTitled('건강은 어땠나요?')).toBeNull()
  })

  it.each([
    ['report=start', '?report=login'],
    ['region=1111051500&report=confirm', '?region=1111051500&report=login'],
    [
      'mock-auth=member-no-consent&report=start',
      '?mock-auth=member-no-consent&report=health-consent',
    ],
  ])(
    '주소로 바로 들어온 %s 는 보고 흐름을 열지 않고 맞는 시트로 바꾼다(replace)',
    async (query, expected) => {
      search = query
      const replaceState = vi.spyOn(window.history, 'replaceState')
      const pushState = vi.spyOn(window.history, 'pushState')
      render(<HomeScreen week={HOME_MOCKS.high} />)

      // 첫 커밋의 effect 안에서 바로 바꾸면 Next 가 아직 history 를 감싸지 않아 모른다. 다음 틱으로 미뤘는지 본다
      expect(replaceState).not.toHaveBeenCalled()
      // 주소를 정리하기 전에도 맞는 시트가 바로 열려 있다
      expect(
        dialogTitled(
          expected.includes('health-consent')
            ? '증상 보고에 동의해 주세요'
            : '보고는 회원만 할 수 있어요',
        )?.open,
      ).toBe(true)
      await waitFor(() =>
        expect(replaceState).toHaveBeenCalledWith({ sneezecastModalDepth: 0 }, '', expected),
      )
      expect(pushState).not.toHaveBeenCalled()
      expect(dialogTitled('건강은 어땠나요?')).toBeNull()
    },
  )

  it('동의한 회원은 ?report=start 로 바로 보고 흐름이 열린다', () => {
    search = 'mock-auth=member&report=start'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<HomeScreen week={HOME_MOCKS.high} />)

    expect(dialogTitled('건강은 어땠나요?')?.open).toBe(true)
    expect(replaceState).not.toHaveBeenCalled()
  })

  it('로그인 안내 시트를 주소로 열고 닫으면 report 만 지우고 region · mock 은 남긴다', async () => {
    search = 'mock=high&region=1111051500&report=login'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<HomeScreen week={HOME_MOCKS.high} regionCode="1111051500" />)

    await userEvent.setup().click(screen.getByRole('button', { name: '닫기' }))
    expect(replaceState).toHaveBeenLastCalledWith(
      { sneezecastModalDepth: 0 },
      '',
      '?mock=high&region=1111051500',
    )
  })

  it('로그인 안내 시트의 이메일로 시작하기는 둘러보기 동네를 넘겨 보고하려던 이메일 로그인으로 간다', async () => {
    search = 'region=11680640&report=login'
    render(<HomeScreen week={HOME_MOCKS.high} regionCode="11680640" />)

    await userEvent.setup().click(screen.getByRole('button', { name: '이메일로 시작하기' }))
    expect(router.push).toHaveBeenCalledWith('/login/email?region=11680640&intent=report')
  })

  // 보고하려던 로그인(#136)은 로그인 뒤 `/?region=…&report=start` 로 온다. 목 로그인은 늘 미동의 회원이라 홈이 동의 시트로 고친다
  it('로그인 뒤 보고 진입(?report=start)으로 돌아온 미동의 회원(목 세션)에게는 동의 시트를 열고 주소를 고친다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    search = 'region=11680640&report=start'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<HomeScreen week={HOME_MOCKS.high} regionCode="11680640" />)

    expect(dialogTitled('증상 보고에 동의해 주세요')?.open).toBe(true)
    expect(dialogTitled('건강은 어땠나요?')).toBeNull()
    await waitFor(() =>
      expect(replaceState).toHaveBeenCalledWith(
        { sneezecastModalDepth: 0 },
        '',
        '?region=11680640&report=health-consent',
      ),
    )
  })

  it('동의 시트에서 동의하면 동의를 한 번 보내고 보고 시작으로 바꾼다 — 덮어쓰기 쿼리도 지운다', async () => {
    search = 'region=1111051500&mock-auth=member-no-consent&report=health-consent'
    window.history.replaceState(
      { sneezecastModalDepth: 1 },
      '',
      '/?region=1111051500&mock-auth=member-no-consent&report=health-consent',
    )
    const replaceState = vi.spyOn(window.history, 'replaceState')
    const user = userEvent.setup()
    render(<HomeScreen week={HOME_MOCKS.high} regionCode="1111051500" />)

    expect(dialogTitled('증상 보고에 동의해 주세요')?.open).toBe(true)
    await user.click(
      screen.getByRole('checkbox', { name: /건강·증상 정보\(민감정보\) 처리에 동의해요/ }),
    )
    await user.click(screen.getByRole('button', { name: '동의하고 보고하기' }))

    await waitFor(() =>
      expect(replaceState).toHaveBeenCalledWith(
        { sneezecastModalDepth: 1 },
        '',
        '?region=1111051500&report=start',
      ),
    )
    expect(agreeHealthConsent).toHaveBeenCalledTimes(1)
    expect(getMockSession()).toBe('member')
  })

  it('목 세션으로 미동의 회원이 동의하면 바로 동의한 회원이 되어 보고 시작으로 바뀐다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    search = 'report=health-consent'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    const user = userEvent.setup()
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await user.click(
      screen.getByRole('checkbox', { name: /건강·증상 정보\(민감정보\) 처리에 동의해요/ }),
    )
    await user.click(screen.getByRole('button', { name: '동의하고 보고하기' }))

    await waitFor(() =>
      expect(replaceState).toHaveBeenCalledWith({ sneezecastModalDepth: 0 }, '', '?report=start'),
    )
    expect(getMockSession()).toBe('member')
  })

  it('동의 시트의 나중에 할게요는 닫는다', async () => {
    search = 'mock-auth=member-no-consent&report=health-consent'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '나중에 할게요' }))
    expect(replaceState).toHaveBeenLastCalledWith(
      { sneezecastModalDepth: 0 },
      '',
      '?mock-auth=member-no-consent',
    )
    expect(agreeHealthConsent).not.toHaveBeenCalled()
  })

  it('둘러보기 동네(regionCode)를 탭바 · 데스크톱 메뉴 링크에 붙인다', () => {
    render(<HomeScreen week={HOME_MOCKS.insufficient} regionCode="1111051500" />)
    const hrefs = [
      ...document.querySelectorAll('nav[aria-label="주요 메뉴"] a, nav[aria-label="계정 메뉴"] a'),
    ].map((link) => link.getAttribute('href'))

    // 탭바 셋 + 데스크톱 가운데 메뉴 둘(홈 · 지도) + 오른쪽 끝 내 정보 하나
    expect(hrefs).toHaveLength(6)
    hrefs.forEach((href) => expect(href).toMatch(/\?region=1111051500$/))
    expect(hrefs).toContain('/?region=1111051500')
    expect(hrefs).toContain('/map?region=1111051500')
    expect(hrefs.filter((href) => href === '/me?region=1111051500')).toHaveLength(2)
  })

  it('목 세션이 바뀌면 같은 홈이 회원 홈으로 다시 그려진다', async () => {
    render(<HomeScreen week={HOME_MOCKS.high} />)
    expect(screen.getAllByRole('button', { name: REPORT_BUTTONS.guest })).toHaveLength(2)

    await act(() => loginWithEmail('dong@example.com', 'dongne2026', 'mock'))
    expect(screen.getAllByRole('button', { name: REPORT_BUTTONS.member })).toHaveLength(2)
    expect(screen.queryByText('로그인하면 이번 주 보고를 할 수 있어요')).toBeNull()
  })
})

describe('HomeScreen 보낸 뒤 보고 버튼 (Flow 의 reported)', () => {
  beforeEach(async () => {
    search = 'mock-auth=member'
    window.history.replaceState(null, '', '/?mock-auth=member')
    resetMockSession()
    await cancelReport()
  })

  afterEach(async () => {
    vi.restoreAllMocks()
    await cancelReport()
  })

  it('보낸 뒤면 하단 · 머리줄 버튼이 완료 · 수정하기이고, 하단은 회색 보조 버튼이다', async () => {
    await submitReport({ kind: 'none' })
    render(<HomeScreen week={HOME_MOCKS.high} />)

    const buttons = screen.getAllByRole('button', { name: REPORT_BUTTONS.reported })
    expect(buttons).toHaveLength(2)
    expect(screen.queryByRole('button', { name: REPORT_BUTTONS.member })).toBeNull()
    // DOM 순서: 머리줄(태블릿 · 데스크톱) → 하단(모바일)
    const [header, bottom] = buttons
    expect(header?.className).toContain('bg-brand')
    expect(bottom?.className).toContain('bg-section')
    expect(bottom?.className).not.toContain('bg-brand')
  })

  it('보낸 뒤 버튼을 누르면 보고 흐름(?report=start)을 연다 — 보고 흐름이 보낸 보고를 보고 수정으로 연다', async () => {
    await submitReport({ kind: 'none' })
    const pushState = vi.spyOn(window.history, 'pushState')
    const user = userEvent.setup()
    render(<HomeScreen week={HOME_MOCKS.high} />)

    for (const button of screen.getAllByRole('button', { name: REPORT_BUTTONS.reported })) {
      await user.click(button)
    }
    expect(pushState).toHaveBeenNthCalledWith(
      1,
      { sneezecastModalDepth: 1 },
      '',
      '?mock-auth=member&report=start',
    )
    expect(pushState).toHaveBeenCalledTimes(2)
  })

  it('보내면 같은 홈의 버튼이 바로 바뀌고, 되돌리면 다시 보고하기다', async () => {
    render(<HomeScreen week={HOME_MOCKS.high} />)
    expect(screen.getAllByRole('button', { name: REPORT_BUTTONS.member })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: REPORT_BUTTONS.member })[1]?.className).toContain(
      'bg-brand',
    )

    await act(() => submitReport({ kind: 'none' }))
    expect(screen.getAllByRole('button', { name: REPORT_BUTTONS.reported })).toHaveLength(2)

    await act(() => cancelReport())
    expect(screen.getAllByRole('button', { name: REPORT_BUTTONS.member })).toHaveLength(2)
  })

  it('미동의 회원(덮어쓰기)은 보낸 보고가 남아 있어도 보고하기다 — 누르면 동의 시트다', async () => {
    await submitReport({ kind: 'none' })
    search = 'mock-auth=member-no-consent'
    render(<HomeScreen week={HOME_MOCKS.high} />)

    expect(screen.getAllByRole('button', { name: REPORT_BUTTONS.member })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: REPORT_BUTTONS.reported })).toBeNull()
  })

  it('서버 그림(하이드레이션 첫 그림)은 보낸 보고가 있어도 보고하기로 그린다', async () => {
    await submitReport({ kind: 'none' })
    const html = renderToString(<HomeScreen week={HOME_MOCKS.high} />)

    expect(html).toContain(REPORT_BUTTONS.member)
    expect(html).not.toContain(REPORT_BUTTONS.reported)
  })
})

describe('HomeScreen 다시 들어온 회원 (약관 재동의 · 동네 다시 고르기)', () => {
  beforeEach(() => {
    search = ''
    resetMockSession()
    router.replace.mockClear()
    window.history.replaceState(null, '', '/')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('비회원은 조건 덮어쓰기가 있어도 홈에 그대로 둔다', () => {
    search = 'mock-required=terms,region'
    render(<HomeScreen week={HOME_MOCKS.normal} />)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('조건이 있는 회원은 재동의부터 보낸다 (동네 · 덮어쓰기만 남긴다)', () => {
    search = 'region=11440660&mock-auth=member&mock-required=terms,region&report=start'
    render(<HomeScreen week={HOME_MOCKS.normal} regionCode="11440660" />)
    expect(router.replace).toHaveBeenCalledWith(
      '/terms/reconsent?region=11440660&mock-auth=member&mock-required=terms%2Cregion',
    )
  })

  // 원시 history 를 바꾸면 Next 가 대기 중인 router.replace 를 버린다(리뷰에서 프로덕션 빌드로 재현) — 보낼 곳이 있으면 정리하지 않는다
  it('보고 진입이 맞지 않아도 보낼 곳이 있으면 주소를 정리하지 않고 재동의로만 보낸다', async () => {
    search = 'mock-auth=member-no-consent&mock-required=terms&report=start'
    window.history.replaceState(null, '', `/?${search}`)
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<HomeScreen week={HOME_MOCKS.normal} />)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(replaceState).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith(
      '/terms/reconsent?mock-auth=member-no-consent&mock-required=terms',
    )
  })

  // 보고하려던 로그인(#136)은 로그인 뒤 `?report=start` 로 온다. 조건이 남았으면 보내기 전 그림에 동의 시트가 비치지 않아야 한다
  it('보낼 곳이 있으면 보고 진입 시트를 열지 않는다 (보고하려던 로그인 뒤 재동의가 남은 회원)', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026', 'mock')
    search = 'region=11680640&report=start'
    render(<HomeScreen week={HOME_MOCKS.normal} regionCode="11680640" />)

    expect(dialogTitled('증상 보고에 동의해 주세요')?.open ?? false).toBe(false)
    expect(router.replace).toHaveBeenCalledWith('/terms/reconsent?region=11680640')
  })

  it('하이드레이션으로 열어도 첫 그림(비회원)의 보고 진입 정리가 남지 않는다', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026', 'mock')
    search = 'report=start'
    window.history.replaceState(null, '', `/?${search}`)
    const replaceState = vi.spyOn(window.history, 'replaceState')
    const ui = <HomeScreen week={HOME_MOCKS.normal} />
    const container = document.createElement('div')
    document.body.append(container)
    container.innerHTML = renderToString(ui)
    render(ui, { container, hydrate: true })
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(replaceState).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/terms/reconsent')
  })

  it('동네가 폐지된 회원(목 프로필)은 동네 다시 고르기로 보낸다', async () => {
    await loginWithEmail('reselect@example.com', 'dongne2026', 'mock')
    render(<HomeScreen week={HOME_MOCKS.normal} />)
    expect(router.replace).toHaveBeenCalledWith('/setup/region?reselect=1')
  })
})

describe('HomeScreen 오프라인 띠 (State-offline)', () => {
  const OFFLINE_WITH_TIME = '오프라인이에요. 11월 19일 09:00에 받은 정보예요'
  const PUSH_INAPP = '○○동 이번 주 안내가 발행됐어요 · 알림 대신 여기서 알려드려요'

  /** 브라우저 연결 상태를 바꾸고 그 이벤트를 보낸다 (`useOnline` 이 navigator.onLine 과 online · offline 이벤트를 따른다) */
  function goOnline(value: boolean) {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(value)
    act(() => {
      window.dispatchEvent(new Event(value ? 'online' : 'offline'))
    })
  }

  beforeEach(() => {
    search = ''
    window.history.replaceState(null, '', '/')
    resetMockSession()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('온라인이면 띠가 없고, 끊기면 받은 시각과 함께 띄우고, 다시 연결되면 거둔다', () => {
    // 2026-11-19 00:00 UTC = 한국 09:00
    render(<HomeScreen week={HOME_MOCKS.normal} receivedAt="2026-11-19T00:00:00Z" />)
    expect(screen.queryByText(/오프라인이에요/)).toBeNull()

    goOnline(false)
    expect(screen.getByText(OFFLINE_WITH_TIME).closest('[role="status"]')?.classList).toContain(
      'mt-3',
    )

    goOnline(true)
    expect(screen.queryByText(/오프라인이에요/)).toBeNull()
  })

  it('받은 시각을 모르면 시각 없이 "오프라인이에요" 만 보인다', () => {
    render(<HomeScreen week={HOME_MOCKS.normal} />)
    goOnline(false)
    expect(screen.getByText('오프라인이에요')).toBeDefined()
  })

  it('알림 대체 안내와 함께 나오면 오프라인 띠가 위에 놓이고 둘의 여백이 같다', () => {
    search = 'mock-auth=member&mock-push=unsupported'
    render(<HomeScreen week={HOME_MOCKS.high} receivedAt="2026-11-19T00:00:00Z" />)
    goOnline(false)

    const offline = screen.getByText(OFFLINE_WITH_TIME).closest('[role="status"]')!
    const pushInapp = screen.getByText(PUSH_INAPP).closest('[role="status"]')!
    expect(
      offline.compareDocumentPosition(pushInapp) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    expect(
      [...offline.classList].filter((name) => /^(tablet:|desktop:)?m[xt]-/.test(name)),
    ).toEqual([...pushInapp.classList].filter((name) => /^(tablet:|desktop:)?m[xt]-/.test(name)))

    // 다시 연결되면 오프라인 띠만 거두고 알림 대체 안내는 남는다
    goOnline(true)
    expect(screen.queryByText(/오프라인이에요/)).toBeNull()
    expect(screen.getByText(PUSH_INAPP)).toBeDefined()
  })

  it('서버 그림은 오프라인이어도 띠 없이 그린다 — 하이드레이션 첫 그림과 맞춘다', () => {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false)
    const html = renderToString(
      <HomeScreen week={HOME_MOCKS.normal} receivedAt="2026-11-19T00:00:00Z" />,
    )
    expect(html).not.toContain('오프라인이에요')
  })
})

describe('HomeScreen 둘러보기 동네 · 내 동네 (#141)', () => {
  const YEOKSAM1 = { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }
  const SEOGYO = '11440660'

  beforeEach(() => {
    search = ''
    resetMockSession()
    router.push.mockClear()
    window.history.replaceState(null, '', '/')
  })

  /** 내 동네가 역삼1동이고 건강정보 동의를 한 목 회원 */
  async function signInWithRegion() {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await saveRegion(YEOKSAM1)
    await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
  }

  it('머리줄 동네 이름은 홈으로 돌아올 둘러볼 동네 고르기를 연다 (비회원도 같다)', async () => {
    search = 'region=raw&report=login'
    render(<HomeScreen week={{ ...HOME_MOCKS.normal, regionName: '서교동' }} regionCode={SEOGYO} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '동네 바꾸기, 현재 서교동' }))
    expect(router.push).toHaveBeenCalledWith(`/browse/region?next=%2F&region=${SEOGYO}`)
  })

  it('둘러보기 동네가 없으면 회원은 내 동네를 보인다', async () => {
    await signInWithRegion()
    render(<HomeScreen week={HOME_MOCKS.normal} />)

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
      '역삼1동 이번 주 우리 동네 건강',
    )
    expect(screen.getByRole('button', { name: '동네 바꾸기, 현재 역삼1동' })).toBeDefined()
  })

  it('다른 동네를 둘러보는 중이면 그 동네를 보이고, 보고 흐름은 내 동네로 보고한다고 밝힌다', async () => {
    await signInWithRegion()
    search = `region=${SEOGYO}&report=start`
    render(<HomeScreen week={{ ...HOME_MOCKS.normal, regionName: '서교동' }} regionCode={SEOGYO} />)

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
      '서교동 이번 주 우리 동네 건강',
    )
    expect(
      await screen.findByText('보고는 내 동네 역삼1동 기준이에요. 둘러보는 동네와 달라요.'),
    ).toBeDefined()
  })

  it('둘러보는 동네가 내 동네면 보고 동네를 따로 밝히지 않는다', async () => {
    await signInWithRegion()
    search = `region=${YEOKSAM1.code}&report=start`
    render(
      <HomeScreen
        week={{ ...HOME_MOCKS.normal, regionName: '역삼1동' }}
        regionCode={YEOKSAM1.code}
      />,
    )

    await screen.findByText('증상 없었어요')
    expect(screen.queryByText(/둘러보는 동네와 달라요/)).toBeNull()
  })
})
