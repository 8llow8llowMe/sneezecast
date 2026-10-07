// @vitest-environment jsdom
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'
import { resetApiSession, selectApiSource } from '@/test/api-session'

import * as client from './admin-history-client'
import { resetMockHistory } from './admin-history-client'
import { resetMockReviewCandidates } from './admin-review-client'
import { AdminHistoryScreen } from './history-screen'
import { createMockHistory } from './mock'
import type { HistoryEntry } from './types'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/admin/history',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}))

// 보내는 중 · 1단계 값이 없는 이력을 흉내 내려고 감싼다(기본은 실제 목)
vi.mock('./admin-history-client', async (importOriginal) => {
  const actual = await importOriginal<typeof client>()
  return {
    ...actual,
    listHistory: vi.fn(actual.listHistory),
    retractAdvisory: vi.fn(actual.retractAdvisory),
  }
})

async function renderScreen() {
  const view = render(<AdminHistoryScreen />)
  await act(async () => {})
  return view
}

const table = () => screen.getByRole('table')
const rows = () => within(table()).getAllByRole('row').slice(1)
const detailTitle = () => screen.getAllByRole('heading', { level: 2 })[0]?.textContent
const button = (name: string) => screen.getByRole('button', { name })
const dialog = () => screen.getByRole('dialog')
const steps = () =>
  within(screen.getByRole('list', { name: '처리 이력' }))
    .getAllByRole('listitem')
    .map((step) => step.textContent)
const stat = (label: string) =>
  screen.getByText(label, { selector: 'dt' }).nextElementSibling?.textContent

beforeEach(() => {
  search = ''
  resetMockSession()
  resetMockHistory()
  resetMockReviewCandidates()
  vi.clearAllMocks()
  // 2025-11-20 14:08 KST
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2025-11-20T05:08:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
  resetApiSession()
})

describe('AdminHistoryScreen 발행 이력 (목)', () => {
  it('요약 · 표 · 첫 행의 상세를 그린다 — 요약은 목록에서 계산한다', async () => {
    search = 'mock-auth=member&mock-role=operator'
    await renderScreen()

    expect(screen.getByRole('heading', { level: 1, name: '발행 이력' })).toBeDefined()
    expect(screen.getByText(/^모든 후보의/).textContent).toBe(
      '모든 후보의 승인·수정·보류 기록이 남아요. 검토 시간은 시범 운영 지표로 씁니다.',
    )
    expect(stat('발행')).toBe('3건')
    expect(stat('보류')).toBe('1건')
    expect(stat('평균 검토 시간')).toBe('7분')
    expect(stat('수정 후 발행 비율')).toBe('33%')

    const menu = within(screen.getByRole('navigation', { name: '운영 메뉴' }))
    expect(menu.getByRole('link', { name: '발행 이력' }).getAttribute('aria-current')).toBe('page')
    expect(menu.getByRole('link', { name: /검토 대기/ }).textContent).toBe(
      '검토 대기, 남은 후보 4건',
    )

    expect(
      within(table())
        .getAllByRole('columnheader')
        .map((th) => th.textContent),
    ).toEqual(['발행·처리일', '행정동', '기준 주', '후보 유형', '결과', '담당', '검토 시간'])
    expect(rows()[0]?.textContent).toContain('11월 11일○○1동')
    expect(rows()[0]?.textContent).toContain('11월 2주기준선 변화수정 후 발행운영자 A6분')
    expect(rows()[3]?.textContent).toContain('10월 28일')
    expect(rows()[3]?.textContent).toContain('10월 4주')
    expect(button('○○1동').getAttribute('aria-current')).toBe('true')

    expect(detailTitle()).toBe('○○1동 · 11월 2주')
    expect(screen.getByText('기준선 변화 · 참여 128명 · 증상 보고 18%')).toBeDefined()
    expect(steps()).toEqual([
      '후보 등록 · 14:02기준선 9% 대비 +9%p · 표본 128명',
      'AI 초안 생성 · 14:03출처 확인된 집계값 3개 · 예방수칙 2건 연결',
      '운영자 수정 · 14:06"유행" 표현 삭제 · 행동 문장 1개 추가',
      '승인·발행 · 14:08운영자 A · 검토 6분',
    ])
    expect(screen.getByText(/^○○동에서 이번 주 발열·기침 보고가/)).toBeDefined()
    expect(button('정정 발행')).toBeDefined()
    expect(button('발행 철회')).toBeDefined()
    expect(screen.getByRole('link', { name: '사용자 화면에서 보기' }).getAttribute('href')).toBe(
      '/notice/99990101/2025-W46?mock=published',
    )
  })

  it('행 어디를 눌러도 고른다 — 보류는 정정 · 철회 · 사용자 화면 링크가 없다', async () => {
    await renderScreen()
    await userEvent.setup().click(within(rows()[1]!).getByText('참여 급증'))

    expect(detailTitle()).toBe('○○5동 · 11월 2주')
    expect(button('○○5동').getAttribute('aria-current')).toBe('true')
    expect(steps().at(-1)).toBe('보류 · 13:20운영자 B · 검토 9분')
    expect(screen.queryByRole('button', { name: '정정 발행' })).toBeNull()
    expect(screen.queryByRole('button', { name: '발행 철회' })).toBeNull()
    expect(screen.queryByRole('link', { name: '사용자 화면에서 보기' })).toBeNull()
  })

  it('보류했다가 발행한 이번 주 후보는 한 행이고, 담당 · 검토 시간은 마지막 처리 단계에만 붙는다', async () => {
    const review = await import('./admin-review-client')
    const list = await review.listReviewCandidates('mock')
    if (list.status !== 'ready' || !list.candidates[0]) throw new Error('not ready')
    const { id, version, draft } = list.candidates[0] // ○○1동, 6분 전 검토 시작
    await review.holdCandidate(id, { version }, 'mock')
    await review.publishCandidate(id, { draft, version: version + 1 }, 'mock')
    await renderScreen()

    expect(rows()[0]?.textContent).toContain('11월 3주')
    expect(rows()[0]?.textContent).toContain('발행')
    expect(steps()).toEqual(['보류 · 14:08', '승인·발행 · 14:08검토 6분'])
    expect(stat('발행')).toBe('4건')
  })

  it('1단계에 없는 값(담당 · 검토 시간 · 세부 문구)이 없으면 열 · 칸 · 문구를 숨긴다', async () => {
    // 담당 · 검토 시간 · 세부 문구(1단계에 없는 값)를 뺀 이력
    const plain = createMockHistory().map((item) => {
      const rest = Object.fromEntries(
        Object.entries(item).filter(([key]) => key !== 'operatorName' && key !== 'reviewMinutes'),
      ) as HistoryEntry
      return { ...rest, timeline: item.timeline.map(({ step, at }) => ({ step, at })) }
    })
    vi.mocked(client.listHistory).mockResolvedValueOnce({ status: 'ready', entries: plain })
    await renderScreen()

    expect(screen.queryByText('평균 검토 시간')).toBeNull()
    expect(screen.getByText(/^모든 후보의/).textContent).toBe(
      '모든 후보의 승인·수정·보류 기록이 남아요.',
    )
    expect(stat('수정 후 발행 비율')).toBe('33%')
    expect(within(table()).queryByRole('columnheader', { name: '담당' })).toBeNull()
    expect(within(table()).queryByRole('columnheader', { name: '검토 시간' })).toBeNull()
    expect(steps().at(-1)).toBe('승인·발행 · 14:08')
  })

  it('정정 발행: 사유 · 본문을 검사하고, 보내면 새 안내를 고르고 이전 안내는 정정됨이 된다', async () => {
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('정정 발행'))
    expect(within(dialog()).getByRole('heading', { name: '정정 발행할까요?' })).toBeDefined()
    const reason = within(dialog()).getByRole('textbox', { name: '정정 사유' })
    const body = within(dialog()).getByRole('textbox', { name: '고친 본문' })
    expect((body as HTMLTextAreaElement).value).toMatch(/^○○동에서 이번 주/)

    await user.clear(body)
    await user.click(within(dialog()).getByRole('button', { name: '정정 발행' }))
    expect(
      within(dialog())
        .getAllByRole('alert')
        .map((item) => item.textContent),
    ).toEqual(['정정 사유를 입력해 주세요.', '고친 본문을 입력해 주세요.'])
    expect(reason.getAttribute('aria-invalid')).toBe('true')

    await user.click(reason)
    await user.paste('가'.repeat(501))
    await user.type(body, '고친 안내예요.')
    await user.click(within(dialog()).getByRole('button', { name: '정정 발행' }))
    expect(within(dialog()).getByRole('alert').textContent).toBe('사유는 500자까지 쓸 수 있어요.')

    await user.clear(reason)
    await user.type(reason, '참여자 수를 바로잡았어요.')
    await user.click(within(dialog()).getByRole('button', { name: '정정 발행' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(rows()[0]?.textContent).toContain('정정 발행')
    expect(rows()[1]?.textContent).toContain('정정됨')
    expect(detailTitle()).toBe('○○1동 · 11월 2주')
    expect(document.activeElement).toBe(screen.getAllByRole('heading', { level: 2 })[0])
    expect(screen.getByRole('status').textContent).toBe(
      '정정 발행했어요. 사용자 화면에 정정일과 사유가 함께 보여요.',
    )
    expect(steps()).toEqual(['정정 발행 · 14:08참여자 수를 바로잡았어요.'])
    expect(screen.getByText('고친 안내예요.', { selector: 'p' })).toBeDefined()
    expect(screen.getByRole('link', { name: '사용자 화면에서 보기' }).getAttribute('href')).toBe(
      '/notice/99990101/2025-W46?mock=corrected',
    )
    // 정정 발행은 같은 안내를 바로잡은 것이라 발행 수 · 비율에 세지 않는다
    expect(stat('발행')).toBe('3건')

    await user.click(within(rows()[1]!).getByRole('button'))
    expect(steps().at(-1)).toBe('정정됨 · 11월 20일 14:08참여자 수를 바로잡았어요.')
    expect(screen.queryByRole('button', { name: '정정 발행' })).toBeNull()
  })

  it('발행 철회: 사유가 필수이고, 철회하면 끝 상태라 버튼이 없어지고 상세 제목으로 포커스를 옮긴다', async () => {
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('발행 철회'))
    expect(within(dialog()).getByText('사용자 화면에는 “철회된 안내예요”가 남아요.')).toBeDefined()
    await user.click(within(dialog()).getByRole('button', { name: '발행 철회' }))
    expect(within(dialog()).getByRole('alert').textContent).toBe('철회 사유를 입력해 주세요.')
    expect(client.retractAdvisory).not.toHaveBeenCalled()

    await user.type(
      within(dialog()).getByRole('textbox', { name: '철회 사유' }),
      '공식 자료와 달랐어요.',
    )
    await user.click(within(dialog()).getByRole('button', { name: '발행 철회' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(rows()[0]?.textContent).toContain('철회')
    expect(screen.queryByRole('button', { name: '정정 발행' })).toBeNull()
    expect(screen.queryByRole('button', { name: '발행 철회' })).toBeNull()
    expect(steps().at(-1)).toBe('발행 철회 · 11월 20일 14:08공식 자료와 달랐어요.')
    expect(document.activeElement).toBe(screen.getAllByRole('heading', { level: 2 })[0])
    expect(screen.getByRole('link', { name: '사용자 화면에서 보기' }).getAttribute('href')).toBe(
      '/notice/99990101/2025-W46?mock=retracted',
    )
  })

  it('보내는 동안 대화상자 버튼 · 닫기 · 행 고르기를 막는다', async () => {
    let finish: (result: client.HistoryActionResult) => void = () => {}
    vi.mocked(client.retractAdvisory).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('발행 철회'))
    await user.type(within(dialog()).getByRole('textbox', { name: '철회 사유' }), '사유')
    await user.click(within(dialog()).getByRole('button', { name: '발행 철회' }))

    for (const name of ['취소', '발행 철회']) {
      expect(within(dialog()).getByRole('button', { name }).getAttribute('aria-disabled')).toBe(
        'true',
      )
    }
    expect(
      within(dialog()).getByRole('textbox', { name: '철회 사유' }).hasAttribute('readonly'),
    ).toBe(true)
    await user.click(within(dialog()).getByRole('button', { name: '취소' }))
    fireEvent(dialog(), new Event('cancel'))
    expect(screen.getByRole('dialog')).toBeDefined()
    expect(within(table()).getByRole('button', { name: '○○5동' }).hasAttribute('disabled')).toBe(
      true,
    )

    act(() => finish({ status: 'unavailable' }))
    await act(async () => {})
    expect(within(dialog()).getByRole('alert').textContent).toBe(
      '철회하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    await user.click(within(dialog()).getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('응답 전에 화면을 떠나면 늦은 응답을 버린다(상태를 바꾸지 않고 오류도 없다)', async () => {
    let finish: (result: client.HistoryActionResult) => void = () => {}
    vi.mocked(client.retractAdvisory).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const view = await renderScreen()
    const user = userEvent.setup()
    await user.click(button('발행 철회'))
    await user.type(within(dialog()).getByRole('textbox', { name: '철회 사유' }), '사유')
    await user.click(within(dialog()).getByRole('button', { name: '발행 철회' }))
    view.unmount()

    act(() => finish({ status: 'ok', entries: [], selectedId: 'advisory-1' }))
    await act(async () => {})
    expect(consoleError).not.toHaveBeenCalled()
    consoleError.mockRestore()
  })

  it('?mock-admin=fail 이면 대화상자 안에 실패를 알리고 입력을 그대로 둔다', async () => {
    search = 'mock-admin=fail'
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('정정 발행'))
    await user.type(within(dialog()).getByRole('textbox', { name: '정정 사유' }), '사유')
    await user.click(within(dialog()).getByRole('button', { name: '정정 발행' }))

    expect(within(dialog()).getByRole('alert').textContent).toBe(
      '정정 발행하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(
      within(dialog()).getByRole<HTMLTextAreaElement>('textbox', { name: '정정 사유' }).value,
    ).toBe('사유')
    expect(rows()[0]?.textContent).toContain('수정 후 발행')
  })

  it('?mock-admin=conflict 면 대화상자를 닫고 최신 이력으로 바꿔 알린다 — 다시 하면 된다', async () => {
    search = 'mock-admin=conflict'
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('발행 철회'))
    await user.type(within(dialog()).getByRole('textbox', { name: '철회 사유' }), '사유')
    await user.click(within(dialog()).getByRole('button', { name: '발행 철회' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByRole('status').textContent).toBe(
      '다른 운영자가 먼저 이 안내를 바꿨어요. 최신 내용으로 바꿔 두었어요. 확인한 뒤 다시 시도해 주세요.',
    )
    expect(document.activeElement).toBe(screen.getAllByRole('heading', { level: 2 })[0])

    await user.click(button('발행 철회'))
    await user.type(within(dialog()).getByRole('textbox', { name: '철회 사유' }), '사유')
    await user.click(within(dialog()).getByRole('button', { name: '발행 철회' }))
    expect(rows()[0]?.textContent).toContain('철회')
  })

  it('?mock-admin=empty 면 기록이 없다고 알리고 비율 칸 · 상세가 없다', async () => {
    search = 'mock-admin=empty'
    await renderScreen()
    expect(screen.getByText('아직 발행하거나 보류한 기록이 없어요.')).toBeDefined()
    expect(stat('발행')).toBe('0건')
    expect(screen.queryByText('수정 후 발행 비율')).toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
  })
})

describe('AdminHistoryScreen 실데이터', () => {
  it('운영자 API 가 없어 요청 없이 준비 중이라고 알린다 — 목 재현 쿼리도 듣지 않는다', async () => {
    selectApiSource()
    search = 'mock-admin=empty'
    await renderScreen()

    expect(screen.getByRole('status').textContent).toBe(
      '발행 이력은 아직 준비하고 있어요. 지금은 이력을 불러올 수 없어요.',
    )
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByText('발행', { selector: 'dt' })).toBeNull()
    expect(
      within(screen.getByRole('navigation', { name: '운영 메뉴' })).getByRole('link', {
        name: /검토 대기/,
      }).textContent,
    ).toBe('검토 대기')
  })
})
