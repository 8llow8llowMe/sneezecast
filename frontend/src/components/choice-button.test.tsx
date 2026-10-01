// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { ChoiceButton } from './choice-button'

describe('ChoiceButton', () => {
  it('바로 넘어가는 선택지는 aria-pressed 가 없고 회색 1px 테두리다', () => {
    render(<ChoiceButton label="증상 없었어요" />)
    const button = screen.getByRole('button', { name: '증상 없었어요' })

    expect(button.getAttribute('aria-pressed')).toBeNull()
    expect(button.classList).toContain('border-hairline')
    expect(button.classList).toContain('min-h-16')
  })

  it('고른 선택지는 aria-pressed="true", 2px 네이비 테두리, 체크 아이콘이다', () => {
    const { container } = render(<ChoiceButton label="발열·기침·인후통" size="md" selected />)
    const button = screen.getByRole('button', { name: '발열·기침·인후통' })

    expect(button.getAttribute('aria-pressed')).toBe('true')
    expect(button.classList).toContain('border-selected')
    expect(button.classList).toContain('border-brand')
    expect(button.classList).toContain('min-h-14')
    expect(container.querySelector('svg')).not.toBeNull()
  })

  it('고르지 않은 여러 개 선택지는 aria-pressed="false" 이고 체크가 없다', () => {
    const { container } = render(<ChoiceButton label="구토·설사" selected={false} />)

    expect(screen.getByRole('button').getAttribute('aria-pressed')).toBe('false')
    expect(container.querySelector('svg')).toBeNull()
  })

  it('보조 문구는 설명으로 연결하고, 누르면 onClick 을 부른다', async () => {
    const onClick = vi.fn()
    render(
      <ChoiceButton
        label="그 외 증상만 있었어요"
        hint="두통, 근육통 등"
        selected={false}
        onClick={onClick}
      />,
    )

    const button = screen.getByRole('button', {
      name: /그 외 증상만 있었어요/,
      description: '두통, 근육통 등',
    })
    await userEvent.setup().click(button)
    expect(onClick).toHaveBeenCalledTimes(1)
  })
})
