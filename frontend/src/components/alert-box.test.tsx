// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { AlertBox } from './alert-box'

describe('AlertBox', () => {
  it('실패는 나타나는 즉시 읽히게 alert 로 둔다', () => {
    render(<AlertBox tone="danger">카카오 로그인을 마치지 못했어요.</AlertBox>)
    const alert = screen.getByRole('alert')
    expect(alert.textContent).toBe('카카오 로그인을 마치지 못했어요.')
    expect(alert.classList).toContain('bg-danger-bg')
  })

  it.each([
    ['info', 'bg-info-bg'],
    ['neutral', 'bg-section'],
  ] as const)('%s 는 status 로 읽히고 %s 바탕이다', (tone, bg) => {
    render(
      <AlertBox tone={tone} action={<button type="button">이메일로 로그인</button>}>
        안내
      </AlertBox>,
    )
    const status = screen.getByRole('status')
    expect(status.classList).toContain(bg)
    expect(screen.getByRole('button', { name: '이메일로 로그인' })).toBeDefined()
  })

  it('꼭 읽혀야 하는 회색 상자는 role 을 alert 로 바꿀 수 있다', () => {
    render(
      <AlertBox tone="neutral" role="alert">
        잠시 막혔어요
      </AlertBox>,
    )
    expect(screen.getByRole('alert').classList).toContain('bg-section')
  })
})
