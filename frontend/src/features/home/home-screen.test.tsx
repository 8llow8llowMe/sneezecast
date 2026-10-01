// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { HomeScreen } from './home-screen'
import { HOME_MOCKS } from './mock'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
}))

/** 닫힌 dialog 는 보조기술 트리에서 빠지므로 태그로 찾는다 */
function explainDialog() {
  return document.querySelector('dialog')
}

describe('HomeScreen', () => {
  beforeEach(() => {
    search = ''
    window.history.replaceState(null, '', '/')
  })

  afterEach(() => {
    vi.restoreAllMocks()
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
    render(<HomeScreen week={HOME_MOCKS.normal} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '알림 설정' }))
    expect(screen.getByRole('status').textContent).toBe('알림 설정 화면은 준비하고 있어요')
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
    render(<HomeScreen week={HOME_MOCKS.high} />)

    await user.click(screen.getByRole('button', { name: '왜 이렇게 보나요?' }))
    // 테스트의 useSearchParams 는 주소를 따라가지 않으므로, 열린 뒤 다시 그린 상태를 흉내 낸다
    search = 'explain=1'
    await user.click(screen.getByRole('button', { name: '알림 설정' }))
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
