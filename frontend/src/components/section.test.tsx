// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Section, SectionBand } from './section'

describe('Section', () => {
  it('제목을 h2 로 놓고 내용을 그 아래에 둔다', () => {
    render(
      <Section title="증상별 변화">
        <p>내용</p>
      </Section>,
    )

    const heading = screen.getByRole('heading', { level: 2, name: '증상별 변화' })
    expect(heading.nextElementSibling?.textContent).toBe('내용')
  })
})

describe('Section layout', () => {
  it('panel 은 태블릿부터 여백을 없앤다', () => {
    const { container, rerender } = render(<Section title="제목">내용</Section>)
    const section = () => container.firstElementChild as HTMLElement

    expect(section().classList).toContain('tablet:px-page-tablet')

    rerender(
      <Section title="제목" layout="panel">
        내용
      </Section>,
    )
    expect(section().classList).toContain('px-page-mobile')
    expect(section().classList).toContain('tablet:p-0')
    expect(section().classList).not.toContain('tablet:px-page-tablet')
  })
})

describe('SectionBand', () => {
  it('8px 회색 띠이고 보조기술에 읽히지 않는다', () => {
    const { container } = render(<SectionBand />)
    const band = container.firstElementChild as HTMLElement

    expect(band.getAttribute('aria-hidden')).toBe('true')
    expect(band.classList).toContain('h-band')
    expect(band.classList).toContain('bg-section')
  })
})
