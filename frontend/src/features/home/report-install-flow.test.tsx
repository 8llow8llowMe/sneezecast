// @vitest-environment jsdom
import type { ReactNode } from 'react'

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'
import { InstallScreen } from '@/features/install/install-screen'
import { cancelReport } from '@/features/report/report-client'
import { NavTrailProvider } from '@/lib/use-nav-trail'

import { HomeScreen } from './home-screen'
import { HOME_MOCKS } from './mock'

// 테스트에는 Next 라우터가 없다. 주소는 jsdom 의 지금 주소를 읽고, 화면 사이 이동은 아래에서 손으로 흉내 낸다
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
  useRouter: () => router,
  usePathname: () => window.location.pathname,
}))

/** 루트 레이아웃처럼 앱 안 이동 기록으로 감싼다. 화면을 바꿔 그려도 기록은 이어진다 */
function app(child: ReactNode) {
  return <NavTrailProvider>{child}</NavTrailProvider>
}

/** 열린 대화상자의 제목 (홈에는 보고 · 판단 기준 · 로그인 · 동의 대화상자가 있다) */
function openDialogTitle() {
  return document.querySelector('dialog[open] h2')?.textContent ?? null
}

const QA = 'mock-auth=member&mock-push=needs-install'

/**
 * S06 보고 완료 → S12 홈 화면 추가 안내 → 닫기 → 보고 완료 유지 (#110).
 * 홈과 설치 안내는 다른 라우트라 설치 안내로 가면 홈이 사라지고, 닫아서 돌아오면 홈이 새로 그려진다.
 * 보낸 보고가 홈 상태에 있으면 이때 사라져 `?report=done` 이 시작 단계로 풀렸다.
 */
describe('보고 완료 → 홈 화면 추가 안내 → 닫기', () => {
  beforeEach(async () => {
    resetMockSession()
    await cancelReport('mock')
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('설치 안내를 닫고 돌아오면 보고 완료 화면이 그대로다', async () => {
    const user = userEvent.setup()
    // 홈에서 보고 버튼으로 연 보고 흐름(기록 한 칸)
    window.history.replaceState({ sneezecastModalDepth: 1 }, '', `/?report=start&${QA}`)
    const view = render(app(<HomeScreen week={HOME_MOCKS.normal} />))

    // 1) 증상 없음으로 보내면 완료 단계(기록 없이 바꿈)
    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    view.rerender(app(<HomeScreen week={HOME_MOCKS.normal} />))
    expect(window.location.search).toBe(`?report=done&${QA}`)
    expect(openDialogTitle()).toBe('이번 주 보고를 받았어요')

    // 2) 완료 화면의 설치 안내 행을 누른다. jsdom 은 링크 이동을 하지 않아 누름 처리 뒤 이동을 손으로 흉내 낸다
    const link = screen.getByRole('link', { name: /다음 주 월요일에 알려드릴까요/ })
    expect(link.getAttribute('href')).toBe(`/install?${QA}`)
    link.addEventListener('click', (event) => event.preventDefault())
    await user.click(link)
    window.history.pushState(null, '', `/install?${QA}`)
    view.rerender(app(<InstallScreen />))

    // 3) 앱 안 이동으로 왔으므로(루트 이동 기록에 홈이 앞에 있다) 닫기는 기록을 되돌린다(홈으로 replace 하지 않는다)
    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()

    // 4) 되돌아온 완료 주소에서 홈이 새로 그려져도 보낸 보고가 남아 완료 단계다
    window.history.back()
    await vi.waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(window.location.search).toBe(`?report=done&${QA}`)
    view.rerender(app(<HomeScreen week={HOME_MOCKS.normal} />))
    expect(openDialogTitle()).toBe('이번 주 보고를 받았어요')
    expect(screen.getByRole('link', { name: /다음 주 월요일에 알려드릴까요/ })).toBeDefined()

    // 5) 변화 보기로 닫으면 보고 흐름이 쌓은 한 칸만 되돌린다 — 되돌아온 기록 항목도 깊이를 들고 있다
    const go = vi.spyOn(window.history, 'go').mockImplementation(() => {})
    await user.click(screen.getByRole('button', { name: '우리 동네 변화 보기' }))
    expect(go).toHaveBeenCalledWith(-1)
  })

  it('돌아온 완료 화면에서 보고 수정하기를 누르면 이미 보낸 보고의 수정 안내가 보인다', async () => {
    const user = userEvent.setup()
    window.history.replaceState({ sneezecastModalDepth: 1 }, '', `/?report=start&${QA}`)
    const home = render(<HomeScreen week={HOME_MOCKS.normal} />)
    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    home.unmount()

    // 홈을 떠났다 돌아와 새로 그린 뒤
    const back = render(<HomeScreen week={HOME_MOCKS.normal} />)
    await user.click(screen.getByRole('button', { name: '보고 수정하기' }))
    back.rerender(<HomeScreen week={HOME_MOCKS.normal} />)
    expect(window.location.search).toBe(`?report=start&${QA}`)
    expect(
      screen.getByText(/에 보고했어요\. 수정하면 집계에는 마지막 보고만 반영돼요\./),
    ).toBeDefined()
  })
})
