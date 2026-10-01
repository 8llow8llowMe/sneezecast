// @vitest-environment jsdom
import type { ComponentProps } from 'react'

import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { clearNoticeEntry, enteredNoticeInApp } from './notice-entry'
import { NoticeLink } from './notice-link'

// 테스트에는 Next 라우터가 없다. 링크는 누름만 확인하려고 a 로 바꾼다
vi.mock('next/link', () => ({
  default: ({ href, children, ...rest }: ComponentProps<'a'> & { href: string }) => (
    <a
      href={href}
      {...rest}
      onClick={(event) => {
        rest.onClick?.(event)
        event.preventDefault()
      }}
    >
      {children}
    </a>
  ),
}))

const HREF = '/notice/99990100/2025-W47?mock=published'

beforeEach(() => {
  clearNoticeEntry()
})

describe('NoticeLink', () => {
  it('누르면 앱 안에서 그 안내(쿼리 없는 경로)로 들어왔다고 남긴다', () => {
    render(<NoticeLink href={HREF}>안내 전체 보기</NoticeLink>)

    expect(enteredNoticeInApp('/notice/99990100/2025-W47')).toBe(false)
    fireEvent.click(screen.getByRole('link', { name: '안내 전체 보기' }))
    expect(enteredNoticeInApp('/notice/99990100/2025-W47')).toBe(true)
    expect(enteredNoticeInApp('/notice/11680640/2025-W47')).toBe(false)
  })

  it('새 탭 · 새 창으로 여는 누름은 남기지 않는다', () => {
    render(<NoticeLink href={HREF}>안내 전체 보기</NoticeLink>)
    const link = screen.getByRole('link', { name: '안내 전체 보기' })

    fireEvent.click(link, { metaKey: true })
    fireEvent.click(link, { ctrlKey: true })
    fireEvent.click(link, { shiftKey: true })
    fireEvent.click(link, { button: 1 })
    expect(enteredNoticeInApp('/notice/99990100/2025-W47')).toBe(false)
  })
})
