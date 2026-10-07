import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { loginWithEmail, logout, resetMockSession } from '@/features/auth/auth-client'
import { NOTICE_EXAMPLE_DISTRICTS } from '@/features/notice/mock'

import {
  correctAdvisory,
  kstDate,
  listHistory,
  resetMockHistory,
  retractAdvisory,
  summarizeHistory,
} from './admin-history-client'
import {
  countPendingCandidates,
  holdCandidate,
  listReviewCandidates,
  publishCandidate,
  resetMockReviewCandidates,
  saveCandidateDraft,
} from './admin-review-client'
import { createMockHistory } from './mock'
import type { HistoryEntry } from './types'

async function entries() {
  const result = await listHistory('mock')
  if (result.status !== 'ready') throw new Error('not ready')
  return result.entries
}

/** 1단계에 없는 선택 값을 뺀다 */
function omit(value: HistoryEntry, ...keys: (keyof HistoryEntry)[]): HistoryEntry {
  return Object.fromEntries(
    Object.entries(value).filter(([key]) => !keys.includes(key as keyof HistoryEntry)),
  ) as HistoryEntry
}

function entry(overrides: Partial<HistoryEntry>): HistoryEntry {
  return { ...createMockHistory()[0]!, ...overrides }
}

beforeEach(() => {
  resetMockSession()
  resetMockHistory()
  resetMockReviewCandidates()
  // 2025-11-20 14:08 KST
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2025-11-20T05:08:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('발행 이력 클라이언트', () => {
  it('실데이터는 요청하지 않고 unavailable 이다 (목록 · 정정 · 철회 모두)', async () => {
    expect(await listHistory('api', { scenario: 'fail' })).toEqual({ status: 'unavailable' })
    expect(
      await correctAdvisory('advisory-1', { body: '본문', reason: '사유', version: 4 }, 'api'),
    ).toEqual({ status: 'unavailable' })
    expect(await retractAdvisory('advisory-1', { reason: '사유', version: 4 }, 'api')).toEqual({
      status: 'unavailable',
    })
  })

  it('목은 시안의 지난 주 행이고 최근 것부터다. 동은 동네 안내 목이 아는 예시 동네다', async () => {
    const list = await entries()
    expect(list.map((item) => `${item.districtName} ${item.isoWeek} ${item.outcome}`)).toEqual([
      '○○1동 2025-W46 published',
      '○○5동 2025-W46 held',
      '○○2동 2025-W45 published',
      '○○4동 2025-W43 published',
    ])
    const known = NOTICE_EXAMPLE_DISTRICTS.map((district) => district.code)
    expect(list.every((item) => known.includes(item.districtCode))).toBe(true)
  })

  it('요약은 목록에서 계산한다 — 시안 값(평균 7분)과 수정 후 발행 비율', async () => {
    expect(summarizeHistory(await entries())).toEqual({
      published: 3,
      held: 1,
      averageReviewMinutes: 7,
      editedRate: 33,
    })
  })

  it('요약: 검토 시간이 없으면 평균은 null, 발행이 없으면 비율은 null, 정정 발행은 세지 않는다', () => {
    const noMinutes = omit(entry({ edited: false }), 'reviewMinutes')
    expect(summarizeHistory([noMinutes, { ...noMinutes, id: 'x', outcome: 'held' }])).toEqual({
      published: 1,
      held: 1,
      averageReviewMinutes: null,
      editedRate: 0,
    })
    expect(summarizeHistory([{ ...noMinutes, outcome: 'held' }]).editedRate).toBeNull()
    const correction = entry({
      id: 'c',
      correction: { previousId: 'advisory-1', correctedOn: '2025-11-20', reason: '사유' },
    })
    expect(summarizeHistory([entry({ outcome: 'corrected' }), correction]).published).toBe(1)
  })

  it('정정 발행: 이전 안내는 정정됨(사유)이 되고, 고친 본문이 정정일 · 사유를 가진 새 안내로 발행된다', async () => {
    const result = await correctAdvisory(
      'advisory-1',
      { body: '고친 본문', reason: '참여자 수를 바로잡았어요.', version: 4 },
      'mock',
    )
    if (result.status !== 'ok') throw new Error('not ok')
    const [fresh, previous] = result.entries
    expect(result.selectedId).toBe(fresh?.id)
    expect(fresh).toMatchObject({
      districtName: '○○1동',
      isoWeek: '2025-W46',
      outcome: 'published',
      body: '고친 본문',
      version: 1,
      // 동네 안내(S07)의 정정 이력과 같은 모양(정정일 YYYY-MM-DD · 사유)
      correction: {
        previousId: 'advisory-1',
        correctedOn: '2025-11-20',
        reason: '참여자 수를 바로잡았어요.',
      },
    })
    expect(previous).toMatchObject({ id: 'advisory-1', outcome: 'corrected', version: 5 })
    expect(previous?.timeline.at(-1)).toEqual({
      step: 'corrected',
      at: '2025-11-20T05:08:00.000Z',
      note: '참여자 수를 바로잡았어요.',
    })

    // 정정한 새 안내도 발행 중이라 다시 정정 · 철회할 수 있고, 내려간 이전 안내는 고칠 수 없다(충돌)
    expect(
      await retractAdvisory(fresh!.id, { reason: '잘못 냈어요.', version: 1 }, 'mock'),
    ).toMatchObject({ status: 'ok' })
    expect(
      await correctAdvisory('advisory-1', { body: 'x', reason: 'y', version: 5 }, 'mock'),
    ).toMatchObject({ status: 'conflict' })
  })

  it('철회: 끝 상태라 다시 정정 · 철회하면 충돌이다', async () => {
    const result = await retractAdvisory(
      'advisory-2',
      { reason: '공식 자료와 달랐어요.', version: 3 },
      'mock',
    )
    if (result.status !== 'ok') throw new Error('not ok')
    expect(result.selectedId).toBe('advisory-2')
    expect(result.entries.find((item) => item.id === 'advisory-2')).toMatchObject({
      outcome: 'retracted',
      version: 4,
      retraction: { retractedOn: '2025-11-20', reason: '공식 자료와 달랐어요.' },
    })
    expect(
      await retractAdvisory('advisory-2', { reason: '다시', version: 4 }, 'mock'),
    ).toMatchObject({ status: 'conflict' })
  })

  it('보류는 정정 · 철회할 수 없고(충돌), 버전이 다르면 충돌로 지금 이력을 준다', async () => {
    expect(
      await retractAdvisory('candidate-w46-5', { reason: '사유', version: 2 }, 'mock'),
    ).toMatchObject({ status: 'conflict' })
    const stale = await retractAdvisory('advisory-1', { reason: '사유', version: 3 }, 'mock')
    expect(stale.status === 'conflict' && stale.entries).toHaveLength(4)
  })

  it('목 재현: empty 는 이력 없음, fail 은 처리가 거부되고 다음 목록(재현 없이)에서 꺼진다, conflict 는 첫 처리가 충돌한다', async () => {
    expect(await listHistory('mock', { scenario: 'empty' })).toEqual({
      status: 'ready',
      entries: [],
    })

    await listHistory('mock', { scenario: 'fail' })
    await expect(
      retractAdvisory('advisory-1', { reason: '사유', version: 4 }, 'mock'),
    ).rejects.toThrow()
    await listHistory('mock')
    expect(
      await retractAdvisory('advisory-1', { reason: '사유', version: 4 }, 'mock'),
    ).toMatchObject({ status: 'ok' })

    await listHistory('mock', { scenario: 'conflict' })
    expect(
      await retractAdvisory('advisory-1', { reason: '사유', version: 4 }, 'mock'),
    ).toMatchObject({ status: 'conflict' })
    expect(
      await retractAdvisory('advisory-1', { reason: '사유', version: 5 }, 'mock'),
    ).toMatchObject({ status: 'ok' })
  })

  it('목 세션이 비회원이 되면 이력을 처음으로 지운다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await retractAdvisory('advisory-1', { reason: '사유', version: 4 }, 'mock')
    await logout('mock')
    expect((await entries())[0]?.outcome).toBe('published')
  })

  it('한국 날짜는 기기 시간대와 무관하다', () => {
    expect(kstDate(new Date('2025-11-19T15:30:00Z'))).toBe('2025-11-20')
  })
})

describe('검토 대기 → 발행 이력 (같은 목 서버)', () => {
  it('보류하면 이번 주 행이 보류로 더해지고, 나중에 발행하면 그 행이 발행으로 바뀐다', async () => {
    const list = await listReviewCandidates('mock')
    if (list.status !== 'ready' || !list.candidates[1]) throw new Error('not ready')
    const { id, version } = list.candidates[1] // ○○2동, 검토 시작 없음
    await holdCandidate(id, { version }, 'mock')

    let rows = await entries()
    expect(rows[0]).toMatchObject({
      id,
      districtName: '○○2동',
      isoWeek: '2025-W47',
      outcome: 'held',
    })
    expect(rows[0]?.reviewMinutes).toBeUndefined()
    expect(summarizeHistory(rows)).toMatchObject({ published: 3, held: 2 })

    await publishCandidate(id, { draft: '고친 본문', version: version + 1 }, 'mock')
    rows = await entries()
    expect(rows.filter((row) => row.id === id)).toHaveLength(1)
    expect(rows[0]).toMatchObject({ id, outcome: 'published', edited: true, body: '고친 본문' })
    expect(rows[0]?.timeline.map((event) => event.step)).toEqual(['held', 'edited', 'published'])
  })

  it('저장한 초안 그대로 발행하면 수정 후 발행이고, 검토 시작이 있으면 검토 시간을 센다', async () => {
    const list = await listReviewCandidates('mock')
    if (list.status !== 'ready' || !list.candidates[0]) throw new Error('not ready')
    const first = list.candidates[0] // ○○1동, 6분 전 검토 시작
    vi.setSystemTime(new Date('2025-11-20T05:10:00Z'))
    await saveCandidateDraft(first.id, { draft: '저장본', version: first.version }, 'mock')
    await publishCandidate(first.id, { draft: '저장본', version: first.version + 1 }, 'mock')

    const [row] = await entries()
    expect(row).toMatchObject({ id: first.id, outcome: 'published', edited: true })
    expect(row?.timeline.map((event) => event.step)).toEqual(['edited', 'published'])
    expect(row?.reviewMinutes).toBe(8)
  })

  it('이력에서 철회 · 정정한 행은 검토 대기를 재설정한 뒤 다시 발행해도 덮지 않는다(끝 상태 유지, 발행 중 2건 방지)', async () => {
    for (const end of ['retract', 'correct'] as const) {
      resetMockHistory()
      resetMockReviewCandidates()
      const list = await listReviewCandidates('mock')
      if (list.status !== 'ready' || !list.candidates[2]) throw new Error('not ready')
      const { id, version, draft } = list.candidates[2]
      await publishCandidate(id, { draft, version }, 'mock')
      if (end === 'retract') await retractAdvisory(id, { reason: '사유', version: 1 }, 'mock')
      else await correctAdvisory(id, { body: draft, reason: '사유', version: 1 }, 'mock')

      // 검토 대기 목을 처음으로(`?mock-admin=` 재설정과 같다) 되돌리고 같은 후보를 다시 발행한다
      resetMockReviewCandidates()
      const again = await listReviewCandidates('mock')
      if (again.status !== 'ready' || !again.candidates[2]) throw new Error('not ready')
      await publishCandidate(id, { draft, version: again.candidates[2].version }, 'mock')

      const rows = (await entries()).filter((row) => row.id === id)
      expect(rows).toHaveLength(1)
      expect(rows[0]?.outcome).toBe(end === 'retract' ? 'retracted' : 'corrected')
      const live = (await entries()).filter(
        (row) =>
          row.districtCode === rows[0]?.districtCode &&
          row.isoWeek === rows[0]?.isoWeek &&
          row.outcome === 'published',
      )
      expect(live).toHaveLength(end === 'retract' ? 0 : 1)
    }
  })

  it('고치지 않고 발행하면 발행(수정 없음)이다', async () => {
    const list = await listReviewCandidates('mock')
    if (list.status !== 'ready' || !list.candidates[2]) throw new Error('not ready')
    const { id, version, draft } = list.candidates[2]
    await publishCandidate(id, { draft, version }, 'mock')
    expect((await entries())[0]).toMatchObject({ id, edited: false })
  })

  it('검토 대기 수는 목록 · 재현을 바꾸지 않고 읽는다(보류 · 발행 제외). 실데이터는 null 이다', async () => {
    expect(await countPendingCandidates('mock')).toBe(4)
    const list = await listReviewCandidates('mock')
    if (list.status !== 'ready' || !list.candidates[0]) throw new Error('not ready')
    await holdCandidate(list.candidates[0].id, { version: list.candidates[0].version }, 'mock')
    expect(await countPendingCandidates('mock')).toBe(3)
    expect(await countPendingCandidates('api')).toBeNull()
  })
})
