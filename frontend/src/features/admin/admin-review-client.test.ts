import { beforeEach, describe, expect, it } from 'vitest'

import { loginWithEmail, logout, resetMockSession } from '@/features/auth/auth-client'

import {
  holdCandidate,
  listReviewCandidates,
  parseMockAdminScenario,
  publishCandidate,
  resetMockReviewCandidates,
  saveCandidateDraft,
} from './admin-review-client'

async function firstCandidate() {
  const result = await listReviewCandidates('mock')
  if (result.status !== 'ready' || !result.candidates[0]) throw new Error('no candidate')
  return result.candidates[0]
}

beforeEach(() => {
  resetMockSession()
  resetMockReviewCandidates()
})

describe('운영자 검토 클라이언트', () => {
  it('실데이터는 요청하지 않고 unavailable 이다 (목록 · 처리 모두)', async () => {
    expect(await listReviewCandidates('api', { scenario: 'fail' })).toEqual({
      status: 'unavailable',
    })
    expect(await saveCandidateDraft('candidate-1', { draft: '안내', version: 3 }, 'api')).toEqual({
      status: 'unavailable',
    })
    expect(await holdCandidate('candidate-1', { version: 3 }, 'api')).toEqual({
      status: 'unavailable',
    })
    expect(await publishCandidate('candidate-1', { draft: '안내', version: 3 }, 'api')).toEqual({
      status: 'unavailable',
    })
  })

  it('목은 시안의 후보 4건(11월 3주)이고, 표본 100명 이상만 있다', async () => {
    const result = await listReviewCandidates('mock')
    if (result.status !== 'ready') throw new Error('not ready')
    expect(result.isoWeek).toBe('2025-W47')
    expect(result.candidates.map((item) => item.districtName)).toEqual([
      '○○1동',
      '○○2동',
      '○○3동',
      '○○4동',
    ])
    expect(result.candidates.every((item) => item.participants >= 100)).toBe(true)
  })

  it('수정 저장은 초안을 바꾸고 버전을 올리며, 대기 후보는 검토 중이 된다', async () => {
    const list = await listReviewCandidates('mock')
    if (list.status !== 'ready' || !list.candidates[1]) throw new Error('not ready')
    const waiting = list.candidates[1]
    expect(waiting.state).toBe('waiting')

    const result = await saveCandidateDraft(
      waiting.id,
      { draft: '고친 안내', version: waiting.version },
      'mock',
    )
    expect(result).toMatchObject({
      status: 'ok',
      candidate: { draft: '고친 안내', version: waiting.version + 1, state: 'reviewing' },
    })
    if (result.status === 'ok') expect(result.candidate?.reviewStartedAt).toBeDefined()
  })

  it('버전이 다르면 충돌(409)이고 지금 서버의 후보를 준다', async () => {
    const candidate = await firstCandidate()
    await saveCandidateDraft(
      candidate.id,
      { draft: '먼저 고침', version: candidate.version },
      'mock',
    )

    const stale = await holdCandidate(candidate.id, { version: candidate.version }, 'mock')
    expect(stale).toMatchObject({
      status: 'conflict',
      candidate: { draft: '먼저 고침', version: candidate.version + 1 },
    })
  })

  it('보류는 후보를 목록에 남기고, 발행은 목록에서 뺀다 — 발행한 후보를 다시 고치면 충돌(null)이다', async () => {
    const candidate = await firstCandidate()
    const held = await holdCandidate(candidate.id, { version: candidate.version }, 'mock')
    expect(held).toMatchObject({ status: 'ok', candidate: { state: 'held' } })

    const published = await publishCandidate(
      candidate.id,
      { draft: '발행본', version: candidate.version + 1 },
      'mock',
    )
    expect(published).toEqual({ status: 'ok', candidate: null })
    const after = await listReviewCandidates('mock')
    expect(after.status === 'ready' && after.candidates.map((item) => item.id)).not.toContain(
      candidate.id,
    )
    expect(
      await saveCandidateDraft(
        candidate.id,
        { draft: 'x', version: candidate.version + 2 },
        'mock',
      ),
    ).toEqual({ status: 'conflict', candidate: null })
  })

  it('목 재현: empty 는 후보 없음, fail 은 처리가 거부되고 다음 목록(재현 없이)에서 꺼진다', async () => {
    expect(await listReviewCandidates('mock', { scenario: 'empty' })).toMatchObject({
      candidates: [],
    })

    const failing = await listReviewCandidates('mock', { scenario: 'fail' })
    if (failing.status !== 'ready' || !failing.candidates[0]) throw new Error('not ready')
    const { id, version } = failing.candidates[0]
    await expect(holdCandidate(id, { version }, 'mock')).rejects.toThrow()

    await listReviewCandidates('mock')
    expect(await holdCandidate(id, { version }, 'mock')).toMatchObject({ status: 'ok' })
  })

  it('목 재현 conflict: 목록을 준 뒤 다른 운영자가 고친 것처럼 첫 처리가 충돌하고, 최신 버전으로 다시 하면 된다', async () => {
    const list = await listReviewCandidates('mock', { scenario: 'conflict' })
    if (list.status !== 'ready' || !list.candidates[0]) throw new Error('not ready')
    const { id, version } = list.candidates[0]

    const first = await saveCandidateDraft(id, { draft: '안내', version }, 'mock')
    expect(first).toMatchObject({ status: 'conflict', candidate: { version: version + 1 } })
    expect(
      await saveCandidateDraft(id, { draft: '안내', version: version + 1 }, 'mock'),
    ).toMatchObject({ status: 'ok' })
  })

  it('목 세션이 비회원이 되면 처리 결과를 지운다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    const candidate = await firstCandidate()
    await holdCandidate(candidate.id, { version: candidate.version }, 'mock')

    await logout('mock')
    expect((await firstCandidate()).state).toBe('reviewing')
  })

  it('목 재현 쿼리는 아는 값만 받는다', () => {
    expect(parseMockAdminScenario('conflict')).toBe('conflict')
    expect(parseMockAdminScenario('nope')).toBeNull()
    expect(parseMockAdminScenario(null)).toBeNull()
  })
})
