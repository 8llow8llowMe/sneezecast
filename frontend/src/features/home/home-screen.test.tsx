// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { HomeScreen } from './home-screen'
import { HOME_MOCKS } from './mock'

describe('HomeScreen', () => {
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
