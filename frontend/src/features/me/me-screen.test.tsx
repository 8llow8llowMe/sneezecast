// @vitest-environment jsdom
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
  withdrawHealthConsent,
  withdrawMembership,
} from '@/features/auth/auth-client'
import { consentFor } from '@/features/auth/legal'

import { MeScreen } from './me-screen'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
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

function renderMe(props: { regionCode?: string } = {}) {
  return render(<MeScreen regionName="○○동" {...props} />)
}

/** 동의한 회원으로 로그인해 둔다 (목 세션) */
async function loginAsMember(email = 'dong@example.com') {
  await loginWithEmail(email, 'dongne2026')
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
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeDefined()
    expect(screen.queryByRole('button', { name: /비밀번호 설정/ })).toBeNull()
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

  it('카카오 회원(Settings-kakao): 비밀번호 "설정" 과 이메일로도 로그인 안내', () => {
    search = 'mock-auth=member&mock-provider=kakao'
    renderMe()

    expect(screen.getByText('카카오 · dong@kakao.com')).toBeDefined()
    expect(screen.getByRole('button', { name: /^비밀번호 설정/ }).textContent).toBe(
      '비밀번호 설정이메일로도 로그인할 수 있어요',
    )
    expect(screen.queryByRole('button', { name: '비밀번호 변경' })).toBeNull()
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

  it('비로그인(Settings-guest): 둘러보는 동네 · 로그인 안내 · 개인정보 안내 · 서비스 정보만', async () => {
    renderMe()

    expect(screen.getByRole('link', { name: /^둘러보는 동네/ }).getAttribute('href')).toBe(
      '/browse/region',
    )
    expect(screen.getByRole('heading', { name: '로그인하면 보고할 수 있어요' })).toBeDefined()
    expect(
      screen.getByText('한 주에 한 번 10초면 돼요. 이름·연락처·주소는 받지 않아요.'),
    ).toBeDefined()
    expect(screen.getByRole('heading', { level: 2, name: '개인정보 안내' })).toBeDefined()
    expect(screen.queryByRole('heading', { level: 2, name: '계정' })).toBeNull()
    expect(screen.queryByRole('button', { name: '로그아웃' })).toBeNull()
    expect(screen.queryByRole('switch')).toBeNull()

    const menu = screen.getByRole('navigation', { name: '설정 메뉴', hidden: true })
    expect([...menu.querySelectorAll('button')].map((item) => item.textContent)).toEqual([
      '내 동네',
      '로그인',
      '개인정보 안내',
      '서비스 정보',
    ])

    // 안내 상자 버튼과 머리줄(태블릿 · 데스크톱) 버튼이 같은 글자다 — 둘 다 로그인으로 간다
    const user = userEvent.setup()
    for (const button of screen.getAllByRole('button', { name: '로그인하고 보고하기' })) {
      await user.click(button)
    }
    expect(router.push.mock.calls).toEqual([['/login'], ['/login']])
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
    await loginWithEmail(email, 'dongne2026')
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

  it('아직 없는 화면은 준비 중 알림을 띄운다', async () => {
    search = 'mock-auth=member'
    renderMe()

    await userEvent.setup().click(screen.getByRole('button', { name: '로그인한 기기' }))
    expect(
      screen.getByText('로그인한 기기 화면은 준비하고 있어요').closest('[role="status"]'),
    ).not.toBeNull()
  })

  it('알림 스위치는 구독하지 않는다 — 꺼진 채로 준비 중을 알린다', async () => {
    search = 'mock-auth=member'
    renderMe()

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

    expect(getMockSession()).toBe('guest')
    expect(getMockProfile()).toBeNull()
    expect(router.replace).toHaveBeenCalledWith('/?region=11440660')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('동의 철회에 성공하면 미동의 회원이 되고 홈으로 간다', async () => {
    await loginAsMember()
    search = 'confirm=consent-withdraw'
    renderMe()

    const dialog = screen.getByRole('dialog', { name: '건강정보 동의를 철회할까요?' })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '동의 철회하기' }))

    expect(withdrawHealthConsent).toHaveBeenCalledTimes(1)
    expect(getMockSession()).toBe('member-no-consent')
    // 세션이 먼저 바뀌어(미동의 회원은 이 대화상자를 열 수 없다) 이동할 때까지 대화상자를 닫지 않는다
    expect(screen.getByRole('dialog', { name: '건강정보 동의를 철회할까요?' })).toBeDefined()
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('?mock-auth= 덮어쓰기로 연 동의 철회는 비회원 세션을 회원으로 바꾸지 않고 홈으로 간다', async () => {
    search = 'mock-auth=member&confirm=consent-withdraw'
    renderMe()

    const dialog = screen.getByRole('dialog', { name: '건강정보 동의를 철회할까요?' })
    await userEvent.setup().click(within(dialog).getByRole('button', { name: '동의 철회하기' }))

    expect(getMockSession()).toBe('guest')
    expect(router.replace).toHaveBeenCalledWith('/')
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
    rerender(<MeScreen regionName="○○동" />)
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

    expect(getMockSession()).toBe('member-no-consent')
    expect(router.replace).not.toHaveBeenCalled()
  })

  it.each([
    ['비회원의 logout', '', 'logout', ''],
    ['비회원의 withdraw', 'region=11440660', 'withdraw', '?region=11440660'],
    [
      '미동의 회원의 consent-withdraw',
      'mock-auth=member-no-consent',
      'consent-withdraw',
      '?mock-auth=member-no-consent',
    ],
  ])('주소로 바로 들어온 %s 는 열지 않고 주소에서 지운다', async (_, base, kind, expected) => {
    vi.useFakeTimers()
    search = [base, `confirm=${kind}`].filter(Boolean).join('&')
    const replaceState = vi.spyOn(window.history, 'replaceState')
    renderMe()

    expect(screen.queryByRole('dialog')).toBeNull()
    // 마운트 effect 에서 바로 바꾸지 않는다 (docs/conventions.md)
    expect(replaceState).not.toHaveBeenCalled()
    await act(() => vi.runAllTimersAsync())
    expect(replaceState).toHaveBeenCalledWith(
      { sneezecastModalDepth: 0 },
      '',
      expected || window.location.pathname,
    )
  })

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
