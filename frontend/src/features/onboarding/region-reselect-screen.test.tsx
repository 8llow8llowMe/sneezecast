// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from '@/features/auth/auth-client'
import {
  getMockProfile,
  loginWithEmail,
  resetMockSession,
  saveRegion,
} from '@/features/auth/auth-client'
import { SessionExpiryWatcher } from '@/features/auth/session-expiry-watcher'
import type * as regionClient from '@/features/region/region-client'
import { listSuccessorDistricts, searchDistricts } from '@/features/region/region-client'
import { clearSessionExpiring, notifySessionExpired } from '@/lib/session-expiry'

import { OnboardingProvider } from './onboarding-context'
import { RegionReselectScreen } from './region-reselect-screen'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/setup/region',
  useRouter: () => router,
}))

vi.mock('@/features/auth/auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, saveRegion: vi.fn(actual.saveRegion) }
})

vi.mock('@/features/region/region-client', async (importOriginal) => {
  const actual = await importOriginal<typeof regionClient>()
  return {
    ...actual,
    searchDistricts: vi.fn(actual.searchDistricts),
    listSuccessorDistricts: vi.fn(actual.listSuccessorDistricts),
  }
})

const ui = (
  <OnboardingProvider>
    <RegionReselectScreen />
  </OnboardingProvider>
)

const saveButton = () => screen.getByRole('button', { name: '이 동네로 바꾸기' })
const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'
const optionTexts = () =>
  screen.queryAllByRole('radio').map((option) => option.closest('label')?.textContent)

beforeEach(async () => {
  search = ''
  resetMockSession()
  router.replace.mockClear()
  router.push.mockClear()
  vi.mocked(saveRegion).mockReset()
  vi.mocked(searchDistricts).mockReset()
  vi.mocked(listSuccessorDistricts).mockReset()
  await loginWithEmail('reselect@example.com', 'dongne2026')
})

describe('RegionReselectScreen 그림', () => {
  it('옛 동네 이름을 넣어 알리고 이어 받은 동네 후보를 보인다 (단계 표시 · 뒤로 없음)', async () => {
    render(ui)
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('동네를 다시 골라 주세요')
    expect(screen.getByText(/고르셨던 ○○1동이 행정구역 개편으로 바뀌었어요\./).textContent).toBe(
      '고르셨던 ○○1동이 행정구역 개편으로 바뀌었어요. 지금 사는 행정동을 다시 골라 주세요.',
    )

    await screen.findByRole('group', { name: '다시 고를 동네 후보' })
    expect(optionTexts()).toEqual([
      '○○새1동○○시 ○○구 · 옛 ○○1동 일부',
      '○○새2동○○시 ○○구 · 옛 ○○1동 일부',
      '○○2동○○시 ○○구',
    ])
    expect(listSuccessorDistricts).toHaveBeenCalledWith('99990110')
    expect(screen.queryByRole('button', { name: '뒤로' })).toBeNull()
    expect(screen.queryByText(/\d \/ 4/)).toBeNull()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('후보를 불러오는 동안 안내하고, 불러오지 못하면 검색으로 고르라고 알린다', async () => {
    let fail: () => void = () => {}
    vi.mocked(listSuccessorDistricts).mockImplementationOnce(
      () => new Promise((_, reject) => (fail = () => reject(new Error('network')))),
    )
    render(ui)
    expect(
      screen.getByText('후보 동네를 불러오고 있어요').closest('[role="status"]'),
    ).not.toBeNull()

    await act(async () => {
      fail()
      await Promise.resolve()
    })
    expect(screen.getByText('후보 동네를 불러오지 못했어요')).toBeDefined()
    expect(screen.getByText('동 이름으로 검색해 주세요.')).toBeDefined()
    expect(screen.queryAllByRole('radio')).toHaveLength(0)
  })

  it('후보가 없으면 동 이름으로 검색하라고 알린다', async () => {
    vi.mocked(listSuccessorDistricts).mockResolvedValueOnce([])
    render(ui)
    expect(await screen.findByText('후보 동네가 없어요')).toBeDefined()
    expect(screen.getByText('동 이름으로 검색해 주세요.').closest('[role="status"]')).not.toBeNull()
    expect(screen.queryAllByRole('radio')).toHaveLength(0)
  })

  it('검색어를 넣으면 후보 대신 검색 결과를 보이고, 고른 것을 지운다', async () => {
    const user = userEvent.setup()
    render(ui)
    await user.click(await screen.findByRole('radio', { name: /○○새1동/ }))
    expect(isOff(saveButton())).toBe(false)

    await user.type(screen.getByRole('searchbox', { name: '행정동 이름' }), '역삼')
    await screen.findByRole('group', { name: '검색 결과' })
    expect(optionTexts()).toEqual(['역삼1동서울특별시 강남구', '역삼2동서울특별시 강남구'])
    expect(isOff(saveButton())).toBe(true)

    // 검색어를 지우면 다시 후보다
    await user.clear(screen.getByRole('searchbox', { name: '행정동 이름' }))
    expect(await screen.findByRole('group', { name: '다시 고를 동네 후보' })).toBeDefined()
  })

  it('맞는 동이 없으면 결과 없음 안내를 보인다', async () => {
    const user = userEvent.setup()
    render(ui)
    await user.type(screen.getByRole('searchbox', { name: '행정동 이름' }), '없는동이름')
    expect(await screen.findByText('‘없는동이름’과 맞는 행정동이 없어요')).toBeDefined()
  })

  it('위치 권한을 요청하지 않는다', async () => {
    const getCurrentPosition = vi.fn()
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition, watchPosition: getCurrentPosition },
    })
    render(ui)
    await screen.findByRole('group', { name: '다시 고를 동네 후보' })
    expect(getCurrentPosition).not.toHaveBeenCalled()
  })
})

describe('RegionReselectScreen 저장', () => {
  it('고르기 전에는 버튼이 꺼져 있고 눌러도 저장하지 않는다', async () => {
    const user = userEvent.setup()
    render(ui)
    await screen.findByRole('group', { name: '다시 고를 동네 후보' })
    expect(isOff(saveButton())).toBe(true)
    await user.click(saveButton())
    expect(saveRegion).not.toHaveBeenCalled()
  })

  it('고른 동네를 저장하면 프로필의 동네가 바뀌고 next 로 기록을 바꿔 간다', async () => {
    search = 'next=/me&region=11440660&report=start'
    const user = userEvent.setup()
    render(ui)
    await user.click(await screen.findByRole('radio', { name: /○○새2동/ }))
    await user.click(saveButton())

    expect(saveRegion).toHaveBeenCalledWith(
      expect.objectContaining({ code: '99990112', name: '○○새2동' }),
    )
    expect(getMockProfile()).toMatchObject({
      region: { code: '99990112', name: '○○새2동' },
      regionAbolished: false,
    })
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660')
    // 첫 진입 Provider 의 가입 단계로 가지 않는다
    expect(router.push).not.toHaveBeenCalled()
  })

  it('검색으로 고른 동네도 저장한다 (덮어쓰기에서 마친 조건을 빼고 홈으로)', async () => {
    resetMockSession()
    search = 'mock-auth=member&mock-required=region'
    const user = userEvent.setup()
    render(ui)
    await user.type(screen.getByRole('searchbox', { name: '행정동 이름' }), '서교')
    await user.click(await screen.findByRole('radio', { name: /서교동/ }))
    await user.click(saveButton())

    expect(saveRegion).toHaveBeenCalledWith(expect.objectContaining({ code: '11440660' }))
    expect(router.replace).toHaveBeenCalledWith('/?mock-auth=member')
  })

  it('보내는 동안 버튼이 꺼지고 다시 눌러도 한 번만 보낸다', async () => {
    let finish: () => void = () => {}
    vi.mocked(saveRegion).mockImplementationOnce(
      () => new Promise<void>((resolve) => (finish = resolve)),
    )
    const user = userEvent.setup()
    render(ui)
    await user.click(await screen.findByRole('radio', { name: /○○새1동/ }))
    await user.click(saveButton())
    expect(isOff(saveButton())).toBe(true)
    await user.click(saveButton())
    expect(saveRegion).toHaveBeenCalledTimes(1)
    // 보내는 중에는 검색 칸이 읽기 전용이다
    expect(screen.getByRole<HTMLInputElement>('searchbox', { name: '행정동 이름' }).readOnly).toBe(
      true,
    )

    await act(async () => {
      finish()
      await Promise.resolve()
    })
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('저장하지 못하면 빨강 상자로 알리고 다시 누를 수 있다 (재현 이메일)', async () => {
    resetMockSession()
    await loginWithEmail('reselect-fail@example.com', 'dongne2026')
    const user = userEvent.setup()
    render(ui)
    await user.click(await screen.findByRole('radio', { name: /○○새1동/ }))
    await user.click(saveButton())

    expect((await screen.findByRole('alert')).textContent).toContain(
      '동네를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(router.replace).not.toHaveBeenCalled()
    expect(getMockProfile()?.regionAbolished).toBe(true)
    expect(isOff(saveButton())).toBe(false)
    await user.click(saveButton())
    await waitFor(() => expect(saveRegion).toHaveBeenCalledTimes(2))
  })

  it('응답 전에 화면을 떠나면 늦은 응답으로 이동하지 않는다', async () => {
    let finish: () => void = () => {}
    vi.mocked(saveRegion).mockImplementationOnce(
      () => new Promise<void>((resolve) => (finish = resolve)),
    )
    const user = userEvent.setup()
    const { unmount } = render(ui)
    await user.click(await screen.findByRole('radio', { name: /○○새1동/ }))
    await user.click(saveButton())
    unmount()
    await act(async () => {
      finish()
      await Promise.resolve()
    })
    expect(router.replace).not.toHaveBeenCalled()
  })
})

describe('RegionReselectScreen 들어올 수 없을 때', () => {
  it('비회원이면 그리지 않고 next 로 보낸다', () => {
    resetMockSession()
    search = 'reselect=1&next=/me/password'
    const { container } = render(ui)
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/me/password')
  })

  it('동네 조건이 없는 회원은 next 로, 목록 밖 next 는 홈으로 보낸다', async () => {
    resetMockSession()
    await loginWithEmail('dong@example.com', 'dongne2026')
    search = 'reselect=1&next=//evil.example'
    render(ui)
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('약관 재동의가 남았으면 재동의로 먼저 보낸다', () => {
    resetMockSession()
    search = 'reselect=1&next=/me&mock-auth=member&mock-required=terms,region'
    render(ui)
    expect(router.replace).toHaveBeenCalledWith(
      '/terms/reconsent?next=%2Fme&mock-auth=member&mock-required=terms%2Cregion',
    )
  })

  it('하이드레이션 첫 그림(비회원)으로 판단하지 않는다 — 회원이면 하이드레이션 뒤 화면을 그린다', async () => {
    const container = document.createElement('div')
    document.body.append(container)
    container.innerHTML = renderToString(ui)
    expect(container.textContent).toBe('')

    render(ui, { container, hydrate: true })
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('동네를 다시 골라 주세요')
    await screen.findByRole('group', { name: '다시 고를 동네 후보' })
    expect(router.replace).not.toHaveBeenCalled()
  })
})

describe('RegionReselectScreen 로그인 만료', () => {
  it('다시 고르기 화면에서 만료되면 홈으로 덮어쓰지 않고 만료 토스트가 있는 로그인 화면으로만 간다', async () => {
    vi.mocked(listSuccessorDistricts).mockResolvedValue([])
    clearSessionExpiring()
    render(
      <OnboardingProvider>
        <RegionReselectScreen />
        <SessionExpiryWatcher />
      </OnboardingProvider>,
    )
    await screen.findByText(/후보 동네가 없어요/)
    expect(router.replace).not.toHaveBeenCalled()

    // 세션이 비회원이 되어 조건이 사라지면 화면은 다음 곳(홈)으로 보내려 한다 — 만료 중에는 보내지 않아야 한다
    act(() => notifySessionExpired())

    expect(router.replace.mock.calls).toEqual([['/login?reason=expired']])
    clearSessionExpiring()
  })
})
