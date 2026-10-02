// @vitest-environment jsdom
import type { ReactNode } from 'react'

import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from '@/features/auth/auth-client'
import {
  agreeHealthConsent,
  getMockProfile,
  getMockSession,
  loginWithEmail,
  logout,
  resetMockSession,
  saveRegion,
  signup,
  withdrawHealthConsent,
  withdrawMembership,
} from '@/features/auth/auth-client'
import { consentFor } from '@/features/auth/legal'
import { resetMemberInfoForTests, startMemberInfo } from '@/features/auth/member-info'
import { cancelReport, getSubmittedReport, submitReport } from '@/features/report/report-client'
import { resetSessionForTests, setSession } from '@/lib/session/session-store'
import { clearSessionExpiring, notifySessionExpired } from '@/lib/session-expiry'
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

import { takeHomeNotice } from './leave-notice'
import { MeScreen } from './me-screen'
import { MeTrailProvider, useMeTrail } from './me-trail'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/me',
  useRouter: () => router,
}))

vi.mock('@/features/auth/auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return {
    ...actual,
    logout: vi.fn(actual.logout),
    withdrawHealthConsent: vi.fn(actual.withdrawHealthConsent),
    withdrawMembership: vi.fn(actual.withdrawMembership),
  }
})

// 내 정보는 레이아웃의 MeTrailProvider 안에서 그려진다(계정 화면이 남긴 알림을 읽는다)
// 앱에서는 루트 레이아웃의 NavTrailProvider(앱 안 이동 기록)가 내 정보 레이아웃을 감싼다
function withTrail(children: ReactNode) {
  return (
    <NavTrailProvider>
      <MeTrailProvider>{children}</MeTrailProvider>
    </NavTrailProvider>
  )
}

function renderMe(props: { regionCode?: string } = {}) {
  return render(withTrail(<MeScreen regionName="○○동" {...props} />))
}

/** 계정 화면처럼 레이아웃에 알림을 남긴다. 내 정보가 그려지기 전에 부른다 */
let trail: ReturnType<typeof useMeTrail> | null = null
function TrailProbe() {
  trail = useMeTrail()
  return null
}

/** 알림 섹션 (제목이 있는 섹션은 이름 있는 region 이 아니라 id 로 찾는다) */
function notificationSection() {
  const section = document.getElementById('me-notification')
  if (!section) throw new Error('알림 섹션이 없다')
  return section
}

/** 동의한 회원으로 로그인해 둔다 (목 세션) */
async function loginAsMember(email = 'dong@example.com') {
  await loginWithEmail(email, 'dongne2026', 'mock')
  await agreeHealthConsent(consentFor('SENSITIVE_HEALTH_INFO'))
}

function deferred() {
  let resolve: () => void = () => {}
  let reject: () => void = () => {}
  const promise = new Promise<void>((done, fail) => {
    resolve = done
    reject = () => fail(new Error('mock failure'))
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  search = ''
  resetMockSession()
  takeHomeNotice()
  // vi.fn 의 부른 기록을 지운다(감싼 원래 구현은 남는다). restoreAllMocks 는 spyOn 만 되돌린다
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/me')
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('MeScreen 회원 상태별 화면', () => {
  it('이메일 회원(Settings): 로그인 방법 · 비밀번호 변경 · 동의 철회 · 로그아웃 · 탈퇴', async () => {
    await loginAsMember('me@example.com')
    renderMe()

    expect(screen.getByRole('heading', { level: 2, name: '계정' })).toBeDefined()
    expect(screen.getByText('이메일 · me@example.com')).toBeDefined()
    // 행의 이름은 제목 · 값을 이어 읽는다
    expect(screen.getByRole('button', { name: /^닉네임/ }).textContent).toBe('닉네임동네지기')
    expect(screen.getByRole('link', { name: '비밀번호 변경' }).getAttribute('href')).toBe(
      '/me/password',
    )
    expect(screen.queryByRole('link', { name: /비밀번호 설정/ })).toBeNull()
    expect(screen.getByRole('link', { name: '로그인한 기기' }).getAttribute('href')).toBe(
      '/me/devices',
    )
    expect(screen.getByRole('heading', { level: 2, name: '내 보고' })).toBeDefined()
    expect(screen.getByRole('button', { name: /^최근 보고 내역/ }).textContent).toContain(
      '52주 보관',
    )
    expect(screen.getByText('건강정보 동의 철회').classList).toContain('text-danger')
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeDefined()
    expect(screen.getByText('회원 탈퇴').classList).toContain('text-fg-muted')
    // 비로그인 안내는 없다
    expect(screen.queryByText('로그인하면 보고할 수 있어요')).toBeNull()
  })

  it('카카오 회원(Settings-kakao): 비밀번호가 없어 비밀번호 행을 그리지 않는다 — 설정 행도 없다(#166)', () => {
    search = 'mock-auth=member&mock-provider=kakao'
    renderMe()

    expect(screen.getByText('카카오 · dong@kakao.com')).toBeDefined()
    expect(screen.queryByRole('link', { name: /비밀번호/ })).toBeNull()
    expect(screen.queryByText('이메일로도 로그인할 수 있어요')).toBeNull()
    expect(screen.getByRole('link', { name: '로그인한 기기' })).toBeDefined()
  })

  it('카카오로 가입한 목 세션도 비밀번호 행이 없다', async () => {
    await signup({ kind: 'kakao', consents: [consentFor('TERMS_OF_SERVICE')] }, 'mock')
    renderMe()
    expect(screen.queryByRole('link', { name: /비밀번호/ })).toBeNull()
  })

  it('?mock-auth= 덮어쓰기만 있으면 이메일 예시 프로필을 보인다', () => {
    search = 'mock-auth=member'
    renderMe()
    expect(screen.getByText('이메일 · dong@example.com')).toBeDefined()
  })

  it('동의 안 한 회원: 내 보고 · 동의 철회 대신 홈의 동의 시트로 가는 "건강정보 동의하기"', () => {
    search = 'mock-auth=member-no-consent'
    renderMe({ regionCode: '11440660' })

    expect(screen.queryByRole('heading', { level: 2, name: '내 보고' })).toBeNull()
    expect(screen.queryByText('건강정보 동의 철회')).toBeNull()
    const agree = screen.getByRole('link', { name: /건강정보 동의하기/ })
    expect(agree.getAttribute('href')).toBe('/?region=11440660&report=health-consent')
    // 로그아웃 · 탈퇴는 동의 여부와 무관하다
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeDefined()
    const menu = screen.getByRole('navigation', { name: '설정 메뉴', hidden: true })
    expect(menu.textContent).not.toContain('내 보고')
  })

  it.each([
    ['', '/login?next=%2Fme'],
    ['region=11440660', '/login?next=%2Fme&region=11440660'],
    // QA 덮어쓰기는 로그인에 넘기지 않는다 — ?mock-auth=guest 를 넘기면 로그인 뒤 돌아와 다시 튕긴다
    ['region=11440660&mock-auth=guest&mock-provider=email', '/login?next=%2Fme&region=11440660'],
  ])(
    '비회원(?%s)은 본문 없이 로그인으로 기록을 바꿔 가고, 로그인 뒤 /me 로 돌아온다',
    (base, expected) => {
      search = base
      renderMe()

      expect(router.replace.mock.calls).toEqual([[expected]])
      expect(router.push).not.toHaveBeenCalled()
      // Settings-guest 는 그리지 않는다. 머리줄 · 탭바(셸)만 남는다
      expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
      expect(screen.queryByRole('navigation', { name: '설정 메뉴', hidden: true })).toBeNull()
      expect(screen.queryByText('로그인하면 보고할 수 있어요')).toBeNull()
      // 비회원 머리줄에는 알림(종)이 없다
      expect(screen.queryByRole('button', { name: '알림 설정', hidden: true })).toBeNull()
    },
  )

  it.each([
    ['동의 전 회원', 'mock-auth=member-no-consent'],
    ['동의한 회원', 'mock-auth=member'],
  ])('%s은 로그인으로 보내지 않고 머리줄 알림(종)이 있다', (_, base) => {
    search = base
    renderMe()

    expect(router.replace).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '알림 설정' })).toBeDefined()
    expect(screen.getByRole('heading', { level: 2, name: '계정' })).toBeDefined()
  })

  it('로그인 만료로 비회원이 되면 만료 안내 로그인으로 보낸다', async () => {
    await loginAsMember()
    renderMe()
    expect(router.replace).not.toHaveBeenCalled()

    try {
      // 받는 쪽(루트의 만료 감시)이 세션을 비우는 것을 흉내 낸다
      act(() => {
        notifySessionExpired()
        resetMockSession()
      })
      expect(router.replace.mock.calls).toEqual([['/login?reason=expired']])
    } finally {
      clearSessionExpiring()
    }
  })

  it('데스크톱 설정 메뉴는 기록을 쌓지 않는 바로가기 버튼이다 — 섹션으로 옮기고 제목에 포커스를 준다', async () => {
    search = 'mock-auth=member'
    const scrollIntoView = vi.fn()
    // jsdom 에는 scrollIntoView 가 없다
    Element.prototype.scrollIntoView = scrollIntoView
    const pushState = vi.spyOn(window.history, 'pushState')
    const replaceState = vi.spyOn(window.history, 'replaceState')
    renderMe()

    const menu = screen.getByRole('navigation', { name: '설정 메뉴', hidden: true })
    // 같은 문서 # 링크는 Next 가 모르는 기록 항목을 쌓아 대화상자 닫기를 깨뜨린다 (docs/conventions.md)
    expect(menu.querySelectorAll('a')).toHaveLength(0)
    const items = [...menu.querySelectorAll('button')]
    expect(items.map((item) => item.textContent)).toEqual([
      '계정',
      '내 동네',
      '알림',
      '내 보고',
      '개인정보',
      '서비스 정보',
    ])

    const target = items.find((item) => item.textContent === '알림')
    if (!target) throw new Error('알림 바로가기가 없다')
    await userEvent.setup().click(target)
    expect(scrollIntoView.mock.contexts).toEqual([document.getElementById('me-notification')])
    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 2, name: '알림' }))
    expect(pushState).not.toHaveBeenCalled()
    expect(replaceState).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('')
  })

  it('화면 제목(h1)은 폭마다 하나다 — 모바일 · 태블릿은 머리줄, 데스크톱은 설정 메뉴 위', () => {
    search = 'mock-auth=member'
    renderMe()

    const [header, desktop] = screen.getAllByRole('heading', { level: 1, name: '내 정보' })
    expect(header?.closest('header')).not.toBeNull()
    expect(header?.classList).toContain('desktop:hidden')
    expect(desktop?.parentElement?.classList).toContain('hidden')
    expect(desktop?.parentElement?.classList).toContain('desktop:flex')
  })

  it('긴 이메일은 제목을 줄이지 않고 값만 한 줄로 자르며, 전체 값을 title 로 남긴다', async () => {
    const email = 'a.very.long.email.address.for.testing@example.com'
    expect(email.length).toBeGreaterThanOrEqual(32)
    await loginWithEmail(email, 'dongne2026', 'mock')
    renderMe()

    const value = screen.getByText(`이메일 · ${email}`)
    expect(value.classList).toContain('truncate')
    expect(value.getAttribute('title')).toBe(`이메일 · ${email}`)
    expect(value.parentElement?.classList).toContain('min-w-0')
    expect(value.parentElement?.classList).not.toContain('shrink-0')
    expect(screen.getByText('로그인 방법').parentElement?.classList).toContain('shrink-0')
  })
})

describe('MeScreen 메뉴', () => {
  it('?region= 을 탭바 링크와 회원 머리줄 보고 진입에 남긴다', async () => {
    search = 'mock-auth=member'
    renderMe({ regionCode: '11440660' })

    const tabBar = screen
      .getAllByRole('navigation', { name: '주요 메뉴', hidden: true })
      .find((nav) => nav.classList.contains('desktop:hidden'))
    expect([...(tabBar?.querySelectorAll('a') ?? [])].map((a) => a.getAttribute('href'))).toEqual([
      '/?region=11440660',
      '/map?region=11440660',
      '/me?region=11440660',
    ])
    expect(tabBar?.querySelector('[aria-current="page"]')?.textContent).toBe('내 정보')

    await userEvent.setup().click(screen.getByRole('button', { name: '이번 주 건강 보고하기' }))
    expect(router.push).toHaveBeenCalledWith('/?region=11440660&report=start')
  })

  it('내 보고의 첫 항목은 보내기 전이면 이번 주 보고하기이고 홈의 보고 진입으로 간다', () => {
    search = 'mock-auth=member'
    renderMe({ regionCode: '11440660' })

    const row = screen.getByRole('link', { name: '이번 주 보고하기' })
    expect(row.getAttribute('href')).toBe('/?region=11440660&report=start')
    expect(screen.queryByRole('link', { name: '이번 주 보고 수정' })).toBeNull()
    expect(screen.getAllByRole('button', { name: '이번 주 건강 보고하기' })).toHaveLength(1)
  })

  it('보낸 뒤면 이번 주 보고 수정(Settings 시안)이고 머리줄 보고 버튼은 완료 · 수정하기다', async () => {
    await loginAsMember()
    await submitReport({ kind: 'none' }, null, 'mock')
    renderMe()

    expect(screen.getByRole('link', { name: '이번 주 보고 수정' }).getAttribute('href')).toBe(
      '/?report=start',
    )
    expect(screen.queryByRole('link', { name: '이번 주 보고하기' })).toBeNull()
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: '이번 주 보고 완료 · 수정하기' }))
    expect(router.push).toHaveBeenCalledWith('/?report=start')

    // 보고를 되돌리면 같은 화면의 항목 · 버튼이 보내기 전으로 돌아간다
    await act(() => cancelReport('mock'))
    expect(screen.getByRole('link', { name: '이번 주 보고하기' })).toBeDefined()
    expect(screen.getByRole('button', { name: '이번 주 건강 보고하기' })).toBeDefined()
  })

  it('아직 없는 화면은 준비 중 알림을 띄운다', async () => {
    search = 'mock-auth=member'
    renderMe()

    await userEvent.setup().click(screen.getByRole('button', { name: /^닉네임/ }))
    expect(
      screen.getByText('닉네임 바꾸기 화면은 준비하고 있어요').closest('[role="status"]'),
    ).not.toBeNull()
  })

  it('로그인한 기기 · 비밀번호 행은 동네 · 덮어쓰기를 남긴 채 계정 화면으로 간다 (다른 쿼리는 뺀다)', () => {
    search = 'region=11440660&mock-auth=member&confirm=unknown'
    renderMe({ regionCode: '11440660' })
    expect(screen.getByRole('link', { name: '로그인한 기기' }).getAttribute('href')).toBe(
      '/me/devices?region=11440660&mock-auth=member',
    )
    expect(screen.getByRole('link', { name: '비밀번호 변경' }).getAttribute('href')).toBe(
      '/me/password?region=11440660&mock-auth=member',
    )
  })

  it('보고 동네 행은 둘러보는 동네가 아니라 내 동네를 보이고 내 동네 바꾸기로 간다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await saveRegion({ code: '11680640', name: '역삼1동' }, 'mock')
    search = 'region=11440660&mock-provider=email'
    renderMe({ regionCode: '11440660' })

    const row = screen.getByRole('link', { name: /^보고 동네/ })
    expect(row.textContent).toBe('보고 동네역삼1동')
    expect(row.getAttribute('href')).toBe('/me/region?region=11440660&mock-provider=email')
  })

  it('내 동네를 모르면(가입 없이 이메일 로그인) 보고 동네 값을 비운다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    renderMe()
    expect(screen.getByRole('link', { name: /^보고 동네/ }).textContent).toBe('보고 동네')
  })

  it('머리줄 동네 이름은 둘러보기 동네가 없으면 내 동네이고, 내 정보로 돌아올 둘러볼 동네 고르기를 연다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await saveRegion({ code: '11680640', name: '역삼1동' }, 'mock')
    renderMe()

    await userEvent.setup().click(screen.getByRole('button', { name: '동네 바꾸기, 현재 역삼1동' }))
    expect(router.push).toHaveBeenCalledWith('/browse/region?next=%2Fme')
  })

  it.each([
    ['password-changed', '비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요'],
    ['region-changed', '내 동네를 바꿨어요'],
  ] as const)(
    '계정 화면이 남긴 알림(%s)을 회원에게 한 번 띄우고 비운다 — 다시 그려도 뜨지 않는다',
    async (notice, message) => {
      await loginAsMember()
      const { rerender } = render(withTrail(<TrailProbe />))
      act(() => trail?.leaveNotice(notice))

      rerender(withTrail(<MeScreen regionName="○○동" />))
      expect(screen.getByText(message).closest('[role="status"]')).not.toBeNull()
      expect(trail?.takeNotice()).toBeNull()

      // 계정 화면에 갔다가 돌아와도(내 정보가 다시 마운트) 다시 뜨지 않는다
      rerender(withTrail(<TrailProbe />))
      rerender(withTrail(<MeScreen regionName="○○동" />))
      expect(screen.queryByText(message)).toBeNull()
    },
  )

  it('비회원에게는 남은 알림을 띄우지 않고 비우기만 한다', () => {
    const { rerender } = render(withTrail(<TrailProbe />))
    act(() => trail?.leaveNotice('password-changed'))
    rerender(withTrail(<MeScreen regionName="○○동" />))
    expect(screen.queryByText(/비밀번호를 바꿨어요/)).toBeNull()
    expect(trail?.takeNotice()).toBeNull()
  })

  it('알림 스위치는 구독하지 않는다 — 꺼진 채로 준비 중을 알린다', async () => {
    // jsdom 은 푸시 API 가 없어 미지원으로 보인다. 지원되는 기기를 덮어쓰기로 흉내 낸다
    search = 'mock-auth=member&mock-push=supported'
    renderMe()
    expect(screen.queryByText(/알림을 받을 수 없어요/)).toBeNull()

    const weekly = screen.getByRole('switch', { name: '주간 보고 요청' })
    expect(weekly.getAttribute('aria-checked')).toBe('false')
    expect(weekly.getAttribute('aria-disabled')).toBe('true')
    expect(
      document.getElementById(weekly.getAttribute('aria-describedby') ?? '')?.textContent,
    ).toBe('월요일 아침')
    await userEvent.setup().click(weekly)
    expect(weekly.getAttribute('aria-checked')).toBe('false')
    expect(screen.getByText('알림 설정 화면은 준비하고 있어요')).toBeDefined()
  })

  it('알림을 받을 수 없는 기기(Settings-nopush)는 알림 섹션에 안내를 두고, 스위치는 눌러도 아무 일이 없다', async () => {
    search = 'mock-auth=member&mock-push=needs-install&region=11440660'
    renderMe({ regionCode: '11440660' })

    const section = notificationSection()
    expect(within(section).getByText('이 기기에서는 알림을 받을 수 없어요')).toBeDefined()
    expect(
      within(section)
        .getByRole('link', { name: '홈 화면에 추가하는 방법 보기' })
        .getAttribute('href'),
    ).toBe('/install?region=11440660&mock-auth=member&mock-push=needs-install')

    const weekly = within(section).getByRole('switch', { name: '주간 보고 요청' })
    await userEvent.setup().click(weekly)
    expect(weekly.getAttribute('aria-checked')).toBe('false')
    expect(screen.queryByText('알림 설정 화면은 준비하고 있어요')).toBeNull()
  })

  it('푸시 API 가 없는 브라우저는 설치 안내 링크 없이 홈 상단 안내만 둔다', () => {
    search = 'mock-auth=member'
    renderMe()
    const section = notificationSection()
    expect(within(section).getByText('이 브라우저에서는 알림을 받을 수 없어요')).toBeDefined()
    expect(within(section).queryByRole('link')).toBeNull()
  })

  it('로그아웃 · 동의 철회 · 탈퇴 행은 다른 쿼리를 남긴 채 ?confirm= 을 기록에 쌓는다', async () => {
    search = 'mock-auth=member'
    const pushState = vi.spyOn(window.history, 'pushState')
    const user = userEvent.setup()
    renderMe()

    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await user.click(screen.getByRole('button', { name: /건강정보 동의 철회/ }))
    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
    expect(pushState.mock.calls.map((call) => call[2])).toEqual([
      '?mock-auth=member&confirm=logout',
      '?mock-auth=member&confirm=consent-withdraw',
      '?mock-auth=member&confirm=withdraw',
    ])
  })
})

describe('MeScreen 확인 대화상자', () => {
  it.each([
    [
      'logout',
      '로그아웃할까요?',
      ['이 기기에서만 로그아웃돼요.', '다시 보고하려면 로그인해야 해요.'],
    ],
    [
      'consent-withdraw',
      '건강정보 동의를 철회할까요?',
      [
        '지금까지 보낸 보고를 모두 지워요.',
        '다시 동의하기 전까지 보고할 수 없어요.',
        '모든 기기에서 로그아웃돼요.',
      ],
    ],
    ['withdraw', '회원 탈퇴할까요?', ['보낸 보고는 바로 지워요.', '계정은 30일 뒤 완전히 지워요.']],
  ])('?confirm=%s 는 시안 문구로 열린다', (kind, title, items) => {
    search = `mock-auth=member&confirm=${kind}`
    renderMe()

    const dialog = screen.getByRole('dialog', { name: title })
    expect([...dialog.querySelectorAll('li')].map((li) => li.textContent)).toEqual(items)
    expect(within(dialog).getByRole('button', { name: '취소' })).toBeDefined()
  })

  it('위험한 동작(동의 철회 · 탈퇴)은 빨강, 로그아웃은 네이비 버튼이다', () => {
    search = 'mock-auth=member&confirm=withdraw'
    renderMe()
    const dialog = screen.getByRole('dialog', { name: '회원 탈퇴할까요?' })
    expect(within(dialog).getByRole('button', { name: '탈퇴하기' }).classList).toContain(
      'bg-danger',
    )
  })

  it('취소하면 주소에서 confirm 만 지운다 (주소로 바로 들어와 쌓은 기록이 없음)', async () => {
    search = 'mock-auth=member&confirm=logout'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    renderMe()

    const dialog = screen.getByRole('dialog', { name: '로그아웃할까요?' })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '취소' }))
    expect(replaceState).toHaveBeenCalledWith({ sneezecastModalDepth: 0 }, '', '?mock-auth=member')
  })

  it('로그아웃에 성공하면 비회원이 되고 홈으로 기록을 바꿔 간다 (덮어쓰기는 빠지고 region 은 남는다)', async () => {
    await loginAsMember()
    search = 'region=11440660&mock-provider=email&confirm=logout'
    renderMe({ regionCode: '11440660' })

    const dialog = screen.getByRole('dialog', { name: '로그아웃할까요?' })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '로그아웃' }))

    expect(logout).toHaveBeenCalledWith('mock')
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
    // 세션이 먼저 비회원이 되어도 보내는 중에는 가드가 멈춰 로그인이 아니라 홈으로만 간다
    expect(router.replace.mock.calls).toEqual([['/?region=11440660']])
    expect(router.push).not.toHaveBeenCalled()
  })

  it('동의 철회에 성공하면 로그아웃되고 홈으로 기록을 바꿔 간다 (region 은 남고 알림을 남긴다)', async () => {
    await loginAsMember()
    await submitReport({ kind: 'none' }, null, 'mock')
    search = 'region=11440660&mock-provider=email&confirm=consent-withdraw'
    renderMe({ regionCode: '11440660' })

    const dialog = screen.getByRole('dialog', { name: '건강정보 동의를 철회할까요?' })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '동의 철회하기' }))

    expect(withdrawHealthConsent).toHaveBeenCalledTimes(1)
    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
    expect(getSubmittedReport()).toBeNull()
    // 세션이 먼저 바뀌어도(비회원은 이 대화상자를 열 수 없다) 이동할 때까지 대화상자를 닫지 않는다
    expect(screen.getByRole('dialog', { name: '건강정보 동의를 철회할까요?' })).toBeDefined()
    // 가드가 로그인으로 보내지 않고 홈으로만 간다
    expect(router.replace.mock.calls).toEqual([['/?region=11440660']])
    expect(router.push).not.toHaveBeenCalled()
    expect(takeHomeNotice()).toBe('건강정보 동의를 철회하고 로그아웃했어요')
  })

  it('?mock-auth= 덮어쓰기로 연 동의 철회는 비회원 세션을 회원으로 바꾸지 않고 홈으로 간다', async () => {
    search = 'mock-auth=member&confirm=consent-withdraw'
    renderMe()

    const dialog = screen.getByRole('dialog', { name: '건강정보 동의를 철회할까요?' })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '동의 철회하기' }))

    expect(getMockSession()).toBe('guest')
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('동의 철회하지 못하면 세션 · 보고를 그대로 두고 알림도 남기지 않는다', async () => {
    await loginAsMember('consent-withdraw-fail@example.com')
    const sent = await submitReport({ kind: 'none' }, null, 'mock')
    search = 'confirm=consent-withdraw'
    renderMe()

    const dialog = screen.getByRole('dialog', { name: '건강정보 동의를 철회할까요?' })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '동의 철회하기' }))

    expect(within(dialog).getByRole('alert').textContent).toBe(
      '동의를 철회하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(getMockSession()).toBe('member')
    expect(sent).toMatchObject({ status: 'ok', report: getSubmittedReport() })
    expect(router.replace).not.toHaveBeenCalled()
    expect(takeHomeNotice()).toBeNull()
  })

  it('탈퇴하지 못하면 대화상자 안에 알리고 다시 누를 수 있다', async () => {
    await loginAsMember('withdraw-fail@example.com')
    search = 'confirm=withdraw'
    renderMe()

    const dialog = screen.getByRole('dialog', { name: '회원 탈퇴할까요?' })
    const action = within(dialog).getByRole('button', { name: '탈퇴하기' })
    await userEvent.setup().click(action)

    expect(within(dialog).getByRole('alert').textContent).toBe(
      '탈퇴하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(action.getAttribute('aria-disabled')).toBeNull()
    expect(getMockSession()).toBe('member')
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('보내는 중에는 두 번 보내지 않고 취소 · 닫기를 막는다', async () => {
    const pending = deferred()
    vi.mocked(logout).mockImplementationOnce(() => pending.promise)
    search = 'mock-auth=member&confirm=logout'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    const user = userEvent.setup()
    renderMe()

    const dialog = screen.getByRole('dialog', { name: '로그아웃할까요?' })
    const action = within(dialog).getByRole('button', { name: '로그아웃' })
    await user.click(action)
    await user.click(action)
    expect(logout).toHaveBeenCalledTimes(1)
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
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('보내는 중에는 다른 확인 행이 꺼지고, 뒤로 가기로 닫은 뒤 실패하면 알림으로 알린다', async () => {
    const pending = deferred()
    vi.mocked(withdrawMembership).mockImplementationOnce(() => pending.promise)
    search = 'mock-auth=member&confirm=withdraw'
    const pushState = vi.spyOn(window.history, 'pushState')
    const user = userEvent.setup()
    const { rerender } = renderMe()

    const dialog = screen.getByRole('dialog', { name: '회원 탈퇴할까요?' })
    await user.click(within(dialog).getByRole('button', { name: '탈퇴하기' }))

    const logoutRow = screen.getByRole('button', { name: '로그아웃' })
    const consentRow = screen.getByRole('button', { name: /건강정보 동의 철회/ })
    expect(logoutRow.getAttribute('aria-disabled')).toBe('true')
    expect(consentRow.getAttribute('aria-disabled')).toBe('true')
    await user.click(logoutRow)
    await user.click(consentRow)
    expect(pushState).not.toHaveBeenCalled()

    // 휴대폰 뒤로 가기: 주소에서 confirm 이 빠져 대화상자가 닫힌다
    search = 'mock-auth=member'
    rerender(withTrail(<MeScreen regionName="○○동" />))
    expect(screen.queryByRole('dialog')).toBeNull()

    await act(async () => {
      pending.reject()
      await pending.promise.catch(() => {})
    })
    expect(
      screen.getByText('탈퇴하지 못했어요. 잠시 뒤 다시 시도해 주세요.').closest('[role="status"]'),
    ).not.toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(router.replace).not.toHaveBeenCalled()
    expect(
      screen.getByRole('button', { name: '로그아웃' }).getAttribute('aria-disabled'),
    ).toBeNull()
  })

  it('응답 전에 화면을 떠나면 늦은 응답으로 이동하지 않는다 (세션은 바뀐다)', async () => {
    const pending = deferred()
    // 응답만 늦춘다. 늦게 와도 목 API(두 번째 부름은 원래 구현)가 화면과 무관하게 세션을 바꾼다
    vi.mocked(withdrawHealthConsent).mockImplementationOnce(async () => {
      await pending.promise
      await withdrawHealthConsent()
    })
    await loginAsMember()
    search = 'confirm=consent-withdraw'
    const { unmount } = renderMe()

    const dialog = screen.getByRole('dialog', { name: '건강정보 동의를 철회할까요?' })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '동의 철회하기' }))
    unmount()
    await act(async () => {
      pending.resolve()
      await pending.promise
    })

    expect(getMockSession()).toBe('guest')
    expect(router.replace).not.toHaveBeenCalled()
    expect(takeHomeNotice()).toBeNull()
  })

  it('주소로 바로 들어온 미동의 회원의 consent-withdraw 는 열지 않고 주소에서 지운다', async () => {
    vi.useFakeTimers()
    search = 'mock-auth=member-no-consent&confirm=consent-withdraw'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    renderMe()

    expect(screen.queryByRole('dialog')).toBeNull()
    // 마운트 effect 에서 바로 바꾸지 않는다 (docs/conventions.md)
    expect(replaceState).not.toHaveBeenCalled()
    await act(() => vi.runAllTimersAsync())
    expect(replaceState).toHaveBeenCalledWith(
      { sneezecastModalDepth: 0 },
      '',
      '?mock-auth=member-no-consent',
    )
  })

  it.each([
    ['logout', '', '/login?next=%2Fme'],
    ['withdraw', 'region=11440660', '/login?next=%2Fme&region=11440660'],
  ])(
    '주소로 바로 들어온 비회원의 %s 는 열지 않고, 주소 정리 없이 로그인으로만 보낸다',
    async (kind, base, expected) => {
      vi.useFakeTimers()
      search = [base, `confirm=${kind}`].filter(Boolean).join('&')
      const replaceState = vi.spyOn(window.history, 'replaceState')
      renderMe()

      expect(screen.queryByRole('dialog')).toBeNull()
      await act(() => vi.runAllTimersAsync())
      // 원시 history 를 바꾸면 Next 가 대기 중인 로그인 이동을 버린다 (docs/conventions.md)
      expect(replaceState).not.toHaveBeenCalled()
      expect(router.replace.mock.calls).toEqual([[expected]])
    },
  )

  it('모르는 confirm 값은 아무 것도 열지 않고 그대로 둔다', async () => {
    vi.useFakeTimers()
    search = 'mock-auth=member&confirm=delete-all'
    const replaceState = vi.spyOn(window.history, 'replaceState')
    renderMe()

    await act(() => vi.runAllTimersAsync())
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(replaceState).not.toHaveBeenCalled()
  })
})

describe('MeScreen 다시 들어온 회원', () => {
  // 레이아웃 가드가 보낼 곳이 있으면 ?confirm= 불일치 정리(원시 history)를 하지 않는다 — Next 가 대기 중인 이동을 버린다
  it('보낼 곳이 있으면 맞지 않는 ?confirm= 을 주소에서 지우지 않는다', async () => {
    search = 'mock-auth=member-no-consent&mock-required=terms&confirm=consent-withdraw'
    window.history.replaceState(null, '', `/me?${search}`)
    const replaceState = vi.spyOn(window.history, 'replaceState')
    const goBack = vi.spyOn(window.history, 'go')
    renderMe()
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(replaceState).not.toHaveBeenCalled()
    expect(goBack).not.toHaveBeenCalled()
  })

  it('보낼 곳이 없으면 전처럼 맞지 않는 ?confirm= 을 지운다', async () => {
    search = 'mock-auth=member-no-consent&confirm=consent-withdraw'
    window.history.replaceState(null, '', `/me?${search}`)
    const replaceState = vi.spyOn(window.history, 'replaceState')
    renderMe()
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(replaceState).toHaveBeenCalledTimes(1)
  })
})

describe('MeScreen 실데이터 (회원 API, #164)', () => {
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
  const section = (id: string) => document.getElementById(id)?.textContent ?? ''

  it('읽는 동안은 "불러오고 있어요" 이고 예시 프로필을 보이지 않는다 — 받으면 내 정보 · 내 동네를 보인다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderMe()
    await flush()

    expect(screen.getByText('내 정보를 불러오고 있어요').closest('[role="status"]')).not.toBeNull()
    expect(section('me-account')).not.toContain('dong@example.com')
    expect(section('me-account')).not.toContain('동네지기')

    act(() => {
      server.reply(
        'GET /api/v1/members/me',
        okResponse(myInfoBody({ email: 'kakao@example.com', provider: 'KAKAO' })),
      )
      server.reply(
        'GET /api/v1/members/me/region',
        okResponse({
          code: '11680640',
          name: '역삼1동',
          sigungu: '서울특별시 강남구',
          abolished: false,
        }),
      )
    })
    await flush()
    expect(screen.queryByText('내 정보를 불러오고 있어요')).toBeNull()
    expect(section('me-account')).toContain('재채기탐정')
    expect(section('me-account')).toContain('카카오 · kakao@example.com')
    expect(section('me-region')).toContain('역삼1동')
  })

  it('내 동네만 읽지 못해도 빨강 상자로 알리고, 다시 시도하면 내 동네만 다시 읽는다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderMe()
    await flush()
    act(() => {
      server.reply('GET /api/v1/members/me', okResponse(myInfoBody()))
      server.reply('GET /api/v1/members/me/region', errorResponse('REGION_004', 503))
    })
    await flush()

    expect(screen.getByRole('alert').textContent).toContain(
      '내 정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    // 받은 내 정보는 그대로 보인다
    expect(section('me-account')).toContain('이메일 · me@example.com')

    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    expect(server.requests()).toEqual([
      'GET /api/v1/members/me',
      'GET /api/v1/members/me/region',
      'GET /api/v1/members/me/region',
    ])
    act(() => server.reply('GET /api/v1/members/me/region', okResponse(null)))
    await flush()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('비밀번호 행은 내 정보를 읽어 hasPassword 가 true 일 때만 보인다 — 읽는 동안 · false 면 없다(#166)', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderMe()
    await flush()
    expect(screen.queryByRole('link', { name: '비밀번호 변경' })).toBeNull()

    act(() => {
      server.reply(
        'GET /api/v1/members/me',
        okResponse(myInfoBody({ provider: 'KAKAO', hasPassword: false })),
      )
      server.reply('GET /api/v1/members/me/region', okResponse(null))
    })
    await flush()
    expect(section('me-account')).toContain('카카오 · me@example.com')
    expect(screen.queryByRole('link', { name: /비밀번호/ })).toBeNull()
  })

  it('비밀번호가 있는 회원(카카오를 연결한 이메일 계정 포함)은 비밀번호 변경 행을 본다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderMe()
    await flush()
    act(() => {
      server.reply(
        'GET /api/v1/members/me',
        okResponse(myInfoBody({ provider: 'KAKAO', hasPassword: true })),
      )
      server.reply('GET /api/v1/members/me/region', okResponse(null))
    })
    await flush()
    expect(screen.getByRole('link', { name: '비밀번호 변경' }).getAttribute('href')).toBe(
      '/me/password',
    )
  })
})
