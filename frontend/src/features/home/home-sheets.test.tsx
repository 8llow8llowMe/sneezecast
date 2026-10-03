// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'

import { HomeScreen } from './home-screen'
import { explainSheet, healthConsentSheet, loginSheet, reportFlow } from './home-sheets'
import { HOME_MOCKS } from './mock'

// 주소는 jsdom 의 지금 주소를 읽는다(시트를 연 pushState 가 바로 보인다)
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}))

/** 유휴 시간 콜백을 붙잡아 테스트가 정한 때에 부른다 */
let idleCallbacks: IdleRequestCallback[] = []
const requestIdle = vi.fn((callback: IdleRequestCallback) => {
  idleCallbacks.push(callback)
  return idleCallbacks.length
})

function runIdle() {
  const pending = idleCallbacks
  idleCallbacks = []
  pending.forEach((callback) => callback({ didTimeout: false, timeRemaining: () => 50 }))
}

function openDialog(title: string) {
  return [...document.querySelectorAll('dialog[open]')].find((dialog) =>
    dialog.querySelector('h2')?.textContent?.includes(title),
  )
}

/*
 * 모듈에 남는 "받은 시트" 를 보는 테스트라 순서대로 돈다(이 파일은 받은 것이 없는 상태로 시작한다).
 */
describe('홈 시트 미리 받기 (#184)', () => {
  beforeEach(() => {
    resetMockSession()
    idleCallbacks = []
    requestIdle.mockClear()
    vi.stubGlobal('requestIdleCallback', requestIdle)
    vi.stubGlobal('cancelIdleCallback', vi.fn())
  })

  afterEach(() => {
    // 홈을 내린 뒤(유휴 콜백 취소) 전역을 되돌린다
    cleanup()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    window.history.replaceState(null, '', '/')
  })

  it('동의 시트를 여는 동안 보고 흐름을 미리 받는다 — 유휴 시간을 기다리지 않는다', async () => {
    window.history.replaceState(null, '', '/?mock-auth=member-no-consent&report=health-consent')
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await waitFor(() => expect(openDialog('증상 보고에 동의해 주세요')).toBeDefined())
    await waitFor(() => expect(reportFlow.peek()).not.toBeNull())
    // 유휴 시간 전이라 나머지는 받지 않았다
    expect(loginSheet.peek()).toBeNull()
    expect(explainSheet.peek()).toBeNull()
  })

  it('자료 부족이면 ?explain=1 로 들어와도 판단 기준을 받지 않는다(그리지 않으므로)', async () => {
    window.history.replaceState(null, '', '/?explain=1')
    render(<HomeScreen week={HOME_MOCKS.insufficient} />)

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(explainSheet.peek()).toBeNull()
  })

  it('하이드레이션 뒤 유휴 시간에 시트 넷을 미리 받는다', async () => {
    render(<HomeScreen week={HOME_MOCKS.high} />)

    expect(requestIdle).toHaveBeenCalledWith(expect.any(Function), {
      timeout: 5000,
    })
    expect(loginSheet.peek()).toBeNull()
    runIdle()
    await waitFor(() => {
      expect(loginSheet.peek()).not.toBeNull()
      expect(healthConsentSheet.peek()).not.toBeNull()
      expect(reportFlow.peek()).not.toBeNull()
      expect(explainSheet.peek()).not.toBeNull()
    })
  })
})

// 가드는 로드 상태가 아니라 누른 순간의 주소를 본다 — 받는 중이든 받은 뒤든 같은 경로다(받는 중 재현은 브라우저 확인 기록, performance.md "#184 전후")
describe('시트 쿼리가 있을 때 다시 누르기', () => {
  beforeEach(() => {
    resetMockSession()
    window.history.replaceState(null, '', '/?mock=high')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    window.history.replaceState(null, '', '/')
  })

  it('같은 보고 버튼을 두 번 눌러도 기록을 한 번만 쌓는다', async () => {
    const pushState = vi.spyOn(window.history, 'pushState')
    const user = userEvent.setup()
    render(<HomeScreen week={HOME_MOCKS.high} />)

    const [button] = screen.getAllByRole('button', { name: '로그인하고 보고하기' })
    await user.click(button!)
    await user.click(button!)

    expect(pushState).toHaveBeenCalledTimes(1)
    expect(pushState).toHaveBeenCalledWith(
      { sneezecastModalDepth: 1 },
      '',
      '?mock=high&report=login',
    )
  })

  it('보고 버튼 뒤에 판단 기준을 눌러도 두 번째 시트를 열지 않는다', async () => {
    const pushState = vi.spyOn(window.history, 'pushState')
    const user = userEvent.setup()
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await user.click(screen.getAllByRole('button', { name: '로그인하고 보고하기' })[0]!)
    await user.click(screen.getByRole('button', { name: '왜 이렇게 보나요?' }))

    expect(pushState).toHaveBeenCalledTimes(1)
    expect(window.location.search).toBe('?mock=high&report=login')
  })

  it('판단 기준을 연 뒤에 보고 버튼을 눌러도 열지 않는다', async () => {
    const pushState = vi.spyOn(window.history, 'pushState')
    const user = userEvent.setup()
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await user.click(screen.getByRole('button', { name: '왜 이렇게 보나요?' }))
    await user.click(screen.getAllByRole('button', { name: '로그인하고 보고하기' })[0]!)

    expect(pushState).toHaveBeenCalledTimes(1)
    expect(window.location.search).toBe('?mock=high&explain=1')
  })

  it('자료 부족이면 ?explain=1 이 남아 있어도 열린 시트로 보지 않는다 — 보고 버튼이 연다', async () => {
    window.history.replaceState(null, '', '/?explain=1')
    const pushState = vi.spyOn(window.history, 'pushState')
    render(<HomeScreen week={HOME_MOCKS.insufficient} />)

    await userEvent
      .setup()
      .click(screen.getAllByRole('button', { name: '로그인하고 보고하기' })[0]!)
    expect(pushState).toHaveBeenCalledTimes(1)
    expect(pushState).toHaveBeenCalledWith(
      { sneezecastModalDepth: 1 },
      '',
      '?explain=1&report=login',
    )
  })

  it('모르는 ?report= 값은 열린 시트로 보지 않는다 — 보고 버튼이 연다', async () => {
    window.history.replaceState(null, '', '/?report=unknown')
    const pushState = vi.spyOn(window.history, 'pushState')
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await userEvent
      .setup()
      .click(screen.getAllByRole('button', { name: '로그인하고 보고하기' })[0]!)
    expect(pushState).toHaveBeenCalledWith({ sneezecastModalDepth: 1 }, '', '?report=login')
  })
})
