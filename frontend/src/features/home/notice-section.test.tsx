// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { HOME_MOCKS } from './mock'
import { NoticeSection } from './notice-section'

describe('NoticeSection', () => {
  it('발행된 안내는 운영자 검토 배지 · 발행일 · 순서 목록 · 근거를 보인다', () => {
    render(<NoticeSection notice={HOME_MOCKS.high.notice} />)

    expect(screen.getByText('운영자 검토')).toBeDefined()
    expect(screen.getByText('11월 18일 발행')).toBeDefined()
    const items = screen.getAllByRole('listitem').map((item) => item.textContent)
    expect(items).toEqual(['1기침할 때 옷소매로 입과 코 가리기', '2손 씻기와 실내 환기 자주 하기'])
    expect(screen.getByText('근거: 질병관리청 예방수칙')).toBeDefined()
    expect(screen.getByRole('link', { name: '안내 전체 보기' }).getAttribute('href')).toBe(
      '/notice/99990100/2025-W47?mock=published',
    )
  })

  it('발행 전이면 안내 없음과 공식 예방수칙 링크를 보인다', () => {
    render(<NoticeSection notice={null} />)

    expect(screen.getByText('이번 주 발행된 동네 안내가 없어요')).toBeDefined()
    expect(screen.queryByText('운영자 검토')).toBeNull()
    expect(screen.getByRole('link', { name: '공식 예방수칙 보기' }).getAttribute('href')).toBe(
      '/official',
    )
  })
})
