// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { Button } from './button'

describe('Button', () => {
  it('기본은 type="button" 의 큰 주요 버튼이다', () => {
    render(<Button>이번 주 건강 보고하기</Button>)
    const button = screen.getByRole('button', { name: '이번 주 건강 보고하기' })

    // form 안에서 의도치 않게 제출하지 않도록 기본 type 을 button 으로 둔다
    expect(button.getAttribute('type')).toBe('button')
    expect(button.classList).toContain('bg-brand')
    expect(button.classList).toContain('h-button')
    expect(button.classList).toContain('text-section-title')
  })

  it('type 을 지정하면 그대로 쓴다', () => {
    render(<Button type="submit">보내기</Button>)
    expect(screen.getByRole('button').getAttribute('type')).toBe('submit')
  })

  it.each([
    ['secondary', 'bg-section'],
    ['text', 'text-brand'],
    ['subtle', 'text-fg-sub'],
  ] as const)('%s 변형은 %s 를 쓴다', (variant, expected) => {
    render(<Button variant={variant}>보고 수정하기</Button>)
    expect(screen.getByRole('button').classList).toContain(expected)
  })

  it('작은 크기는 44px 높이 · 15px 글자다', () => {
    render(<Button size="sm">이번 주 건강 보고하기</Button>)
    const { classList } = screen.getByRole('button')
    expect(classList).toContain('h-button-sm')
    expect(classList).toContain('text-body')
  })

  it('글자 버튼은 높이 대신 터치 영역 최소 높이를 지킨다', () => {
    render(<Button variant="text">우리 동네 자료 함께 채우기</Button>)
    expect(screen.getByRole('button').classList).toContain('min-h-touch')
  })

  it('fullWidth 면 폭을 채운다', () => {
    render(<Button fullWidth>우리 동네 변화 보기</Button>)
    expect(screen.getByRole('button').classList).toContain('w-full')
  })

  it('누르면 onClick 을 부르고, 비활성이면 부르지 않는다', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    const { rerender } = render(<Button onClick={onClick}>보내기</Button>)

    await user.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledTimes(1)

    rerender(
      <Button onClick={onClick} disabled>
        보내기
      </Button>,
    )
    await user.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledTimes(1)
  })
})
