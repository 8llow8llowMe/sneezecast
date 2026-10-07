// @vitest-environment jsdom
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'
import { resetApiSession, selectApiSource } from '@/test/api-session'

import * as client from './admin-review-client'
import { listReviewCandidates, resetMockReviewCandidates } from './admin-review-client'
import { barHeightPercent } from './candidate-detail'
import { AdminReviewScreen } from './review-screen'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/admin/review',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}))

// 보내는 중을 붙잡아 보려고 저장만 감싼다(기본은 실제 목)
vi.mock('./admin-review-client', async (importOriginal) => {
  const actual = await importOriginal<typeof client>()
  return { ...actual, saveCandidateDraft: vi.fn(actual.saveCandidateDraft) }
})

/** 목 후보를 읽은 뒤(마이크로태스크)까지 그린다 */
async function renderScreen() {
  const view = render(<AdminReviewScreen />)
  await act(async () => {})
  return view
}

const table = () => screen.getByRole('table')
const rowNames = () =>
  within(table())
    .getAllByRole('button')
    .map((button) => button.textContent)
const detailTitle = () => screen.getByRole('heading', { level: 2 }).textContent
const draft = () => screen.getByRole('textbox', { name: '안내문 초안' })
const button = (name: string) => screen.getByRole('button', { name })
const pendingMenu = () =>
  within(screen.getByRole('navigation', { name: '운영 메뉴' })).getByRole('link', {
    name: /검토 대기/,
  }).textContent

async function checkAll(user: ReturnType<typeof userEvent.setup>) {
  for (const name of [
    '표본 기준(100명 이상) 충족',
    '반복·이상 보고 제외 완료',
    '질병관리청 공식 자료와 충돌 없음',
  ]) {
    await user.click(screen.getByRole('checkbox', { name }))
  }
}

beforeEach(() => {
  search = ''
  resetMockSession()
  resetMockReviewCandidates()
  vi.clearAllMocks()
  // 검토 시작(목은 만든 때부터 6분 전)과 경과를 고정한다 — 2025-11-20 14:08 KST
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2025-11-20T05:08:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
  resetApiSession()
})

describe('AdminReviewScreen 검토 대기 (목)', () => {
  it('시안의 후보 4건 · 머리줄 · 메뉴를 그리고 첫 후보의 상세를 연다', async () => {
    search = 'mock-auth=member&mock-role=operator&mock-admin=nope'
    await renderScreen()

    expect(screen.getByRole('heading', { level: 1, name: '검토 대기' })).toBeDefined()
    expect(screen.getByText(/^이번 주 후보 4건 · 후보 기준:/)).toBeDefined()
    expect(screen.getByText('11월 3주')).toBeDefined()
    expect(pendingMenu()).toBe('검토 대기, 남은 후보 4건')
    // 화면이 없는 메뉴는 링크가 아니다(없는 화면 404 로 가지 않게)
    expect(screen.queryByRole('link', { name: /발행 이력/ })).toBeNull()
    expect(screen.getByText('발행 이력').textContent).toBe('발행 이력 (준비 중)')
    // 메뉴 링크는 목 회원 · 역할 덮어쓰기만 잇는다(목 재현은 잇지 않는다)
    expect(
      within(screen.getByRole('navigation', { name: '운영 메뉴' }))
        .getByRole('link', { name: /검토 대기/ })
        .getAttribute('href'),
    ).toBe('/admin/review?mock-auth=member&mock-role=operator')
    expect(screen.getByRole('link', { name: '우리동네체온계' }).getAttribute('href')).toBe(
      '/?mock-auth=member&mock-role=operator',
    )

    expect(rowNames()).toEqual(['○○1동', '○○2동', '○○3동', '○○4동'])
    const first = within(table()).getAllByRole('row')[1]
    expect(first?.textContent).toContain('128명18%+9%p검토 중')
    expect(button('○○1동').getAttribute('aria-current')).toBe('true')

    expect(detailTitle()).toBe('○○1동 · 기준선 대비 변화')
    expect(
      screen.getByText('11월 3주 · 참여 128명 · 증상 보고 18% (기준선 9%) · 발열·기침·인후통'),
    ).toBeDefined()
    expect(
      screen.getByRole('img', { name: '최근 8주 증상 보고 비율. 기준선 9%, 이번 주 18%' }),
    ).toBeDefined()
    expect(screen.getByText('같은 기기 반복 보고').nextElementSibling?.textContent).toBe(
      '2건 · 집계 제외',
    )
    expect(screen.getByText('검토 시작 14:02 · 경과 6분')).toBeDefined()
    expect(screen.getByText('연결된 예방수칙: 기침 예절 · 손 씻기 (질병관리청)')).toBeDefined()
    expect(draft().getAttribute('aria-describedby')).toBeTruthy()
    expect(screen.getByText('AI 작성 · 운영자 수정 가능')).toBeDefined()
  })

  it('1단계에 없는 값(막대 · 이상 보고 확인 · 검토 시작)이 없는 후보는 그 칸 · 행을 숨긴다', async () => {
    await renderScreen()
    await userEvent.setup().click(button('○○3동'))

    expect(detailTitle()).toBe('○○3동 · 반복 보고 의심')
    expect(screen.getByText(/· 구토·설사$/)).toBeDefined()
    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.queryByText('이상 보고 확인')).toBeNull()
    expect(screen.queryByText(/검토 시작/)).toBeNull()
    expect(screen.getByText('발행 전 확인')).toBeDefined()
  })

  it('유형 필터는 그 유형만 보이고, 고른 후보가 빠지면 첫 후보를 고른다', async () => {
    await renderScreen()
    const user = userEvent.setup()

    await user.click(button('참여 급증 1'))
    expect(button('참여 급증 1').getAttribute('aria-pressed')).toBe('true')
    expect(rowNames()).toEqual(['○○2동'])
    expect(detailTitle()).toBe('○○2동 · 참여 급증')

    await user.click(button('기준선 변화 2'))
    expect(rowNames()).toEqual(['○○1동', '○○4동'])
    await user.click(button('전체 4'))
    expect(rowNames()).toHaveLength(4)
  })

  it('수정 저장: 고친 초안을 저장하고, 대기 후보는 검토 중이 된다', async () => {
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('○○2동'))
    await user.clear(draft())
    await user.type(draft(), '고친 안내문이에요.')
    await user.click(button('수정 저장'))

    expect(screen.getByRole('status').textContent).toBe('고친 초안을 저장했어요.')
    expect(within(table()).getAllByRole('row')[2]?.textContent).toContain('검토 중')
    const saved = await listReviewCandidates('mock')
    expect(saved.status === 'ready' && saved.candidates[1]?.draft).toBe('고친 안내문이에요.')
  })

  it('비었거나 너무 긴 초안은 저장 · 발행하지 않고 칸 아래에 알린다', async () => {
    await renderScreen()
    const user = userEvent.setup()

    await user.clear(draft())
    await user.click(button('수정 저장'))
    expect(screen.getByRole('alert').textContent).toBe('안내문 초안을 입력해 주세요.')
    expect(draft().getAttribute('aria-invalid')).toBe('true')

    await user.click(draft())
    await user.paste('가'.repeat(501))
    await checkAll(user)
    await user.click(button('승인하고 발행'))
    expect(screen.getByRole('alert').textContent).toBe('안내문은 500자까지 쓸 수 있어요.')
    expect(client.saveCandidateDraft).not.toHaveBeenCalled()
    expect(rowNames()).toHaveLength(4)
  })

  it('승인하고 발행: 발행 전 확인을 모두 눌러야 하고, 발행하면 목록에서 빠지고 다음 후보를 고른다', async () => {
    await renderScreen()
    const user = userEvent.setup()

    await user.click(button('승인하고 발행'))
    expect(screen.getByRole('alert').textContent).toBe('발행 전 확인을 모두 체크해 주세요.')
    expect(rowNames()).toHaveLength(4)

    await checkAll(user)
    expect(screen.queryByRole('alert')).toBeNull()
    await user.click(button('승인하고 발행'))

    expect(screen.getByText('○○1동 안내를 발행했어요.')).toBeDefined()
    expect(rowNames()).toEqual(['○○2동', '○○3동', '○○4동'])
    expect(detailTitle()).toBe('○○2동 · 참여 급증')
    expect(screen.getByText(/^이번 주 후보 3건/)).toBeDefined()
    expect(pendingMenu()).toBe('검토 대기, 남은 후보 3건')
    // 다음 후보의 발행 전 확인은 처음부터다
    expect(screen.getAllByRole('checkbox').every((box) => !(box as HTMLInputElement).checked)).toBe(
      true,
    )
  })

  it('보류: 후보는 목록에 남아 보류로 보이고, 검토 대기 수에서 빠진다', async () => {
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('보류'))

    expect(screen.getByRole('status').textContent).toBe(
      '이 후보를 보류했어요. 이번 주에는 안내를 내지 않아요.',
    )
    expect(within(table()).getAllByRole('row')[1]?.textContent).toContain('보류')
    expect(rowNames()).toHaveLength(4)
    expect(pendingMenu()).toBe('검토 대기, 남은 후보 3건')
    expect(button('보류').hasAttribute('disabled')).toBe(true)
  })

  it('보내는 동안에는 세 버튼이 모두 꺼진다', async () => {
    let finish: (result: client.CandidateActionResult) => void = () => {}
    vi.mocked(client.saveCandidateDraft).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await renderScreen()
    await userEvent.setup().click(button('수정 저장'))

    for (const name of ['보류', '수정 저장', '승인하고 발행']) {
      expect(button(name).hasAttribute('disabled')).toBe(true)
    }
    act(() => finish({ status: 'unavailable' }))
    await act(async () => {})
    expect(button('수정 저장').hasAttribute('disabled')).toBe(false)
  })

  it('?mock-admin=fail 이면 처리하지 못했다고 알리고 초안은 그대로다', async () => {
    search = 'mock-admin=fail'
    await renderScreen()
    const user = userEvent.setup()
    await user.type(draft(), ' 추가')
    await user.click(button('수정 저장'))

    expect(screen.getByRole('alert').textContent).toBe(
      '초안을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect((draft() as HTMLTextAreaElement).value).toMatch(/ 추가$/)

    await user.click(button('보류'))
    expect(screen.getByRole('alert').textContent).toBe(
      '보류하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
  })

  it('?mock-admin=conflict 면 최신 내용으로 바꿔 그리고 다시 확인하라고 알린다 — 다시 하면 된다', async () => {
    search = 'mock-admin=conflict'
    await renderScreen()
    const user = userEvent.setup()
    const original = (draft() as HTMLTextAreaElement).value
    await user.type(draft(), ' 추가')
    await user.click(button('수정 저장'))

    expect(screen.getByRole('status').textContent).toBe(
      '다른 운영자가 먼저 이 후보를 고쳤어요. 최신 내용으로 바꿔 두었어요. 확인한 뒤 다시 시도해 주세요.',
    )
    expect((draft() as HTMLTextAreaElement).value).toBe(original)
    expect(document.activeElement).toBe(draft())

    await user.click(button('수정 저장'))
    expect(screen.getByRole('status').textContent).toBe('고친 초안을 저장했어요.')
  })

  it('?mock-admin=empty 면 후보가 없다고 알리고 상세가 없다', async () => {
    search = 'mock-admin=empty'
    await renderScreen()
    expect(screen.getByText('이번 주 검토할 후보가 없어요.')).toBeDefined()
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
    expect(pendingMenu()).toBe('검토 대기, 남은 후보 0건')
  })
})

describe('AdminReviewScreen 보내는 중 · 포커스 · 처리 결과 갈래 (목)', () => {
  /** 다음 저장 요청을 테스트가 끝낼 때까지 붙잡는다 */
  function holdSave() {
    const pending: { finish: (result: client.CandidateActionResult) => void } = { finish: () => {} }
    vi.mocked(client.saveCandidateDraft).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          pending.finish = resolve
        }),
    )
    return pending
  }

  it('마우스는 행 어디를 눌러도 고른다 — 키보드 · 화면 읽기의 버튼은 행정동 칸 하나다', async () => {
    await renderScreen()
    const row = within(table()).getAllByRole('row')[3]
    if (!row) throw new Error('no row')
    expect(within(row).getAllByRole('button')).toHaveLength(1)

    await userEvent.setup().click(within(row).getByText('146명'))
    expect(detailTitle()).toBe('○○3동 · 반복 보고 의심')
    expect(button('○○3동').getAttribute('aria-current')).toBe('true')
  })

  it('보내는 동안 행 · 필터를 눌러도 무시하고, 늦게 온 결과는 보낸 후보의 상세에 붙는다', async () => {
    const pending = holdSave()
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('수정 저장'))

    expect(button('○○2동').hasAttribute('disabled')).toBe(true)
    expect(button('참여 급증 1').hasAttribute('disabled')).toBe(true)
    await user.click(within(table()).getByText('212명'))
    await user.click(button('참여 급증 1'))
    expect(detailTitle()).toBe('○○1동 · 기준선 대비 변화')
    expect(rowNames()).toHaveLength(4)
    expect(button('전체 4').getAttribute('aria-pressed')).toBe('true')

    // 처리 결과 unavailable(실데이터 API 없음)도 실패로 알린다
    act(() => pending.finish({ status: 'unavailable' }))
    await act(async () => {})
    expect(detailTitle()).toBe('○○1동 · 기준선 대비 변화')
    expect(screen.getByRole('alert').textContent).toBe(
      '초안을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(button('○○2동').hasAttribute('disabled')).toBe(false)
  })

  it('발행하면 새로 고른 상세 제목으로 포커스를 옮긴다 — 필터를 건 채면 그 목록의 다음(끝이면 앞) 후보다', async () => {
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('기준선 변화 2'))
    await user.click(button('○○4동'))
    await checkAll(user)
    await user.click(button('승인하고 발행'))

    expect(screen.getByText('○○4동 안내를 발행했어요.')).toBeDefined()
    expect(button('기준선 변화 1').getAttribute('aria-pressed')).toBe('true')
    expect(rowNames()).toEqual(['○○1동'])
    expect(detailTitle()).toBe('○○1동 · 기준선 대비 변화')
    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 2 }))
  })

  it('보이는 목록의 마지막 후보를 발행하면 상세가 없어지고 발행 알림으로 포커스를 옮긴다', async () => {
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('참여 급증 1'))
    await checkAll(user)
    await user.click(button('승인하고 발행'))

    expect(screen.getByText('이 유형의 후보가 없어요.')).toBeDefined()
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
    const notice = screen.getByText('○○2동 안내를 발행했어요.')
    expect(document.activeElement?.contains(notice)).toBe(true)
  })

  it('충돌인데 후보가 없으면(다른 운영자가 먼저 발행) 목록에서 빼고 알린 뒤 다음 후보를 고른다', async () => {
    vi.mocked(client.saveCandidateDraft).mockResolvedValueOnce({
      status: 'conflict',
      candidate: null,
    })
    await renderScreen()
    await userEvent.setup().click(button('수정 저장'))

    expect(screen.getByText('다른 운영자가 ○○1동 후보를 먼저 발행했어요.')).toBeDefined()
    expect(rowNames()).toEqual(['○○2동', '○○3동', '○○4동'])
    expect(detailTitle()).toBe('○○2동 · 참여 급증')
    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 2 }))
  })

  it('응답 전에 화면을 떠나면 늦은 응답을 버린다(상태를 바꾸지 않고 오류도 없다)', async () => {
    const pending = holdSave()
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const view = await renderScreen()
    await userEvent.setup().click(button('수정 저장'))
    view.unmount()

    act(() => pending.finish({ status: 'ok', candidate: null }))
    await act(async () => {})
    expect(consoleError).not.toHaveBeenCalled()
    consoleError.mockRestore()
  })
})

describe('barHeightPercent 기준선 막대 높이', () => {
  it('가장 큰 값이 96% 이고, 모두 0 이면 0 으로 나누지 않고 바닥이다', () => {
    expect(barHeightPercent(18, 18)).toBe(96)
    expect(barHeightPercent(9, 18)).toBe(48)
    expect(barHeightPercent(0, 0)).toBe(0)
    expect(barHeightPercent(5, 0)).toBe(0)
    expect(barHeightPercent(Number.NaN, 10)).toBe(0)
  })
})

describe('AdminReviewScreen 실데이터', () => {
  it('운영자 API 가 없어 요청 없이 준비 중이라고 알린다 — 목 재현 쿼리도 듣지 않는다', async () => {
    selectApiSource()
    search = 'mock-admin=empty'
    await renderScreen()

    expect(screen.getByRole('status').textContent).toBe(
      '운영자 검토는 아직 준비하고 있어요. 지금은 검토 후보를 불러올 수 없어요.',
    )
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByText(/이번 주 후보/)).toBeNull()
    expect(screen.queryByText('이번 주 검토할 후보가 없어요.')).toBeNull()
    expect(pendingMenu()).toBe('검토 대기')
  })
})
