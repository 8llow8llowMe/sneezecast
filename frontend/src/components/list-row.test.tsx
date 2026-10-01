// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ListRow } from './list-row'

describe('ListRow', () => {
  it('제목 · 보조 문구 · 앞뒤 요소를 순서대로 놓는다', () => {
    const { container } = render(
      <ListRow
        leading={<span>앞</span>}
        title="발열·기침·인후통"
        description="지난주와 비슷해요"
        trailing={<span>뒤</span>}
      />,
    )

    expect(container.textContent).toBe('앞발열·기침·인후통지난주와 비슷해요뒤')
    expect(screen.getByText('지난주와 비슷해요').classList).toContain('text-fg-sub')
  })

  it('보조 문구가 없으면 빈 줄을 만들지 않는다', () => {
    const { container } = render(<ListRow title="구토·설사" />)
    expect(container.querySelectorAll('.text-fg-sub')).toHaveLength(0)
  })

  it.each([
    ['data', 'font-semibold', 'text-sub'],
    ['link', 'font-medium', 'text-caption'],
    ['menu', 'font-medium', 'text-sub'],
  ] as const)('%s 행은 제목 %s · 보조 문구 %s 다', (kind, titleClass, descriptionClass) => {
    render(<ListRow kind={kind} title="제목" description="보조" />)
    expect(screen.getByText('제목').classList).toContain(titleClass)
    expect(screen.getByText('보조').classList).toContain(descriptionClass)
  })

  it('최소 높이 56px 이고 divider 를 켜면 아래 구분선을 그린다', () => {
    const { container, rerender } = render(<ListRow title="구토·설사" />)
    const row = () => container.firstElementChild as HTMLElement

    expect(row().classList).toContain('min-h-14')
    expect(row().classList).not.toContain('border-b')

    rerender(<ListRow title="구토·설사" divider />)
    expect(row().classList).toContain('border-b')
    expect(row().classList).toContain('border-divider')
  })
})
