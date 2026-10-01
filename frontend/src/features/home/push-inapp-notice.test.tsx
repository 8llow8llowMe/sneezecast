// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'

import { HomeScreen } from './home-screen'
import { HOME_MOCKS } from './mock'
import { PushInappNotice } from './push-inapp-notice'

let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  useRouter: () => router,
}))

const NOTICE = '○○동 이번 주 안내가 발행됐어요 · 알림 대신 여기서 알려드려요'

beforeEach(() => {
  search = ''
  resetMockSession()
  window.history.replaceState(null, '', '/')
})

describe('PushInappNotice', () => {
  it.each(['needs-install', 'unsupported'])(
    '회원 · 발행된 안내 · 푸시 %s 이면 회색 안내를 띄운다',
    (push) => {
      search = `mock-push=${push}`
      render(<PushInappNotice week={HOME_MOCKS.high} signedIn />)
      const callout = screen.getByText(NOTICE).closest('[role="status"]')
      expect(callout?.classList).toContain('bg-section')
    },
  )

  it('푸시를 받을 수 있으면 띄우지 않는다', () => {
    search = 'mock-push=supported'
    render(<PushInappNotice week={HOME_MOCKS.high} signedIn />)
    expect(screen.queryByText(NOTICE)).toBeNull()
  })

  it('이번 주 발행된 안내가 없거나 비회원이면 띄우지 않는다', () => {
    search = 'mock-push=unsupported'
    const { rerender } = render(<PushInappNotice week={HOME_MOCKS.normal} signedIn />)
    expect(screen.queryByText(/알림 대신 여기서 알려드려요/)).toBeNull()
    rerender(<PushInappNotice week={HOME_MOCKS.high} signedIn={false} />)
    expect(screen.queryByText(NOTICE)).toBeNull()
  })

  it('푸시 지원을 모르는 서버 · 하이드레이션 첫 그림에는 그리지 않고, 하이드레이션 뒤에 띄운다', async () => {
    search = 'mock-push=unsupported'
    const ui = <PushInappNotice week={HOME_MOCKS.high} signedIn />
    const container = document.createElement('div')
    container.innerHTML = renderToString(ui)
    document.body.append(container)
    expect(container.textContent).toBe('')

    render(ui, { container, hydrate: true })
    await act(async () => {})
    expect(screen.getByText(NOTICE)).toBeDefined()
  })
})

describe('HomeScreen 알림 대신 홈 표시 (State-push-inapp)', () => {
  it('동네 안내가 발행된 주에 푸시를 받을 수 없는 회원이면 홈 상단에 띄운다 (jsdom 은 푸시 API 가 없다)', () => {
    search = 'mock-auth=member'
    render(<HomeScreen week={HOME_MOCKS.high} />)
    expect(screen.getByText(NOTICE)).toBeDefined()
  })

  it('비회원 홈에는 띄우지 않는다', () => {
    render(<HomeScreen week={HOME_MOCKS.high} />)
    expect(screen.queryByText(NOTICE)).toBeNull()
  })
})
