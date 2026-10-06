// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'
import type * as notificationClient from '@/features/notification/notification-settings-client'
import {
  getNotificationSettings,
  type NotificationSettingsResult,
  resetMockNotificationSettings,
  updateNotificationSetting,
} from '@/features/notification/notification-settings-client'
import { setSession } from '@/lib/session/session-store'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import { holdRequests, memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

import { MeTrailProvider } from './me-trail'
import { NotificationsScreen } from './notifications-screen'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/me/notifications',
  useRouter: () => router,
}))

// 보내는 중을 붙잡아 보려고 바꾸기만 감싼다(기본은 실제 목)
vi.mock('@/features/notification/notification-settings-client', async (importOriginal) => {
  const actual = await importOriginal<typeof notificationClient>()
  return { ...actual, updateNotificationSetting: vi.fn(actual.updateNotificationSetting) }
})

/** 목 설정을 읽은 뒤(마이크로태스크)까지 그린다 */
async function renderScreen(regionCode?: string) {
  const view = render(
    <NavTrailProvider>
      <MeTrailProvider>
        <NotificationsScreen regionName="○○동" {...(regionCode ? { regionCode } : {})} />
      </MeTrailProvider>
    </NavTrailProvider>,
  )
  await act(async () => {})
  return view
}

const weekly = () => screen.getByRole('switch', { name: '주간 보고 요청' })
const regionNotice = () => screen.getByRole('switch', { name: '검토를 마친 동네 안내' })
const LEAD = '켠 알림만 보내요. 알림을 켜지 않아도 검토를 마친 동네 안내는 홈에서 볼 수 있어요.'

beforeEach(() => {
  // jsdom 은 푸시 API 가 없어 미지원으로 보인다. 지원되는 기기는 덮어쓰기로 흉내 낸다
  search = 'mock-auth=member&mock-push=supported'
  resetMockSession()
  resetMockNotificationSettings()
  vi.clearAllMocks()
})

describe('NotificationsScreen 알림 설정 (목)', () => {
  it('제목 · 안내 · 시안의 두 항목을 보이고, 처음은 모두 꺼짐이다. 머리줄 알림(종)은 없다', async () => {
    await renderScreen()

    expect(screen.getAllByRole('heading', { level: 1, name: '알림 설정' })).toHaveLength(2)
    expect(screen.getByText(LEAD)).toBeDefined()
    expect(screen.getAllByRole('switch').map((item) => item.getAttribute('aria-label'))).toEqual([
      '주간 보고 요청',
      '검토를 마친 동네 안내',
    ])
    expect(weekly().getAttribute('aria-checked')).toBe('false')
    expect(weekly().getAttribute('aria-disabled')).toBeNull()
    expect(
      document.getElementById(weekly().getAttribute('aria-describedby') ?? '')?.textContent,
    ).toBe('월요일 아침')
    expect(
      document.getElementById(regionNotice().getAttribute('aria-describedby') ?? '')?.textContent,
    ).toBe('운영자가 발행했을 때')
    expect(screen.queryByRole('button', { name: '알림 설정' })).toBeNull()
    expect(screen.queryByText(/아직 준비하고 있어요/)).toBeNull()
  })

  it('누르면 그 항목만 켜고 끈다 — 목 서버 값도 바뀐다', async () => {
    await renderScreen()
    const user = userEvent.setup()

    await user.click(weekly())
    expect(weekly().getAttribute('aria-checked')).toBe('true')
    expect(regionNotice().getAttribute('aria-checked')).toBe('false')
    expect(await getNotificationSettings('mock')).toEqual({
      status: 'ready',
      settings: { weeklyReport: true, regionNotice: false },
    })

    await user.click(weekly())
    expect(weekly().getAttribute('aria-checked')).toBe('false')
  })

  it('보내는 중에는 스위치를 모두 누를 수 없고(aria-disabled) 더 보내지 않는다', async () => {
    let finish: (result: NotificationSettingsResult) => void = () => {}
    vi.mocked(updateNotificationSetting).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await renderScreen()
    const user = userEvent.setup()

    await user.click(weekly())
    expect(weekly().getAttribute('aria-disabled')).toBe('true')
    expect(regionNotice().getAttribute('aria-disabled')).toBe('true')
    await user.click(regionNotice())
    expect(updateNotificationSetting).toHaveBeenCalledTimes(1)

    act(() => finish({ status: 'ready', settings: { weeklyReport: true, regionNotice: false } }))
    await act(async () => {})
    expect(weekly().getAttribute('aria-checked')).toBe('true')
    expect(weekly().getAttribute('aria-disabled')).toBeNull()
  })

  it('?mock-notifications=on 이면 모두 켜짐(시안 Settings 모양)이고, 누르면 그 항목만 끈다', async () => {
    search = 'mock-auth=member&mock-push=supported&mock-notifications=on'
    await renderScreen()
    expect(weekly().getAttribute('aria-checked')).toBe('true')
    expect(regionNotice().getAttribute('aria-checked')).toBe('true')

    await userEvent.setup().click(regionNotice())
    expect(updateNotificationSetting).toHaveBeenCalledWith('regionNotice', false, 'mock')
    expect(regionNotice().getAttribute('aria-checked')).toBe('false')
    expect(weekly().getAttribute('aria-checked')).toBe('true')
  })

  it('?mock-notifications=fail 이면 바꾸지 못해 빨강 상자로 알리고 값은 그대로다(다시 누를 수 있다)', async () => {
    search = 'mock-auth=member&mock-push=supported&mock-notifications=fail'
    await renderScreen()
    const user = userEvent.setup()

    await user.click(weekly())
    expect(screen.getByRole('alert').textContent).toBe(
      '알림 설정을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(weekly().getAttribute('aria-checked')).toBe('false')
    expect(weekly().getAttribute('aria-disabled')).toBeNull()

    await user.click(weekly())
    expect(updateNotificationSetting).toHaveBeenCalledTimes(2)
  })

  it('실패한 뒤 다시 눌러 성공하면 빨강 상자가 사라지고 값이 바뀐다', async () => {
    vi.mocked(updateNotificationSetting).mockImplementationOnce(() =>
      Promise.reject(new Error('mock: failed once')),
    )
    await renderScreen()
    const user = userEvent.setup()

    await user.click(weekly())
    expect(screen.getByRole('alert').textContent).toBe(
      '알림 설정을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    await user.click(weekly())
    expect(screen.queryByRole('alert')).toBeNull()
    expect(weekly().getAttribute('aria-checked')).toBe('true')
  })
})

describe('NotificationsScreen 알림을 받을 수 없는 기기', () => {
  it('홈 화면에 추가해야 하는 기기는 미지원 상자 · 설치 안내 링크를 두고, 저장된 값과 무관하게 꺼진 스위치는 눌러도 아무 일이 없다', async () => {
    search = 'region=11440660&mock-auth=member&mock-push=needs-install&mock-notifications=on'
    await renderScreen('11440660')

    expect(screen.getByText('이 기기에서는 알림을 받을 수 없어요')).toBeDefined()
    expect(
      screen.getByRole('link', { name: '홈 화면에 추가하는 방법 보기' }).getAttribute('href'),
    ).toBe('/install?region=11440660&mock-auth=member&mock-push=needs-install')
    expect(screen.queryByText(LEAD)).toBeNull()

    expect(weekly().getAttribute('aria-checked')).toBe('false')
    expect(weekly().getAttribute('aria-disabled')).toBe('true')
    await userEvent.setup().click(weekly())
    expect(updateNotificationSetting).not.toHaveBeenCalled()
    expect(weekly().getAttribute('aria-checked')).toBe('false')
  })

  it('푸시 API 가 없는 브라우저는 설치 안내 링크 없이 미지원 상자만 둔다', async () => {
    search = 'mock-auth=member'
    await renderScreen()
    expect(screen.getByText('이 브라우저에서는 알림을 받을 수 없어요')).toBeDefined()
    expect(screen.queryByRole('link', { name: '홈 화면에 추가하는 방법 보기' })).toBeNull()
    expect(regionNotice().getAttribute('aria-disabled')).toBe('true')
  })
})

describe('NotificationsScreen 가드 · 돌아가기', () => {
  it('비회원이 주소로 들어오면 로그인(돌아올 곳 /me/notifications)으로 기록을 바꿔 간다', async () => {
    search = ''
    const { container } = await renderScreen()
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fme%2Fnotifications')
  })

  it('주소로 바로 들어왔으면 뒤로가 내 정보로 기록을 바꿔 간다 (동네 · 회원 · 알림 덮어쓰기를 남기고 목 재현은 뺀다)', async () => {
    search = 'region=11440660&mock-auth=member&mock-push=supported&mock-notifications=on'
    await renderScreen('11440660')
    // 데스크톱 돌아가기는 여러 화면에서 들어와 `뒤로` 다
    expect(screen.getByRole('button', { name: '뒤로 가기' })).toBeDefined()
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith(
      '/me?region=11440660&mock-auth=member&mock-push=supported',
    )
  })

  it('동네 이름은 알림 설정을 돌아갈 곳으로 동네 고르기를 연다 (알림 덮어쓰기를 남긴다)', async () => {
    search = 'mock-auth=member&mock-push=needs-install'
    await renderScreen()
    await userEvent.setup().click(screen.getByRole('button', { name: '동네 바꾸기, 현재 ○○동' }))
    expect(router.push).toHaveBeenCalledWith(
      '/browse/region?next=%2Fme%2Fnotifications&mock-auth=member&mock-push=needs-install',
    )
  })
})

describe('NotificationsScreen 실데이터', () => {
  afterEach(() => {
    resetApiSession()
  })

  it('알림 설정 API 가 없어 요청하지 않고, 스위치는 꺼진 모양으로 두고 아직 준비하고 있다고 알린다', async () => {
    search = 'mock-push=needs-install&mock-notifications=on'
    selectApiSource()
    const server = holdRequests()
    act(() => setSession(memberToken()))
    await renderScreen()

    expect(
      screen.getByText(
        '알림은 아직 준비하고 있어요. 지금은 켜고 끌 수 없어요. 검토를 마친 동네 안내는 홈에서 볼 수 있어요.',
      ),
    ).toBeDefined()
    // 설치하면 켤 수 있다는 미지원 상자 · 고를 수 있다는 안내는 두지 않는다 — 위 상자와 다른 말을 하지 않게
    expect(screen.queryByText(/알림을 받을 수 없어요/)).toBeNull()
    expect(screen.queryByText(LEAD)).toBeNull()

    expect(weekly().getAttribute('aria-checked')).toBe('false')
    expect(weekly().getAttribute('aria-disabled')).toBe('true')
    await userEvent.setup().click(weekly())
    expect(updateNotificationSetting).not.toHaveBeenCalled()
    expect(server.requests().filter((request) => request.includes('notification'))).toEqual([])
  })

  it('알림을 받을 수 있는 기기여도 안내 문장 없이 스위치를 누를 수 없고 요청하지 않는다', async () => {
    search = 'mock-push=supported'
    selectApiSource()
    const server = holdRequests()
    act(() => setSession(memberToken()))
    await renderScreen()

    expect(screen.getByText(/알림은 아직 준비하고 있어요/)).toBeDefined()
    expect(screen.queryByText(LEAD)).toBeNull()
    for (const item of [weekly(), regionNotice()]) {
      expect(item.getAttribute('aria-checked')).toBe('false')
      expect(item.getAttribute('aria-disabled')).toBe('true')
    }
    await userEvent.setup().click(regionNotice())
    expect(updateNotificationSetting).not.toHaveBeenCalled()
    expect(server.requests().filter((request) => request.includes('notification'))).toEqual([])
  })
})
