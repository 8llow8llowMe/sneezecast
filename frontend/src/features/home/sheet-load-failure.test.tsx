// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'

import { HomeScreen } from './home-screen'
import { HOME_MOCKS } from './mock'

vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}))

// 시트 청크를 받지 못한 경우를 흉내 낸다(지연 로드, #184) — 화면을 연 뒤 연결이 끊겼거나, 배포 뒤 오래 열어 둔 탭이라 청크 이름이 바뀌었다
function chunkLoadError(name: string) {
  const error = new Error(`Failed to load chunk static/chunks/${name}.js`)
  error.name = 'ChunkLoadError'
  return error
}
vi.mock('@/features/auth/login-sheet', () => {
  throw chunkLoadError('login-sheet')
})
vi.mock('@/features/report/report-flow', () => {
  throw chunkLoadError('report-flow')
})
vi.mock('./explain-sheet', () => {
  throw chunkLoadError('explain-sheet')
})

const FAILED = '화면을 불러오지 못했어요. 연결을 확인하고 새로고침해 주세요.'

describe('홈 시트를 받지 못하면', () => {
  beforeEach(() => {
    resetMockSession()
    window.history.replaceState(null, '', '/?region=1111051500&report=login')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('화면 전체를 오류로 바꾸지 않고 알린 뒤 시트 쿼리만 닫는다', async () => {
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<HomeScreen week={HOME_MOCKS.high} regionCode="1111051500" />)

    expect(await screen.findByText(FAILED)).toBeDefined()
    await waitFor(() =>
      expect(replaceState).toHaveBeenCalledWith(
        { sneezecastModalDepth: 0 },
        '',
        '?region=1111051500',
      ),
    )
    // 홈은 그대로다
    expect(screen.getByRole('heading', { level: 1 })).toBeDefined()
    expect(document.querySelector('dialog[open]')).toBeNull()
  })

  it('보고 흐름(동의한 회원)을 받지 못해도 알리고 시트 쿼리를 닫는다', async () => {
    window.history.replaceState(null, '', '/?mock-auth=member&report=start')
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<HomeScreen week={HOME_MOCKS.high} />)

    expect(await screen.findByText(FAILED)).toBeDefined()
    await waitFor(() =>
      expect(replaceState).toHaveBeenCalledWith(
        { sneezecastModalDepth: 0 },
        '',
        '?mock-auth=member',
      ),
    )
    expect(document.querySelector('dialog[open]')).toBeNull()
  })

  it('판단 기준을 받지 못해도 알리고 explain 만 지운다', async () => {
    window.history.replaceState(null, '', '/?mock=high&explain=1')
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(<HomeScreen week={HOME_MOCKS.high} />)

    expect(await screen.findByText(FAILED)).toBeDefined()
    await waitFor(() =>
      expect(replaceState).toHaveBeenCalledWith({ sneezecastModalDepth: 0 }, '', '?mock=high'),
    )
    expect(screen.getByRole('heading', { level: 1 })).toBeDefined()
  })
})
