// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { type RegionStatus } from '@/lib/status'

import { StatusWord } from './status-word'

describe('StatusWord', () => {
  it.each([
    ['normal', '평소 수준', 'text-status-normal-text'],
    ['slight', '조금 늘었어요', 'text-status-slight-text'],
    ['high', '많이 늘었어요', 'text-status-high-text'],
    ['insufficient', '자료 부족', 'text-status-insufficient-text'],
  ] satisfies [RegionStatus, string, string][])(
    '%s 는 "%s" 글자를 %s 색으로 보인다',
    (status, label, color) => {
      render(<StatusWord status={status} />)
      expect(screen.getByText(label).classList).toContain(color)
    },
  )
})
