// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { IconButton } from './icon-button'
import { BellIcon } from './icons'

describe('IconButton', () => {
  it('label 이 버튼 이름이 되고 아이콘은 읽히지 않는다', () => {
    render(<IconButton label="알림 설정" icon={<BellIcon />} />)
    const button = screen.getByRole('button', { name: '알림 설정' })

    expect(button.getAttribute('type')).toBe('button')
    expect(button.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('터치 영역은 44×44 다', () => {
    render(<IconButton label="닫기" icon={<BellIcon />} />)
    expect(screen.getByRole('button').classList).toContain('size-touch')
  })

  it('누르면 onClick 을 부른다', async () => {
    const onClick = vi.fn()
    render(<IconButton label="닫기" icon={<BellIcon />} onClick={onClick} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '닫기' }))
    expect(onClick).toHaveBeenCalledTimes(1)
  })
})
