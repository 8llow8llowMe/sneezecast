// @vitest-environment jsdom
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'
import { ApiError } from '@/lib/api/api-error'
import { CANDIDATE_WEEK, candidateAdvisory, candidateItem, codeName } from '@/test/admin-review'
import { resetApiSession, selectApiSource } from '@/test/api-session'

import { toReviewCandidate } from './admin-review-api'
import * as client from './admin-review-client'
import { listReviewCandidates, resetMockReviewCandidates } from './admin-review-client'
import { barHeightPercent, formatReviewTime } from './candidate-detail'
import { AdminReviewScreen } from './review-screen'
import type { ReviewCandidate, ReviewCandidateList } from './types'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/admin/review',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}))

// 보내는 중을 붙잡아 보려고 저장을, 선택 값이 빠진 후보 · 실데이터 목록을 그려 보려고 목록을 감싼다(기본은 실제 목)
vi.mock('./admin-review-client', async (importOriginal) => {
  const actual = await importOriginal<typeof client>()
  return {
    ...actual,
    listReviewCandidates: vi.fn(actual.listReviewCandidates),
    saveCandidateDraft: vi.fn(actual.saveCandidateDraft),
    // 실데이터 처리 — 테스트마다 결과를 정한다(요청 갈래는 admin-review-client.test.ts 가 본다)
    fetchCandidateDetail: vi.fn(actual.fetchCandidateDetail),
    createAdvisoryDraft: vi.fn(actual.createAdvisoryDraft),
    saveAdvisoryEdit: vi.fn(actual.saveAdvisoryEdit),
    approveAndPublishAdvisory: vi.fn(actual.approveAndPublishAdvisory),
  }
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
    // 목은 한 주뿐이라 주 이동을 그리지 않는다(#286)
    expect(screen.queryByRole('group', { name: '기준 주' })).toBeNull()
    expect(screen.queryByRole('button', { name: '이전 주' })).toBeNull()
    expect(pendingMenu()).toBe('검토 대기, 남은 후보 4건')
    // 화면이 없는 메뉴(기준 설정)는 링크가 아니다(없는 화면 404 로 가지 않게). 발행 이력은 #220 부터 링크다
    const menu = within(screen.getByRole('navigation', { name: '운영 메뉴' }))
    expect(menu.queryByRole('link', { name: /기준 설정/ })).toBeNull()
    expect(menu.getByText('기준 설정').textContent).toBe('기준 설정 (준비 중)')
    // 메뉴 링크는 목 회원 · 역할 덮어쓰기만 잇는다(목 재현은 잇지 않는다). 지금 화면만 aria-current 다
    const review = menu.getByRole('link', { name: /검토 대기/ })
    const history = menu.getByRole('link', { name: '발행 이력' })
    expect(review.getAttribute('href')).toBe('/admin/review?mock-auth=member&mock-role=operator')
    expect(history.getAttribute('href')).toBe('/admin/history?mock-auth=member&mock-role=operator')
    expect(review.getAttribute('aria-current')).toBe('page')
    expect(history.hasAttribute('aria-current')).toBe(false)
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

  it('선택 값이 빠진 후보(유형 · 증상 보고 비율 · 많이 보고된 증상군 · 예방수칙 · 초안 · 버전 없음)는 그 조각을 그리지 않는다', async () => {
    // 후보 API 모양 — 신호 없이 안내만 있던 자료 부족 동네(안내 없음) · 신호는 있지만 수치가 없는 자료 부족 동네
    const base = { isoWeek: '2025-W47', state: 'waiting', active: true, draftable: false } as const
    const candidates: ReviewCandidate[] = [
      {
        ...base,
        id: '11240670:2025-W47',
        districtCode: '11240670',
        districtName: '가락2동',
        participants: 240,
        signals: [],
        level: 'insufficient',
        insufficientReason: 'unstable',
      },
      {
        ...base,
        id: '11240680:2025-W47',
        kind: 'surge',
        districtCode: '11240680',
        districtName: '가락본동',
        participants: 180,
        signals: ['surge'],
        level: 'insufficient',
        insufficientReason: 'no-baseline',
      },
    ]
    vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({
      status: 'ready',
      isoWeek: '2025-W47',
      finalized: true,
      criteria: { surgeChangePercent: 30, repeatRevisedPercent: 20 },
      candidates,
    })
    await renderScreen()

    const rows = within(table()).getAllByRole('row').slice(1)
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('가락2동'),
      expect.stringContaining('가락본동'),
    ])
    // 유형 칸이 비고(신호 없음), 증상 보고 비율 · 기준선 대비 칸이 없다 — `undefined` · `%` 를 그리지 않는다
    expect(within(rows[0]!).queryByText(/기준선 변화|참여 급증|반복 보고 의심/)).toBeNull()
    expect(within(rows[1]!).getAllByText('참여 급증')).toBeDefined()
    for (const row of rows) {
      expect(row.textContent).not.toMatch(/증상 보고|%|undefined/)
    }
    expect(screen.queryByRole('columnheader', { name: '기준선 대비' })).toBeNull()

    // 상세: 제목에 ` · 유형` 이 없고, 근거 줄에 증상 보고 · 증상군이 없고, 예방수칙 줄이 없고, 초안 칸은 비어 있다
    expect(detailTitle()).toBe('가락2동')
    const detail = screen.getByRole('region', { name: '가락2동' })
    expect(within(detail).getByText('11월 3주 · 참여 240명')).toBeDefined()
    expect(detail.textContent).not.toMatch(/증상 보고 \d|undefined|발열·기침|구토·설사/)
    expect(within(detail).queryByText(/연결된 예방수칙/)).toBeNull()
    expect(draft()).toHaveProperty('value', '')

    await userEvent.setup().click(button('가락본동'))
    expect(detailTitle()).toBe('가락본동 · 참여 급증')
    expect(screen.getByText('11월 3주 · 참여 180명')).toBeDefined()
    expect(screen.queryByText(/연결된 예방수칙/)).toBeNull()
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

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe('고친 초안을 저장했어요.'),
    )
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

    expect(await screen.findByText('○○1동 안내를 발행했어요.')).toBeDefined()
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

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe(
        '이 후보를 보류했어요. 이번 주에는 안내를 내지 않아요.',
      ),
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

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toBe(
        '초안을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
      ),
    )
    expect((draft() as HTMLTextAreaElement).value).toMatch(/ 추가$/)

    await user.click(button('보류'))
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toBe(
        '보류하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
      ),
    )
  })

  it('?mock-admin=conflict 면 최신 내용으로 바꿔 그리고 다시 확인하라고 알린다 — 다시 하면 된다', async () => {
    search = 'mock-admin=conflict'
    await renderScreen()
    const user = userEvent.setup()
    const original = (draft() as HTMLTextAreaElement).value
    await user.type(draft(), ' 추가')
    await user.click(button('수정 저장'))

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe(
        '다른 운영자가 먼저 이 후보를 고쳤어요. 최신 내용으로 바꿔 두었어요. 확인한 뒤 다시 시도해 주세요.',
      ),
    )
    expect((draft() as HTMLTextAreaElement).value).toBe(original)
    await waitFor(() => expect(document.activeElement).toBe(draft()))

    await user.click(button('수정 저장'))
    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe('고친 초안을 저장했어요.'),
    )
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

    expect(await screen.findByText('○○4동 안내를 발행했어요.')).toBeDefined()
    expect(button('기준선 변화 1').getAttribute('aria-pressed')).toBe('true')
    expect(rowNames()).toEqual(['○○1동'])
    expect(detailTitle()).toBe('○○1동 · 기준선 대비 변화')
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole('heading', { level: 2 })),
    )
  })

  it('보이는 목록의 마지막 후보를 발행하면 상세가 없어지고 발행 알림으로 포커스를 옮긴다', async () => {
    await renderScreen()
    const user = userEvent.setup()
    await user.click(button('참여 급증 1'))
    await checkAll(user)
    await user.click(button('승인하고 발행'))

    expect(await screen.findByText('이 유형의 후보가 없어요.')).toBeDefined()
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
    const notice = screen.getByText('○○2동 안내를 발행했어요.')
    await waitFor(() => expect(document.activeElement?.contains(notice)).toBe(true))
  })

  it('충돌인데 후보가 없으면(다른 운영자가 먼저 발행) 목록에서 빼고 알린 뒤 다음 후보를 고른다', async () => {
    vi.mocked(client.saveCandidateDraft).mockResolvedValueOnce({
      status: 'conflict',
      candidate: null,
    })
    await renderScreen()
    await userEvent.setup().click(button('수정 저장'))

    expect(await screen.findByText('다른 운영자가 ○○1동 후보를 먼저 발행했어요.')).toBeDefined()
    expect(rowNames()).toEqual(['○○2동', '○○3동', '○○4동'])
    expect(detailTitle()).toBe('○○2동 · 참여 급증')
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole('heading', { level: 2 })),
    )
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

describe('formatReviewTime 검토 시작 · 경과 (지금 2025-11-20 14:08 KST)', () => {
  const now = Date.parse('2025-11-20T05:08:00Z')

  it('오늘(KST)이면 시각만, 경과는 분 · 시간 분이다(시안)', () => {
    expect(formatReviewTime('2025-11-20T05:02:00Z', now)).toBe('검토 시작 14:02 · 경과 6분')
    expect(formatReviewTime('2025-11-19T15:00:00Z', now)).toBe('검토 시작 00:00 · 경과 14시간 8분')
  })

  it('오늘이 아니면 날짜를 붙이고, 24시간 이상이면 일 · 시간으로 접는다(0 인 뒤 단위는 뺀다)', () => {
    // 어제 23:59 KST — 24시간이 안 돼도 날짜가 다르다
    expect(formatReviewTime('2025-11-19T14:59:00Z', now)).toBe(
      '검토 시작 11월 19일 23:59 · 경과 14시간 9분',
    )
    expect(formatReviewTime('2025-11-17T01:00:00Z', now)).toBe(
      '검토 시작 11월 17일 10:00 · 경과 3일 4시간',
    )
    expect(formatReviewTime('2025-11-17T05:08:00Z', now)).toBe(
      '검토 시작 11월 17일 14:08 · 경과 3일',
    )
  })

  it('읽을 수 없는 시각이면 null 이다', () => {
    expect(formatReviewTime('nope', now)).toBeNull()
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
  const CRITERIA = { surgeChangePercent: 25, repeatRevisedPercent: 15 }

  function candidateOf(overrides: Parameters<typeof candidateItem>[0]): ReviewCandidate {
    const candidate = toReviewCandidate(candidateItem(overrides), CANDIDATE_WEEK)
    if (!candidate) throw new Error('no candidate')
    return candidate
  }

  /** 검토 중(EDITED · 701 · 버전 3) — 기준선 변화 · 참여 급증 두 신호 */
  const open = candidateOf({
    signals: [codeName('BASELINE'), codeName('SURGE')],
    advisory: candidateAdvisory('EDITED', '701', 3),
  })
  /** 대기(안내 없음) — 반복 보고 의심 */
  const waiting = candidateOf({
    districtCode: '11240670',
    districtName: '가락2동',
    signals: [codeName('REPEAT')],
  })
  /** 자료 부족(기준선 없음) — 참여 급증 신호만 있고 수치 키 · 초안 만들기가 없다 */
  const insufficient = (() => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 자료 부족이면 수치 키가 없다
    const { symptomaticCount, symptomRate, baselineRate, baselineDeltaPp, groups, ...rest } =
      candidateItem({
        districtCode: '11240680',
        districtName: '가락본동',
        signals: [codeName('SURGE')],
        level: codeName('INSUFFICIENT'),
        insufficientReason: codeName('NO_BASELINE'),
        draftable: false,
        participantCount: 180,
      })
    const candidate = toReviewCandidate(rest, CANDIDATE_WEEK)
    if (!candidate) throw new Error('no candidate')
    return candidate
  })()
  /** 신호 없이 안내(승인됨)만 있는 동네 — `전체` 에만 든다 */
  const noSignal = candidateOf({
    districtCode: '11240690',
    districtName: '문정1동',
    signals: [],
    level: codeName('NORMAL'),
    advisory: candidateAdvisory('APPROVED', '702', 5),
  })
  /** 안내 상세를 읽은 `open` */
  const detailed: ReviewCandidate = {
    ...open,
    title: '발열·기침 보고가 늘었어요',
    draft: '손을 자주 씻어 주세요.',
  }

  function listOf(
    candidates: readonly ReviewCandidate[] = [open, waiting, insufficient, noSignal],
  ): ReviewCandidateList {
    return { isoWeek: CANDIDATE_WEEK, finalized: true, criteria: CRITERIA, candidates }
  }

  const titleField = () => screen.getByRole('textbox', { name: '안내 제목' })
  const CONFLICT_TEXT =
    '다른 운영자가 먼저 이 후보를 고쳤어요. 최신 내용으로 바꿔 두었어요. 확인한 뒤 다시 시도해 주세요.'

  beforeEach(() => {
    // 앞 테스트가 쓰지 않고 남긴 응답이 다음 테스트로 새지 않게 한다(감싼 실제 함수로 되돌린다)
    for (const fn of [
      client.listReviewCandidates,
      client.fetchCandidateDetail,
      client.createAdvisoryDraft,
      client.saveAdvisoryEdit,
      client.approveAndPublishAdvisory,
    ]) {
      vi.mocked(fn).mockReset()
    }
  })

  /** 실데이터로 그리고 목록 · 첫 후보의 안내 상세까지 읽는다 */
  async function renderApi({
    list = listOf(),
    detail = detailed,
  }: { list?: ReviewCandidateList; detail?: ReviewCandidate | null } = {}) {
    selectApiSource()
    vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({ status: 'ready', ...list })
    vi.mocked(client.fetchCandidateDetail).mockResolvedValueOnce(detail)
    const view = await renderScreen()
    await waitFor(() => expect(screen.queryByText('안내문을 불러오고 있어요')).toBeNull())
    return view
  }

  it('후보 API 를 읽어 그린다 — 기준 주 · 후보 기준은 응답 값, 유형별 수는 신호로 세고, 보류는 없다', async () => {
    search = 'mock-admin=empty'
    selectApiSource()
    let resolve: (result: client.CandidateListResult) => void = () => {}
    vi.mocked(client.listReviewCandidates).mockImplementationOnce(
      () => new Promise((done) => (resolve = done)),
    )
    let resolveDetail: (candidate: ReviewCandidate | null) => void = () => {}
    vi.mocked(client.fetchCandidateDetail).mockImplementationOnce(
      () => new Promise((done) => (resolveDetail = done)),
    )
    await renderScreen()
    expect(screen.getByText('검토 후보를 불러오고 있어요')).toBeDefined()
    expect(screen.queryByRole('table')).toBeNull()

    act(() => resolve({ status: 'ready', ...listOf() }))
    await act(async () => {})
    expect(client.listReviewCandidates).toHaveBeenCalledWith('api', { scenario: 'empty' })
    expect(screen.getByText('11월 2주')).toBeDefined()
    expect(
      screen.getByText(
        // 실데이터는 서버 기본 주도 `이번 주` 가 아니라 그 주 이름이다(#286)
        '11월 2주 후보 4건 · 후보 기준: 표본 100명 이상에서 기준선 대비 증가, 참여 급증(전주 대비 +25% 이상), 반복 보고 의심(수정된 보고 15% 이상)',
      ),
    ).toBeDefined()
    expect(pendingMenu()).toBe('검토 대기, 남은 후보 4건')
    for (const name of ['전체 4', '기준선 변화 1', '참여 급증 2', '반복 보고 의심 1']) {
      expect(button(name)).toBeDefined()
    }
    expect(rowNames()).toEqual(['가락1동', '가락2동', '가락본동', '문정1동'])

    // 첫 후보의 안내 상세를 읽는 동안은 제목 · 본문 칸 대신 불러오는 중이고 처리 버튼이 꺼진다
    expect(screen.getByText('안내문을 불러오고 있어요')).toBeDefined()
    expect(button('승인하고 발행').hasAttribute('disabled')).toBe(true)
    act(() => resolveDetail(detailed))
    await waitFor(() => expect(titleField()).toHaveProperty('value', '발열·기침 보고가 늘었어요'))
    expect(client.fetchCandidateDetail).toHaveBeenCalledWith(open)
    expect(draft()).toHaveProperty('value', '손을 자주 씻어 주세요.')
    expect(screen.getByText('운영자 수정 가능')).toBeDefined()
    expect(button('수정 저장').hasAttribute('disabled')).toBe(false)
    expect(screen.queryByRole('button', { name: '보류' })).toBeNull()

    // 유형 필터는 신호로 거른다 — 두 신호를 가진 후보는 두 유형에 다 든다
    await userEvent.setup().click(button('참여 급증 2'))
    expect(rowNames()).toEqual(['가락1동', '가락본동'])
  })

  it('목록을 읽지 못하면 빨강 상자 + 다시 시도다 — 다시 실패하면 다시 시도로, 읽으면 상세 제목으로 포커스를 옮긴다', async () => {
    selectApiSource()
    vi.mocked(client.listReviewCandidates)
      .mockRejectedValueOnce(new Error('network'))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ status: 'ready', ...listOf([waiting]) })
    await renderScreen()

    expect(screen.getByRole('alert').textContent).toContain(
      '검토 후보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(pendingMenu()).toBe('검토 대기')
    const user = userEvent.setup()
    await user.click(button('다시 시도'))
    await waitFor(() => expect(document.activeElement).toBe(button('다시 시도')))
    await user.click(button('다시 시도'))
    expect(await screen.findByRole('table')).toBeDefined()
    expect(rowNames()).toEqual(['가락2동'])
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole('heading', { level: 2 })),
    )
  })

  it('목록이 비면 후보가 없다고 알린다', async () => {
    await renderApi({ list: listOf([]) })
    expect(screen.getByText('11월 2주 검토할 후보가 없어요.')).toBeDefined()
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
    expect(client.fetchCandidateDetail).not.toHaveBeenCalled()
  })

  it('안내 상세를 읽지 못하면 다시 시도할 수 있고, 그사이 발행됐으면 목록에서 뺀다', async () => {
    selectApiSource()
    vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({ status: 'ready', ...listOf() })
    vi.mocked(client.fetchCandidateDetail)
      .mockRejectedValueOnce(new Error('network'))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(detailed)
    await renderScreen()

    expect(
      await screen.findByText('안내문을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.'),
    ).toBeDefined()
    expect(screen.queryByRole('textbox', { name: '안내 제목' })).toBeNull()
    expect(button('승인하고 발행').hasAttribute('disabled')).toBe(true)
    const user = userEvent.setup()
    await user.click(button('다시 시도'))
    await waitFor(() => expect(document.activeElement).toBe(button('다시 시도')))
    await user.click(button('다시 시도'))
    await waitFor(() => expect(titleField()).toHaveProperty('value', '발열·기침 보고가 늘었어요'))
    await waitFor(() => expect(document.activeElement).toBe(titleField()))

    // 문정1동의 안내가 그사이 발행됐다
    vi.mocked(client.fetchCandidateDetail).mockResolvedValueOnce(null)
    await user.click(button('문정1동'))
    expect(await screen.findByText('다른 운영자가 문정1동 후보를 먼저 발행했어요.')).toBeDefined()
    expect(rowNames()).toEqual(['가락1동', '가락2동', '가락본동'])
  })

  it('늦게 온 안내 상세는 버린다 — 그사이 다른 후보를 고르면 그 후보의 상세 · 목록이 그대로다', async () => {
    selectApiSource()
    vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({ status: 'ready', ...listOf() })
    let resolveFirst: (candidate: ReviewCandidate | null) => void = () => {}
    vi.mocked(client.fetchCandidateDetail)
      .mockImplementationOnce(() => new Promise((done) => (resolveFirst = done)))
      .mockResolvedValueOnce({ ...noSignal, title: '문정1동 안내', draft: '문정1동 본문' })
    await renderScreen()
    await userEvent.setup().click(button('문정1동'))
    await waitFor(() => expect(titleField()).toHaveProperty('value', '문정1동 안내'))

    // 가락1동이 그사이 발행됐다는 늦은 응답
    act(() => resolveFirst(null))
    await act(async () => {})
    expect(rowNames()).toEqual(['가락1동', '가락2동', '가락본동', '문정1동'])
    expect(detailTitle()).toBe('문정1동')
    expect(titleField()).toHaveProperty('value', '문정1동 안내')
    expect(screen.queryByText(/먼저 발행했어요/)).toBeNull()
  })

  it('자료 부족 후보는 수치 · 상태색 없이 자료 부족이고, 초안 만들기를 막는다', async () => {
    await renderApi()
    const row = within(table()).getAllByRole('row')[3]
    expect(row?.textContent).toContain('가락본동')
    expect(row?.textContent).toContain('자료 부족')
    expect(row?.textContent).not.toMatch(/%|undefined/)

    await userEvent.setup().click(button('가락본동'))
    expect(detailTitle()).toBe('가락본동 · 참여 급증')
    expect(screen.getByText('11월 2주 · 참여 180명')).toBeDefined()
    expect(
      screen.getByText(
        '자료 부족 후보예요. 증상 보고 비율을 셀 수 없어 초안을 만들거나 발행할 수 없어요.',
      ),
    ).toBeDefined()
    expect(button('초안 만들기').hasAttribute('disabled')).toBe(true)
    expect(titleField().hasAttribute('disabled')).toBe(true)
    expect(draft().hasAttribute('disabled')).toBe(true)
    // 처리할 안내가 없어 상세를 읽지 않는다(첫 후보 한 번뿐)
    expect(client.fetchCandidateDetail).toHaveBeenCalledTimes(1)
  })

  it('안내가 없는 후보는 초안 만들기다 — 제목 · 본문이 필수이고, 만들면 수정 저장 · 승인하고 발행으로 바뀐다', async () => {
    await renderApi()
    const user = userEvent.setup()
    await user.click(button('가락2동'))
    expect(titleField()).toHaveProperty('value', '')
    expect(draft()).toHaveProperty('value', '')
    expect(screen.getByText('운영자 작성')).toBeDefined()
    expect(screen.queryByRole('button', { name: '수정 저장' })).toBeNull()
    expect(screen.queryByRole('button', { name: '승인하고 발행' })).toBeNull()

    await user.click(button('초안 만들기'))
    expect(screen.getByText('제목을 입력해 주세요.')).toBeDefined()
    expect(screen.getByText('안내문 초안을 입력해 주세요.')).toBeDefined()
    expect(client.createAdvisoryDraft).not.toHaveBeenCalled()

    const created: ReviewCandidate = {
      ...waiting,
      state: 'reviewing',
      advisoryId: '703',
      advisoryStatus: 'DRAFT',
      version: 0,
      title: '구토·설사 보고가 늘었어요',
      draft: '음식은 충분히 익혀 드세요.',
    }
    vi.mocked(client.createAdvisoryDraft).mockResolvedValueOnce({
      status: 'ok',
      candidate: created,
    })
    await user.type(titleField(), '구토·설사 보고가 늘었어요')
    await user.type(draft(), '음식은 충분히 익혀 드세요.')
    await user.click(button('초안 만들기'))

    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('초안을 만들었어요.'))
    expect(client.createAdvisoryDraft).toHaveBeenCalledWith(waiting, {
      title: '구토·설사 보고가 늘었어요',
      body: '음식은 충분히 익혀 드세요.',
    })
    expect(within(table()).getAllByRole('row')[2]?.textContent).toContain('검토 중')
    expect(button('수정 저장')).toBeDefined()
    expect(button('승인하고 발행')).toBeDefined()
    expect(screen.queryByRole('button', { name: '초안 만들기' })).toBeNull()
    expect(titleField()).toHaveProperty('value', '구토·설사 보고가 늘었어요')
  })

  it('초안 만들기 결과 갈래: 요청하지 않음(rejected) · 검증 거부 · 실패를 알리고 입력은 그대로다', async () => {
    await renderApi()
    const user = userEvent.setup()
    await user.click(button('가락2동'))
    await user.type(titleField(), '제목')
    await user.type(draft(), '본문')

    const cases: [() => void, string][] = [
      [
        () =>
          vi
            .mocked(client.createAdvisoryDraft)
            .mockResolvedValueOnce({ status: 'rejected', reason: 'has-advisory' }),
        '이 후보에는 이미 검토 중인 안내가 있어요. 새로고침한 뒤 다시 확인해 주세요.',
      ],
      [
        () =>
          vi
            .mocked(client.createAdvisoryDraft)
            .mockResolvedValueOnce({ status: 'rejected', reason: 'not-draftable' }),
        '이 후보로는 초안을 만들 수 없어요. 자료가 부족하거나 집계가 아직 마감되지 않았어요.',
      ],
      [
        () => vi.mocked(client.createAdvisoryDraft).mockResolvedValueOnce({ status: 'invalid' }),
        '초안을 만들지 못했어요. 입력한 내용을 확인해 주세요.',
      ],
      [
        () => vi.mocked(client.createAdvisoryDraft).mockRejectedValueOnce(new Error('network')),
        '초안을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.',
      ],
    ]
    for (const [arrange, text] of cases) {
      arrange()
      await user.click(button('초안 만들기'))
      await waitFor(() => expect(screen.getByText(text)).toBeDefined())
      expect(titleField()).toHaveProperty('value', '제목')
      expect(draft()).toHaveProperty('value', '본문')
    }
  })

  /** 가락2동(안내 없음)을 골라 제목 · 본문을 쓰고 초안 만들기를 누른다 */
  async function createOnWaiting(result: client.CreateDraftResult) {
    await renderApi()
    const user = userEvent.setup()
    await user.click(button('가락2동'))
    await user.type(titleField(), '제목')
    await user.type(draft(), '본문')
    vi.mocked(client.createAdvisoryDraft).mockResolvedValueOnce(result)
    await user.click(button('초안 만들기'))
  }

  it('초안 만들기 충돌: 다시 읽은 후보가 막혔으면(마감 전) 꺼진 칸 대신 상세 제목으로 포커스를 옮긴다', async () => {
    const closed: ReviewCandidate = { ...waiting, draftable: false }
    await createOnWaiting({ status: 'conflict', list: listOf([open, closed]), candidate: closed })

    expect(await screen.findByText(CONFLICT_TEXT)).toBeDefined()
    expect(button('초안 만들기').hasAttribute('disabled')).toBe(true)
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole('heading', { level: 2 })),
    )
  })

  it('초안 만들기 충돌 뒤 그 후보가 목록에 없으면 발행됐다고 하지 않고 중립 문구다', async () => {
    vi.mocked(client.fetchCandidateDetail).mockResolvedValueOnce(detailed)
    await createOnWaiting({ status: 'conflict', list: listOf([open]), candidate: null })

    expect(
      await screen.findByText('이 후보는 더 이상 검토 대상이 아니에요. 목록을 다시 확인해 주세요.'),
    ).toBeDefined()
    expect(rowNames()).toEqual(['가락1동'])
  })

  it('초안 만들기는 됐는데 다시 읽지 못하면(unrefreshed ok) 만들었다고 알리고 목록을 다시 읽게 한다', async () => {
    await createOnWaiting({ status: 'unrefreshed', outcome: 'ok' })

    expect(await screen.findByText('초안을 만들었어요.')).toBeDefined()
    expect(
      screen.getByText('검토 후보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.'),
    ).toBeDefined()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('수정 저장: 고친 제목 · 본문을 보내고, 충돌이면 다시 읽은 목록 · 후보로 바꿔 그린다', async () => {
    await renderApi()
    const user = userEvent.setup()
    await user.clear(titleField())
    await user.type(titleField(), '고친 제목')

    const saved: ReviewCandidate = { ...detailed, title: '고친 제목', version: 4 }
    vi.mocked(client.saveAdvisoryEdit).mockResolvedValueOnce({ status: 'ok', candidate: saved })
    await user.click(button('수정 저장'))
    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe('고친 초안을 저장했어요.'),
    )
    expect(client.saveAdvisoryEdit).toHaveBeenCalledWith(detailed, {
      title: '고친 제목',
      body: '손을 자주 씻어 주세요.',
    })

    // 다른 운영자가 먼저 고쳤다 — 다시 읽은 목록(그사이 다른 후보가 빠짐)과 그 후보의 최신 본문
    const latest: ReviewCandidate = { ...saved, draft: '다른 운영자가 고친 본문', version: 5 }
    vi.mocked(client.saveAdvisoryEdit).mockResolvedValueOnce({
      status: 'conflict',
      list: listOf([latest, waiting]),
      candidate: latest,
    })
    await user.type(draft(), ' 추가')
    await user.click(button('수정 저장'))
    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe(
        '다른 운영자가 먼저 이 후보를 고쳤어요. 최신 내용으로 바꿔 두었어요. 확인한 뒤 다시 시도해 주세요.',
      ),
    )
    expect(client.saveAdvisoryEdit).toHaveBeenLastCalledWith(saved, {
      title: '고친 제목',
      body: '손을 자주 씻어 주세요. 추가',
    })
    expect(rowNames()).toEqual(['가락1동', '가락2동'])
    expect(draft()).toHaveProperty('value', '다른 운영자가 고친 본문')
    await waitFor(() => expect(document.activeElement).toBe(draft()))
  })

  it('승인하고 발행: 고치지 않았으면 dirty 없이 보내고, 발행하면 목록에서 빠지고 다음 후보를 고른다', async () => {
    await renderApi()
    const user = userEvent.setup()
    await checkAll(user)
    vi.mocked(client.approveAndPublishAdvisory).mockResolvedValueOnce({ status: 'published' })
    await user.click(button('승인하고 발행'))

    expect(await screen.findByText('가락1동 안내를 발행했어요.')).toBeDefined()
    expect(client.approveAndPublishAdvisory).toHaveBeenCalledWith(detailed, {
      title: '발열·기침 보고가 늘었어요',
      body: '손을 자주 씻어 주세요.',
      dirty: false,
    })
    expect(rowNames()).toEqual(['가락2동', '가락본동', '문정1동'])
    expect(detailTitle()).toBe('가락2동 · 반복 보고 의심')
    expect(pendingMenu()).toBe('검토 대기, 남은 후보 3건')
  })

  it.each([
    [
      '승인됨으로 남음(같은 동네 · 주에 발행된 안내) — 그 후보가 빠진 목록으로 바꾸고 목록 위에 알린다',
      { status: 'approvedNotPublished', list: listOf([waiting]), candidate: null },
      '가락1동 안내는 승인됨으로 남았어요. 같은 동네 · 같은 주에 이미 발행된 안내가 있어 발행하지 못했어요.',
    ],
    [
      '승인됨으로 남음인데 다시 읽지 못함 — 결과를 알리고 목록을 다시 읽게 한다',
      { status: 'unrefreshed', outcome: 'approvedNotPublished', step: 'publish' },
      '가락1동 안내는 승인됨으로 남았어요. 같은 동네 · 같은 주에 이미 발행된 안내가 있어 발행하지 못했어요.',
    ],
    [
      '충돌 — 다시 읽은 목록 · 후보로 바꾼다',
      {
        status: 'conflict',
        step: 'publish',
        list: listOf([detailed, waiting]),
        candidate: detailed,
      },
      '다른 운영자가 먼저 이 후보를 고쳤어요. 최신 내용으로 바꿔 두었어요. 확인한 뒤 다시 시도해 주세요.',
    ],
    [
      '수정 저장 단계 실패',
      { status: 'failed', step: 'save', candidate: detailed, error: new Error('x') },
      '고친 내용을 저장하지 못해 발행을 멈췄어요. 잠시 뒤 다시 시도해 주세요.',
    ],
  ] as const)('승인하고 발행 결과: %s', async (_, result, text) => {
    await renderApi()
    const user = userEvent.setup()
    await user.type(draft(), ' 추가')
    await checkAll(user)
    vi.mocked(client.approveAndPublishAdvisory).mockResolvedValueOnce(result)
    await user.click(button('승인하고 발행'))

    expect(await screen.findByText(text)).toBeDefined()
    expect(client.approveAndPublishAdvisory).toHaveBeenCalledWith(
      detailed,
      expect.objectContaining({ dirty: true }),
    )
  })

  it.each([
    [
      'approve 에서 failed',
      'failed',
      { ...detailed, version: 4, draft: '손을 자주 씻어 주세요. 추가' },
      '고친 내용은 저장했어요. 승인하지 못해 발행을 멈췄어요. 잠시 뒤 다시 시도해 주세요.',
    ],
    [
      'publish 에서 failed',
      'failed',
      {
        ...detailed,
        advisoryStatus: 'APPROVED',
        version: 5,
        draft: '손을 자주 씻어 주세요. 추가',
      },
      '승인은 됐지만 발행하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    ],
    [
      'approve 에서 invalid',
      'invalid',
      { ...detailed, version: 4, draft: '손을 자주 씻어 주세요. 추가' },
      '고친 내용은 저장했어요. 발행하지 못했어요. 입력한 내용을 확인해 주세요.',
    ],
  ] as const)(
    '승인하고 발행이 %s 로 멈추면 처리된 단계까지 반영한 후보로 바꾸고, 다시 누르면 고친 것 없이 그 후보로 보낸다',
    async (name, status, stopped, text) => {
      await renderApi()
      const user = userEvent.setup()
      await user.type(draft(), ' 추가')
      await checkAll(user)
      const step = name.startsWith('approve') ? 'approve' : 'publish'
      vi.mocked(client.approveAndPublishAdvisory)
        .mockResolvedValueOnce(
          status === 'failed'
            ? { status, step, candidate: stopped, error: new Error('x') }
            : { status, step, candidate: stopped },
        )
        .mockResolvedValueOnce({ status: 'published' })
      await user.click(button('승인하고 발행'))
      expect(await screen.findByText(text)).toBeDefined()

      await user.click(button('승인하고 발행'))
      expect(await screen.findByText('가락1동 안내를 발행했어요.')).toBeDefined()
      expect(client.approveAndPublishAdvisory).toHaveBeenLastCalledWith(stopped, {
        title: '발열·기침 보고가 늘었어요',
        body: '손을 자주 씻어 주세요. 추가',
        dirty: false,
      })
    },
  )

  it('처리 뒤 목록을 다시 읽지 못하면(unrefreshed) 결과를 알리고 다시 시도로 옮긴다 — 다시 읽으면 그 후보를 고른다', async () => {
    await renderApi()
    const user = userEvent.setup()
    await checkAll(user)
    vi.mocked(client.approveAndPublishAdvisory).mockResolvedValueOnce({
      status: 'unrefreshed',
      outcome: 'conflict',
      step: 'approve',
    })
    await user.click(button('승인하고 발행'))

    expect(await screen.findByText('다른 운영자가 먼저 이 후보를 바꿨어요.')).toBeDefined()
    expect(
      screen.getByText('검토 후보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.'),
    ).toBeDefined()
    expect(screen.queryByRole('table')).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(button('다시 시도')))

    vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({
      status: 'ready',
      ...listOf([waiting, detailed]),
    })
    await user.click(button('다시 시도'))
    await waitFor(() => expect(detailTitle()).toBe('가락1동 · 기준선 대비 변화'))
    expect(screen.queryByText('다른 운영자가 먼저 이 후보를 바꿨어요.')).toBeNull()
  })

  describe('다른 주 고르기 (#286 — 지금은 2025-11-20 KST, 이번 주 2025-W47)', () => {
    const weekGroup = () => screen.getByRole('group', { name: '기준 주' })
    const previousWeek = () => within(weekGroup()).getByRole('button', { name: '이전 주' })
    const nextWeek = () => within(weekGroup()).getByRole('button', { name: '다음 주' })
    const listCall = (index: number) => vi.mocked(client.listReviewCandidates).mock.calls[index]

    /** 다음 목록 요청을 붙잡는다 */
    function holdNextList() {
      let resolve: (result: client.CandidateListResult) => void = () => {}
      let reject: (error: unknown) => void = () => {}
      vi.mocked(client.listReviewCandidates).mockImplementationOnce(
        () =>
          new Promise((done, fail) => {
            resolve = done
            reject = fail
          }),
      )
      return {
        resolve: (value: client.CandidateListResult) => resolve(value),
        reject: (error: unknown) => reject(error),
      }
    }

    it('첫 진입은 주 없이 읽고(서버 기본), 이전 주로 옮기면 그 주를 읽고 선택 · 필터 · 상세를 처음부터 한다', async () => {
      await renderApi()
      expect(listCall(0)).toEqual(['api', { scenario: null }])
      expect(listCall(0)?.[1]).not.toHaveProperty('week')
      expect(within(weekGroup()).getByText('11월 2주')).toBeDefined()
      expect(screen.getByText(/^11월 2주 후보 4건 · /)).toBeDefined()

      const user = userEvent.setup()
      await user.click(button('반복 보고 의심 1'))
      expect(detailTitle()).toBe('가락2동 · 반복 보고 의심')

      const held = holdNextList()
      await user.click(previousWeek())
      expect(listCall(1)).toEqual(['api', { scenario: null, week: '2025-W45' }])
      // 읽는 동안은 옮겨 갈 주를 보이고 목록 · 상세가 없다. 누른 버튼에 포커스가 남는다
      expect(within(weekGroup()).getByText('11월 1주')).toBeDefined()
      expect(screen.getByText('검토 후보를 불러오고 있어요')).toBeDefined()
      expect(screen.queryByRole('table')).toBeNull()
      expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
      expect(document.activeElement).toBe(previousWeek())

      vi.mocked(client.fetchCandidateDetail).mockResolvedValueOnce(detailed)
      act(() => held.resolve({ status: 'ready', ...listOf([open, waiting]), isoWeek: '2025-W45' }))
      await waitFor(() => expect(detailTitle()).toBe('가락1동 · 기준선 대비 변화'))
      // 설명 줄은 옮긴 주의 이름이다. 필터는 전체로 돌아간다
      expect(screen.getByText(/^11월 1주 후보 2건 · /)).toBeDefined()
      expect(button('전체 2').getAttribute('aria-pressed')).toBe('true')

      // 서버 기본 주로 돌아와도 `이번 주` 가 아니라 그 주 이름이다
      vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({
        status: 'ready',
        ...listOf([]),
      })
      await user.click(nextWeek())
      await waitFor(() => expect(screen.getByText(/^11월 2주 후보 0건 · /)).toBeDefined())
      expect(listCall(2)).toEqual(['api', { scenario: null, week: '2025-W46' }])
      expect(screen.getByText('11월 2주 검토할 후보가 없어요.')).toBeDefined()
    })

    it('읽는 중에 또 옮기면 마지막에 고른 주만 그린다 — 앞 주의 응답이 늦게 와도 버린다', async () => {
      await renderApi()
      const user = userEvent.setup()
      const first = holdNextList()
      await user.click(previousWeek())
      const second = holdNextList()
      await user.click(previousWeek())
      expect(listCall(1)?.[1]).toEqual({ scenario: null, week: '2025-W45' })
      expect(listCall(2)?.[1]).toEqual({ scenario: null, week: '2025-W44' })
      expect(within(weekGroup()).getByText('10월 5주')).toBeDefined()

      // 나중에 고른 주(W44)가 먼저 오고, 앞 주(W45)가 늦게 온다
      act(() => second.resolve({ status: 'ready', ...listOf([waiting]), isoWeek: '2025-W44' }))
      await waitFor(() => expect(rowNames()).toEqual(['가락2동']))
      act(() =>
        first.resolve({ status: 'ready', ...listOf([open, noSignal]), isoWeek: '2025-W45' }),
      )
      await act(async () => {})
      expect(within(weekGroup()).getByText('10월 5주')).toBeDefined()
      expect(screen.getByText(/^10월 5주 후보 1건 · /)).toBeDefined()
      expect(rowNames()).toEqual(['가락2동'])
    })

    it('옮긴 주에서 처리 뒤 다시 읽지 못하면(unrefreshed) 다시 시도는 옮긴 주를 다시 읽는다', async () => {
      await renderApi()
      const user = userEvent.setup()
      vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({
        status: 'ready',
        ...listOf([open]),
        isoWeek: '2025-W45',
      })
      vi.mocked(client.fetchCandidateDetail).mockResolvedValueOnce(detailed)
      await user.click(previousWeek())
      await waitFor(() => expect(titleField()).toHaveProperty('value', '발열·기침 보고가 늘었어요'))

      vi.mocked(client.saveAdvisoryEdit).mockResolvedValueOnce({
        status: 'unrefreshed',
        outcome: 'conflict',
      })
      await user.type(draft(), ' 추가')
      await user.click(button('수정 저장'))
      expect(await screen.findByText('다른 운영자가 먼저 이 후보를 바꿨어요.')).toBeDefined()
      // 머리줄은 옮긴 주에 남는다
      expect(within(weekGroup()).getByText('11월 1주')).toBeDefined()

      vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({
        status: 'ready',
        ...listOf([waiting]),
        isoWeek: '2025-W45',
      })
      await user.click(button('다시 시도'))
      await waitFor(() => expect(rowNames()).toEqual(['가락2동']))
      expect(listCall(2)).toEqual(['api', { scenario: null, week: '2025-W45' }])
    })

    it('현재 주 − 1 에서는 다음 주로 갈 수 있고, 현재 주에서는 다음 주가 막힌다(미래 주)', async () => {
      await renderApi()
      expect(nextWeek().hasAttribute('aria-disabled')).toBe(false)

      vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({
        status: 'ready',
        ...listOf([]),
        isoWeek: '2025-W47',
      })
      const user = userEvent.setup()
      await user.click(nextWeek())
      await waitFor(() => expect(screen.getByText('11월 3주 검토할 후보가 없어요.')).toBeDefined())
      expect(listCall(1)?.[1]).toEqual({ scenario: null, week: '2025-W47' })
      expect(nextWeek().getAttribute('aria-disabled')).toBe('true')
      expect(document.activeElement).toBe(nextWeek())
      await user.click(nextWeek())
      expect(client.listReviewCandidates).toHaveBeenCalledTimes(2)
      expect(previousWeek().hasAttribute('aria-disabled')).toBe(false)
    })

    it('51주 전(2024-W48)에서는 이전 주가 막힌다', async () => {
      await renderApi({ list: { ...listOf([]), isoWeek: '2024-W49' } })
      expect(previousWeek().hasAttribute('aria-disabled')).toBe(false)

      vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({
        status: 'ready',
        ...listOf([]),
        isoWeek: '2024-W48',
      })
      const user = userEvent.setup()
      await user.click(previousWeek())
      await waitFor(() => expect(within(weekGroup()).getByText('11월 4주')).toBeDefined())
      expect(listCall(1)?.[1]).toEqual({ scenario: null, week: '2024-W48' })
      await waitFor(() => expect(previousWeek().getAttribute('aria-disabled')).toBe('true'))
      await user.click(previousWeek())
      expect(client.listReviewCandidates).toHaveBeenCalledTimes(2)
    })

    it('고른 주를 서버가 받지 않으면(009 · 104) 다시 시도 대신 다른 주를 고르라고 알린다', async () => {
      await renderApi()
      const user = userEvent.setup()
      vi.mocked(client.listReviewCandidates).mockRejectedValueOnce(
        new ApiError({ status: 400, code: 'ADVISORY_009', message: '거절' }),
      )
      await user.click(previousWeek())
      expect((await screen.findByRole('alert')).textContent).toBe(
        '이 주의 검토 후보는 볼 수 없어요. 이번 주부터 51주 전까지만 볼 수 있어요. 위의 이전 주 · 다음 주로 다른 주를 골라 주세요.',
      )
      expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
      expect(within(weekGroup()).getByText('11월 1주')).toBeDefined()

      vi.mocked(client.listReviewCandidates).mockRejectedValueOnce(
        new ApiError({ status: 400, code: 'ADVISORY_104', message: '거절' }),
      )
      await user.click(previousWeek())
      await waitFor(() =>
        expect(screen.getByRole('alert').textContent).toBe(
          '기준 주를 읽지 못해 검토 후보를 불러오지 못했어요. 위의 이전 주 · 다음 주로 다른 주를 골라 주세요.',
        ),
      )

      // 그 밖의 실패는 지금처럼 다시 시도다 — 같은 주를 다시 읽는다
      vi.mocked(client.listReviewCandidates).mockRejectedValueOnce(new Error('network'))
      await user.click(nextWeek())
      expect(await screen.findByRole('button', { name: '다시 시도' })).toBeDefined()
      vi.mocked(client.listReviewCandidates).mockResolvedValueOnce({
        status: 'ready',
        ...listOf([waiting]),
        isoWeek: '2025-W45',
      })
      await user.click(button('다시 시도'))
      await waitFor(() => expect(rowNames()).toEqual(['가락2동']))
      expect(listCall(4)?.[1]).toEqual({ scenario: null, week: '2025-W45' })
    })

    it('처리 중에는 주를 옮기지 않는다', async () => {
      await renderApi()
      const user = userEvent.setup()
      let resolveSave: (result: client.AdvisoryWriteResult) => void = () => {}
      vi.mocked(client.saveAdvisoryEdit).mockImplementationOnce(
        () => new Promise((done) => (resolveSave = done)),
      )
      await user.type(draft(), ' 추가')
      await user.click(button('수정 저장'))
      expect(previousWeek().getAttribute('aria-disabled')).toBe('true')
      expect(nextWeek().getAttribute('aria-disabled')).toBe('true')
      await user.click(previousWeek())
      expect(client.listReviewCandidates).toHaveBeenCalledTimes(1)

      act(() => resolveSave({ status: 'ok', candidate: { ...detailed, version: 4 } }))
      await waitFor(() => expect(previousWeek().hasAttribute('aria-disabled')).toBe(false))
    })
  })

  it('검토 시작은 안내를 만든 시각이다 — 오늘이면 시안처럼 시각 · 경과, 오늘이 아니면 날짜를 붙이고 경과를 일로 접는다', async () => {
    await renderApi({ detail: { ...detailed, reviewStartedAt: '2025-11-20T04:30:00Z' } })
    expect(screen.getByText('검토 시작 13:30 · 경과 38분')).toBeDefined()
  })

  it('3일 전에 만든 안내는 날짜와 일 단위 경과로 보인다', async () => {
    await renderApi({ detail: { ...detailed, reviewStartedAt: '2025-11-17T01:00:00Z' } })
    expect(screen.getByText('검토 시작 11월 17일 10:00 · 경과 3일 4시간')).toBeDefined()
  })
})
