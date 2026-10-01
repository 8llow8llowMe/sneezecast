// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Badge, type BadgeKind } from './badge'

describe('Badge', () => {
  it.each([
    ['official', '공식', 'bg-brand'],
    ['citizen', '시민 자가보고', 'border-muted-bar'],
    ['review', '운영자 검토', 'border-emphasis'],
  ] satisfies [BadgeKind, string, string][])('%s 는 "%s" 를 %s 로 보인다', (kind, label, cls) => {
    render(<Badge kind={kind} />)
    const badge = screen.getByText(label)
    expect(badge.classList).toContain(cls)
    expect(badge.classList).toContain('rounded-chip')
  })
})
