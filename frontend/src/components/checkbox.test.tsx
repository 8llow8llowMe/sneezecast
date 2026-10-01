// @vitest-environment jsdom
import { useState } from 'react'

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { Checkbox } from './checkbox'

function Harness() {
  const [checked, setChecked] = useState(false)
  return (
    <Checkbox
      checked={checked}
      onChange={(event) => setChecked(event.target.checked)}
      label="성인 본인의 건강 상태만 보고할게요"
    />
  )
}

describe('Checkbox', () => {
  it('네이티브 체크 상자라 글자를 눌러도 바뀌고 이름으로 찾힌다', async () => {
    render(<Harness />)
    const box = screen.getByRole<HTMLInputElement>('checkbox', {
      name: '성인 본인의 건강 상태만 보고할게요',
    })
    expect(box.checked).toBe(false)

    await userEvent.setup().click(screen.getByText('성인 본인의 건강 상태만 보고할게요'))
    expect(box.checked).toBe(true)
  })

  it('켜지면 네이비 상자에 체크를 그린다', () => {
    const { container, rerender } = render(<Checkbox checked={false} readOnly label="동의" />)
    const visual = container.querySelector('span[aria-hidden="true"]')
    expect(visual?.classList).toContain('border-muted-bar')
    expect(visual?.querySelector('svg')).toBeNull()

    rerender(<Checkbox checked readOnly label="동의" />)
    expect(visual?.classList).toContain('bg-brand')
    expect(visual?.querySelector('svg')).not.toBeNull()
  })
})
