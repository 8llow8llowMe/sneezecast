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

  it('목 안내 링크에 둘러보기 동네를 잇는다', () => {
    render(<NoticeSection notice={HOME_MOCKS.high.notice} navSearch="region=11680640" />)

    expect(screen.getByRole('link', { name: '안내 전체 보기' }).getAttribute('href')).toBe(
      '/notice/99990100/2025-W47?mock=published&region=11680640',
    )
  })

  it('요약만 아는 안내(실데이터)는 배지 · 발행일 · 제목 · 안내 전체 보기를 보이고 목록 · 근거는 없다', () => {
    render(
      <NoticeSection
        notice={{
          publishedLabel: '11월 18일 발행',
          title: '호흡기 증상 보고가 늘었어요',
          href: '/notice/11680640/2025-W46',
        }}
        navSearch="region=11440660"
      />,
    )

    expect(screen.getByText('운영자 검토')).toBeDefined()
    expect(screen.getByText('호흡기 증상 보고가 늘었어요')).toBeDefined()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryByText(/근거:/)).toBeNull()
    // 둘러보기 동네를 잇는다(목 링크와 같다)
    expect(screen.getByRole('link', { name: '안내 전체 보기' }).getAttribute('href')).toBe(
      '/notice/11680640/2025-W46?region=11440660',
    )
  })

  it('발행 전이면 안내 없음과 질병관리청 공식 정보 링크를 보인다', () => {
    render(<NoticeSection notice={null} />)

    expect(screen.getByText('이번 주 발행된 동네 안내가 없어요')).toBeDefined()
    expect(screen.queryByText('운영자 검토')).toBeNull()
    expect(
      screen.getByRole('link', { name: '질병관리청 공식 정보 보기' }).getAttribute('href'),
    ).toBe('/official')
  })
})
