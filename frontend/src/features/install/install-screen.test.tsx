// @vitest-environment jsdom
import { StrictMode } from 'react'
import { renderToString } from 'react-dom/server'

import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { clearInstallEntry, enteredInstallInApp, markInstallEntry } from './install-entry'
import { InstallScreen } from './install-screen'

let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  useRouter: () => router,
}))

const IPHONE_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1'
const MAC_CHROME_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'

function setUserAgent(userAgent: string) {
  vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue(userAgent)
}

function guideTitles() {
  return screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent)
}

beforeEach(() => {
  search = ''
  clearInstallEntry()
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('InstallScreen', () => {
  it('제목 · 설명과 아래 버튼을 그린다. 모바일 설명 첫 문장은 태블릿부터 숨긴다', () => {
    render(<InstallScreen />)
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
      '홈 화면에 추가하면 다음 주에 알려드려요',
    )
    expect(
      screen.getByText(/주간 보고 알림은 우리동네체온계를 홈 화면에 추가한 뒤/).classList,
    ).toContain('tablet:hidden')
    expect(screen.getByText(/알림을 켜지 않아도 홈에서 같은 내용을 볼 수 있어요/)).toBeDefined()
    expect(screen.getByRole('button', { name: '알림 켜기' })).toBeDefined()
    // 모바일(회색 글자) · 태블릿 이상(회색 버튼) 모양이 달라 두 번 그리고 폭으로 하나만 보인다
    expect(screen.getAllByRole('button', { name: '나중에 할게요' })).toHaveLength(2)
  })

  it('모르는 기기는 폭별 두 묶음을 다 그린다 — 모바일은 iPhone · Android, 태블릿부터 iPad·Mac · Chrome·Edge', () => {
    render(<InstallScreen />)
    expect(guideTitles()).toEqual([
      'iPhone (Safari)',
      'Android (Chrome)',
      'iPad·Mac (Safari)',
      'Chrome·Edge',
    ])
    const iphone = screen.getByRole('region', { name: 'iPhone (Safari)' })
    expect(iphone.classList).toContain('tablet:hidden')
    expect(
      within(iphone)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([
      '1화면 아래 공유 버튼 누르기',
      '2"홈 화면에 추가" 고르기',
      '3추가한 앱을 열고 알림 허용하기',
    ])
    expect(screen.getByRole('region', { name: 'Chrome·Edge' }).classList).toContain('hidden')
  })

  it('기기를 알면 그 묶음 하나만 폭과 무관하게 보인다', () => {
    setUserAgent(IPHONE_UA)
    const { unmount } = render(<InstallScreen />)
    expect(guideTitles()).toEqual(['iPhone (Safari)'])
    expect(screen.getByRole('region', { name: 'iPhone (Safari)' }).classList).not.toContain(
      'tablet:hidden',
    )
    unmount()

    setUserAgent(MAC_CHROME_UA)
    render(<InstallScreen />)
    expect(guideTitles()).toEqual(['Chrome·Edge'])
  })

  it('하이드레이션 첫 그림은 서버처럼 두 묶음이고, 하이드레이션 뒤 기기 묶음 하나로 바뀐다', async () => {
    setUserAgent(IPHONE_UA)
    const ui = <InstallScreen />
    const container = document.createElement('div')
    container.innerHTML = renderToString(ui)
    document.body.append(container)
    expect(container.querySelectorAll('h2')).toHaveLength(4)

    render(ui, { container, hydrate: true })
    await act(async () => {})
    expect(guideTitles()).toEqual(['iPhone (Safari)'])
  })

  it('알림 켜기는 권한을 묻지 않고 준비 중을 알린다', async () => {
    const requestPermission = vi.fn()
    vi.stubGlobal('Notification', { requestPermission, permission: 'default' })
    render(<InstallScreen />)
    await userEvent.setup().click(screen.getByRole('button', { name: '알림 켜기' }))
    expect(
      screen.getByText('알림 켜기는 준비하고 있어요').closest('[role="status"]'),
    ).not.toBeNull()
    expect(requestPermission).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('주소로 바로 들어왔으면 닫기 · 나중에 할게요가 홈으로 기록을 바꿔 간다 (동네 · 덮어쓰기를 남긴다)', async () => {
    search = 'region=11440660&mock-auth=member&mock-push=needs-install&utm=x'
    render(<InstallScreen regionCode="11440660" />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(router.replace).toHaveBeenLastCalledWith(
      '/?region=11440660&mock-auth=member&mock-push=needs-install',
    )

    for (const later of screen.getAllByRole('button', { name: '나중에 할게요' })) {
      await user.click(later)
    }
    expect(router.replace).toHaveBeenCalledTimes(3)
    expect(router.back).not.toHaveBeenCalled()
  })

  it('앱 안 링크로 들어왔으면 닫기 · 나중에 할게요가 기록을 되돌린다', async () => {
    markInstallEntry()
    render(<InstallScreen />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '닫기' }))
    const [mobileLater] = screen.getAllByRole('button', { name: '나중에 할게요' })
    if (!mobileLater) throw new Error('나중에 할게요 버튼이 없다')
    await user.click(mobileLater)
    expect(router.back).toHaveBeenCalledTimes(2)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('표시는 마운트 때 소비한다 — 다음에 표시 없이 연 설치 안내(새 탭 · 새로고침 · 다른 길)는 홈으로 간다', async () => {
    markInstallEntry()
    const { unmount } = render(<InstallScreen />)
    expect(enteredInstallInApp()).toBe(false)
    unmount()

    render(<InstallScreen />)
    await userEvent.setup().click(screen.getByRole('button', { name: '닫기' }))
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('StrictMode 의 두 번 그리기 · effect 에도 읽은 값을 지킨다', async () => {
    markInstallEntry()
    render(
      <StrictMode>
        <InstallScreen />
      </StrictMode>,
    )
    expect(enteredInstallInApp()).toBe(false)
    await userEvent.setup().click(screen.getByRole('button', { name: '닫기' }))
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  })
})
