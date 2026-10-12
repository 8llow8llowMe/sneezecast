import { beforeEach, describe, expect, it, vi } from 'vitest'

import { loginWithEmail, logout, resetMockSession } from '@/features/auth/auth-client'
import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import type * as sessionStore from '@/lib/session/session-store'
import { refreshSession } from '@/lib/session/session-store'
import { operatorAdvisory } from '@/test/admin-history'
import {
  CANDIDATE_WEEK,
  candidateAdvisory,
  candidateItem,
  candidateListResponse,
  processable,
} from '@/test/admin-review'

import { ADMIN_ADVISORIES_PATH } from './admin-advisory-request'
import { listHistory, resetMockHistory } from './admin-history-client'
import type { ReviewCandidateItemResponse } from './admin-review-api'
import { toReviewCandidate } from './admin-review-api'
import {
  ADMIN_CANDIDATES_PATH,
  approveAndPublishAdvisory,
  countPendingCandidates,
  createAdvisoryDraft,
  fetchCandidateDetail,
  fetchReviewCandidates,
  hasOpenAdvisory,
  holdCandidate,
  listReviewCandidates,
  parseMockAdminScenario,
  type ProcessableCandidate,
  publishCandidate,
  resetMockReviewCandidates,
  saveAdvisoryEdit,
  saveCandidateDraft,
} from './admin-review-client'
import { createMockCandidates, mockCandidateTitle, mockTitleOf } from './mock'
import type { ReviewCandidate } from './types'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))
vi.mock('@/lib/session/session-store', async (importOriginal) => ({
  ...(await importOriginal<typeof sessionStore>()),
  refreshSession: vi.fn(() => Promise.resolve()),
}))

async function firstCandidate() {
  const result = await listReviewCandidates('mock')
  if (result.status !== 'ready' || !result.candidates[0]) throw new Error('no candidate')
  return processable(result.candidates[0])
}

beforeEach(() => {
  resetMockSession()
  resetMockReviewCandidates()
  resetMockHistory()
  vi.mocked(apiRequest).mockReset()
  vi.mocked(refreshSession).mockClear()
})

describe('운영자 검토 클라이언트', () => {
  it('실데이터 목록은 후보 API 를 읽는다(목 재현 쿼리는 듣지 않는다) — 목 처리 함수 · 검토 대기 수는 요청하지 않는다', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(candidateListResponse([candidateItem()]))
    expect(await listReviewCandidates('api', { scenario: 'empty' })).toMatchObject({
      status: 'ready',
      isoWeek: CANDIDATE_WEEK,
      finalized: true,
      criteria: { surgeChangePercent: 30, repeatRevisedPercent: 20 },
      candidates: [{ id: `11240660:${CANDIDATE_WEEK}`, districtName: '가락1동' }],
    })
    expect(apiRequest).toHaveBeenCalledWith(ADMIN_CANDIDATES_PATH, { query: { week: undefined } })

    vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
    await expect(listReviewCandidates('api')).rejects.toThrow()

    vi.mocked(apiRequest).mockClear()
    // 처리는 제목 · 안내 ID 가 필요한 실데이터 함수가 맡고, 보류 · 검토 대기 수는 서버에 없다
    expect(await saveCandidateDraft('candidate-1', { draft: '안내', version: 3 }, 'api')).toEqual({
      status: 'unavailable',
    })
    expect(await holdCandidate('candidate-1', { version: 3 }, 'api')).toEqual({
      status: 'unavailable',
    })
    expect(await publishCandidate('candidate-1', { draft: '안내', version: 3 }, 'api')).toEqual({
      status: 'unavailable',
    })
    expect(await countPendingCandidates('api')).toBeNull()
    expect(apiRequest).not.toHaveBeenCalled()
  })

  it('목은 시안의 후보 4건(11월 3주)이고, 표본 100명 이상만 있다', async () => {
    const result = await listReviewCandidates('mock')
    if (result.status !== 'ready') throw new Error('not ready')
    expect(result.isoWeek).toBe('2025-W47')
    expect(result.criteria).toEqual({ surgeChangePercent: 30, repeatRevisedPercent: 20 })
    // 유형 필터는 신호로 센다 — 목 후보도 대표 유형 하나를 신호로 갖는다
    expect(result.candidates.map((item) => item.signals)).toEqual([
      ['baseline'],
      ['surge'],
      ['repeat'],
      ['baseline'],
    ])
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
    const waiting = processable(list.candidates[1])
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
    const { id, version } = processable(failing.candidates[0])
    await expect(holdCandidate(id, { version }, 'mock')).rejects.toThrow()

    await listReviewCandidates('mock')
    expect(await holdCandidate(id, { version }, 'mock')).toMatchObject({ status: 'ok' })
  })

  it('목 재현 conflict: 목록을 준 뒤 다른 운영자가 고친 것처럼 첫 처리가 충돌하고, 최신 버전으로 다시 하면 된다', async () => {
    const list = await listReviewCandidates('mock', { scenario: 'conflict' })
    if (list.status !== 'ready' || !list.candidates[0]) throw new Error('not ready')
    const { id, version } = processable(list.candidates[0])

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

  it('목 제목: 고쳐 저장한 제목을 후보에 남기고 발행 이력에도 그 제목으로 더한다', async () => {
    const candidate = await firstCandidate()
    expect(mockTitleOf(candidate)).toBe('발열·기침 보고가 늘었어요')
    const saved = await saveCandidateDraft(
      candidate.id,
      { title: '고친 제목', draft: candidate.draft, version: candidate.version },
      'mock',
    )
    expect(saved).toMatchObject({ status: 'ok', candidate: { title: '고친 제목' } })

    // 저장한 제목 · 초안 그대로 발행하면 운영자 수정은 저장 때 한 번이다
    await publishCandidate(
      candidate.id,
      { title: '고친 제목', draft: candidate.draft, version: candidate.version + 1 },
      'mock',
    )
    const history = await listHistory('mock')
    const entry = history.entries.find((item) => item.id === candidate.id)
    expect(entry).toMatchObject({ title: '고친 제목', edited: true })
    expect(entry?.timeline.map((event) => event.step)).toEqual(['edited', 'published'])
  })

  it('목 발행: 저장하지 않고 제목만 고쳐 발행해도 운영자 수정을 거친 것으로 남는다', async () => {
    const candidate = await firstCandidate()
    await publishCandidate(
      candidate.id,
      { title: '바로 고친 제목', draft: candidate.draft, version: candidate.version },
      'mock',
    )
    const history = await listHistory('mock')
    const entry = history.entries.find((item) => item.id === candidate.id)
    expect(entry?.title).toBe('바로 고친 제목')
    expect(entry?.timeline.map((event) => event.step)).toEqual(['edited', 'published'])
  })

  it('목 후보의 예시 제목: 많이 보고된 증상군이 없으면 증상군을 지어내지 않고 중립 제목이다', () => {
    const [first, surge, repeat] = createMockCandidates()
    if (!first || !surge || !repeat) throw new Error('no candidate')
    expect(mockCandidateTitle(first)).toBe('발열·기침 보고가 늘었어요')
    expect(mockCandidateTitle(surge)).toBe('참여가 지난주보다 많이 늘었어요')
    expect(mockCandidateTitle(repeat)).toBe('구토·설사 보고가 늘었어요')
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- 많이 보고된 증상군만 뺀 후보
    const { leadingSymptom, ...withoutSymptom } = first
    expect(mockCandidateTitle(withoutSymptom)).toBe('이번 주 동네 증상 보고 안내')
  })

  it('목 재현 쿼리는 아는 값만 받는다', () => {
    expect(parseMockAdminScenario('conflict')).toBe('conflict')
    expect(parseMockAdminScenario('nope')).toBeNull()
    expect(parseMockAdminScenario(null)).toBeNull()
  })
})

/* ── 실데이터 요청 (#272) ─────────────────────────────────────────────────────────────────── */

function apiError(code: string, status: number): ApiError {
  return new ApiError({ status, code, message: '거절' })
}

const ADVISORY_PATH = `${ADMIN_ADVISORIES_PATH}/701`

function candidateOf(overrides: Partial<ReviewCandidateItemResponse> = {}): ReviewCandidate {
  const candidate = toReviewCandidate(candidateItem(overrides), CANDIDATE_WEEK)
  if (!candidate) throw new Error('no candidate')
  return candidate
}

/** 처리할 안내(기본 EDITED · 701 · 버전 3)가 있는 후보 */
function openCandidate(status = 'EDITED', version = 3): ProcessableCandidate {
  const candidate = candidateOf({ advisory: candidateAdvisory(status, '701', version) })
  if (!hasOpenAdvisory(candidate)) throw new Error('no open advisory')
  return candidate
}

/** 처리 응답(안내 한 건) */
function advisoryAt(statusCode: string, version: number, overrides = {}) {
  return operatorAdvisory({ statusCode, version, publishedAt: null, ...overrides })
}

describe('운영자 검토 클라이언트 (실데이터 요청)', () => {
  const edit = { title: '고친 제목', body: '고친 본문' }

  describe('후보 목록 · 상세', () => {
    it('목록은 GET /candidates 이고 week 를 주면 쿼리로 싣는다(없으면 서버 기본). 응답을 화면 묶음으로 옮긴다', async () => {
      vi.mocked(apiRequest).mockResolvedValue(
        candidateListResponse([
          candidateItem({ advisory: candidateAdvisory('EDITED') }),
          candidateItem({
            districtCode: '11240670',
            advisory: candidateAdvisory('PUBLISHED', '702'),
          }),
        ]),
      )

      const list = await fetchReviewCandidates()
      expect(ADMIN_CANDIDATES_PATH).toBe('/api/v1/advisories/admin/candidates')
      expect(apiRequest).toHaveBeenLastCalledWith(ADMIN_CANDIDATES_PATH, {
        query: { week: undefined },
      })
      expect(list.isoWeek).toBe(CANDIDATE_WEEK)
      // 발행 중인 안내가 있는 동네는 빠진다
      expect(list.candidates.map((item) => [item.id, item.advisoryStatus])).toEqual([
        ['11240660:2025-W46', 'EDITED'],
      ])

      await fetchReviewCandidates({ week: '2025-W45' })
      expect(apiRequest).toHaveBeenLastCalledWith(ADMIN_CANDIDATES_PATH, {
        query: { week: '2025-W45' },
      })
    })

    it('목록 실패 · 응답 모양이 계약과 다르면 거부한다. 403 이면 세션 요약을 다시 맞춘다', async () => {
      vi.mocked(apiRequest).mockResolvedValueOnce({ isoWeek: CANDIDATE_WEEK })
      await expect(fetchReviewCandidates()).rejects.toThrow()
      expect(refreshSession).not.toHaveBeenCalled()

      vi.mocked(apiRequest).mockRejectedValueOnce(apiError('SECURITY_006', 403))
      await expect(fetchReviewCandidates()).rejects.toMatchObject({ code: 'SECURITY_006' })
      expect(refreshSession).toHaveBeenCalledTimes(1)
    })

    it('상세는 GET /{advisoryId} 로 제목 · 초안 · 버전 · 안내 상태를 덮는다', async () => {
      vi.mocked(apiRequest).mockResolvedValueOnce({
        advisory: advisoryAt('APPROVED', 4, { title: '상세 제목', body: '상세 본문' }),
        histories: [],
      })

      const candidate = await fetchCandidateDetail(openCandidate())
      expect(apiRequest).toHaveBeenCalledWith(ADVISORY_PATH)
      expect(candidate).toMatchObject({
        state: 'reviewing',
        advisoryId: '701',
        advisoryStatus: 'APPROVED',
        version: 4,
        title: '상세 제목',
        draft: '상세 본문',
      })
    })

    it('처리할 안내가 없는 후보(대기)는 상세를 요청하지 않고 그대로 준다. 상세 실패는 거부한다', async () => {
      const waiting = candidateOf()
      expect(await fetchCandidateDetail(waiting)).toBe(waiting)
      expect(apiRequest).not.toHaveBeenCalled()

      vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('network', 0))
      await expect(fetchCandidateDetail(openCandidate())).rejects.toMatchObject({
        code: 'UNAVAILABLE',
      })
    })
  })

  describe('초안 만들기 · 수정 저장', () => {
    it('초안 만들기는 POST { districtCode, isoWeek, title, body } 이고 응답(DRAFT)으로 후보를 덮는다', async () => {
      vi.mocked(apiRequest).mockResolvedValueOnce(
        advisoryAt('DRAFT', 0, { title: '새 제목', body: '새 본문' }),
      )

      const result = await createAdvisoryDraft(candidateOf(), { title: '새 제목', body: '새 본문' })
      expect(vi.mocked(apiRequest).mock.calls).toEqual([
        [
          ADMIN_ADVISORIES_PATH,
          {
            method: 'POST',
            body: {
              districtCode: '11240660',
              isoWeek: CANDIDATE_WEEK,
              title: '새 제목',
              body: '새 본문',
            },
          },
        ],
      ])
      expect(result).toEqual({
        status: 'ok',
        candidate: expect.objectContaining({
          state: 'reviewing',
          advisoryId: '701',
          advisoryStatus: 'DRAFT',
          version: 0,
          title: '새 제목',
          draft: '새 본문',
        }),
      })
    })

    it('초안을 만들 수 없거나(draftable 아님) 이미 처리할 안내가 있으면 요청하지 않고 rejected 다', async () => {
      expect(await createAdvisoryDraft(candidateOf({ draftable: false }), edit)).toEqual({
        status: 'rejected',
        reason: 'not-draftable',
      })
      expect(await createAdvisoryDraft(openCandidate(), edit)).toEqual({
        status: 'rejected',
        reason: 'has-advisory',
      })
      expect(apiRequest).not.toHaveBeenCalled()
    })

    it.each([
      ['ADVISORY_002', 404],
      ['ADVISORY_003', 409],
      ['ADVISORY_004', 409],
    ])(
      '초안 만들기가 %s(%i)면 목록을 다시 읽어 충돌로 준다 — 안내가 없는 후보는 상세를 읽지 않는다',
      async (code, status) => {
        vi.mocked(apiRequest)
          .mockRejectedValueOnce(apiError(code, status))
          .mockResolvedValueOnce(candidateListResponse([candidateItem({ draftable: false })]))

        const result = await createAdvisoryDraft(candidateOf(), edit)
        expect(apiRequest).toHaveBeenCalledTimes(2)
        expect(apiRequest).toHaveBeenLastCalledWith(ADMIN_CANDIDATES_PATH, {
          query: { week: CANDIDATE_WEEK },
        })
        expect(result).toMatchObject({
          status: 'conflict',
          candidate: { id: '11240660:2025-W46', draftable: false },
        })
      },
    )

    it('초안 만들기가 처리됐는데 응답을 옮기지 못하면 다시 읽어 안내가 있는 후보를 준다(중복 초안 방지). 못 읽으면 unrefreshed', async () => {
      vi.mocked(apiRequest)
        .mockResolvedValueOnce(advisoryAt('DRAFT', 0, { districtCode: '11240670' }))
        .mockResolvedValueOnce(
          candidateListResponse([
            candidateItem({ advisory: candidateAdvisory('DRAFT', '701', 0) }),
          ]),
        )
        .mockResolvedValueOnce({ advisory: advisoryAt('DRAFT', 0), histories: [] })
      expect(await createAdvisoryDraft(candidateOf(), edit)).toMatchObject({
        status: 'ok',
        candidate: { advisoryId: '701', advisoryStatus: 'DRAFT', version: 0 },
      })

      vi.mocked(apiRequest)
        .mockResolvedValueOnce(advisoryAt('DRAFT', 0, { districtCode: '11240670' }))
        .mockRejectedValueOnce(unavailableError('network', 0))
      expect(await createAdvisoryDraft(candidateOf(), edit)).toEqual({
        status: 'unrefreshed',
        outcome: 'ok',
      })
    })

    it('다시 읽은 상세가 그사이 발행됐으면 목록에서 빼고 후보는 null 이다. 다시 읽기가 403 이면 세션 요약을 한 번 맞춘다', async () => {
      vi.mocked(apiRequest)
        .mockRejectedValueOnce(apiError('ADVISORY_005', 409))
        .mockResolvedValueOnce(
          candidateListResponse([
            candidateItem({ advisory: candidateAdvisory('APPROVED', '701', 5) }),
            candidateItem({ districtCode: '11240670' }),
          ]),
        )
        .mockResolvedValueOnce({ advisory: advisoryAt('PUBLISHED', 6), histories: [] })
      const result = await saveAdvisoryEdit(openCandidate(), edit)
      if (result.status !== 'conflict') throw new Error('not conflict')
      expect(result.candidate).toBeNull()
      expect(result.list.candidates.map((item) => item.id)).toEqual(['11240670:2025-W46'])

      vi.mocked(apiRequest)
        .mockRejectedValueOnce(apiError('ADVISORY_006', 409))
        .mockRejectedValueOnce(apiError('SECURITY_006', 403))
      expect(await saveAdvisoryEdit(openCandidate(), edit)).toEqual({
        status: 'unrefreshed',
        outcome: 'conflict',
      })
      expect(refreshSession).toHaveBeenCalledTimes(1)
    })

    it('수정 저장은 PUT /{advisoryId} { title, body, version } 이고 응답의 새 버전 · EDITED 를 싣는다', async () => {
      vi.mocked(apiRequest).mockResolvedValueOnce(advisoryAt('EDITED', 4, edit))

      const result = await saveAdvisoryEdit(openCandidate('APPROVED'), edit)
      expect(apiRequest).toHaveBeenCalledWith(ADVISORY_PATH, {
        method: 'PUT',
        body: { ...edit, version: 3 },
      })
      expect(result).toMatchObject({
        status: 'ok',
        candidate: { advisoryStatus: 'EDITED', version: 4, title: '고친 제목', draft: '고친 본문' },
      })
    })

    it('수정 저장 충돌(006)이면 목록 · 그 후보 상세를 다시 읽고, 다시 읽지 못하면 unrefreshed 다', async () => {
      vi.mocked(apiRequest)
        .mockRejectedValueOnce(apiError('ADVISORY_006', 409))
        .mockResolvedValueOnce(
          candidateListResponse([
            candidateItem({ advisory: candidateAdvisory('EDITED', '701', 5) }),
          ]),
        )
        .mockResolvedValueOnce({
          advisory: advisoryAt('EDITED', 5, { body: '남이 고친 본문' }),
          histories: [],
        })

      const result = await saveAdvisoryEdit(openCandidate(), edit)
      expect(vi.mocked(apiRequest).mock.calls.slice(1)).toEqual([
        [ADMIN_CANDIDATES_PATH, { query: { week: CANDIDATE_WEEK } }],
        [ADVISORY_PATH],
      ])
      if (result.status !== 'conflict') throw new Error('not conflict')
      expect(result.candidate).toMatchObject({ version: 5, draft: '남이 고친 본문' })
      // 다시 읽은 목록의 그 행도 상세를 덮은 후보다
      expect(result.list.candidates).toEqual([result.candidate])

      vi.mocked(apiRequest)
        .mockRejectedValueOnce(apiError('ADVISORY_006', 409))
        .mockRejectedValueOnce(unavailableError('network', 0))
      expect(await saveAdvisoryEdit(openCandidate(), edit)).toEqual({
        status: 'unrefreshed',
        outcome: 'conflict',
      })
    })

    it('검증 거부(400)는 다시 읽지 않고 invalid, 그 밖의 실패는 거부한다. 403 이면 세션 요약을 다시 맞춘다', async () => {
      vi.mocked(apiRequest).mockRejectedValueOnce(apiError('ADVISORY_106', 400))
      expect(await saveAdvisoryEdit(openCandidate(), edit)).toEqual({ status: 'invalid' })
      expect(apiRequest).toHaveBeenCalledTimes(1)

      vi.mocked(apiRequest).mockRejectedValueOnce(unavailableError('timeout', 0))
      await expect(saveAdvisoryEdit(openCandidate(), edit)).rejects.toMatchObject({
        code: 'UNAVAILABLE',
      })
      expect(refreshSession).not.toHaveBeenCalled()

      vi.mocked(apiRequest).mockRejectedValueOnce(apiError('SECURITY_006', 403))
      await expect(createAdvisoryDraft(candidateOf(), edit)).rejects.toMatchObject({
        code: 'SECURITY_006',
      })
      expect(refreshSession).toHaveBeenCalledTimes(1)
      expect(apiRequest).toHaveBeenCalledTimes(3)
    })
  })

  describe('승인하고 발행', () => {
    const approvePath = `${ADVISORY_PATH}/approve`
    const publishPath = `${ADVISORY_PATH}/publish`

    it('검토 중(EDITED) · 고친 것 없음: approve → publish, 앞 응답의 version 을 잇는다', async () => {
      vi.mocked(apiRequest)
        .mockResolvedValueOnce(advisoryAt('APPROVED', 4))
        .mockResolvedValueOnce(advisoryAt('PUBLISHED', 5))

      const result = await approveAndPublishAdvisory(openCandidate('EDITED', 3), {
        ...edit,
        dirty: false,
      })
      expect(result).toEqual({ status: 'published' })
      expect(vi.mocked(apiRequest).mock.calls).toEqual([
        [approvePath, { method: 'POST', body: { version: 3 } }],
        [publishPath, { method: 'POST', body: { version: 4 } }],
      ])
    })

    it.each(['EDITED', 'APPROVED'])(
      '%s · 고친 것 있음: PUT → approve → publish, 각 단계 응답의 version 을 잇는다',
      async (status) => {
        vi.mocked(apiRequest)
          .mockResolvedValueOnce(advisoryAt('EDITED', 4, edit))
          .mockResolvedValueOnce(advisoryAt('APPROVED', 5, edit))
          .mockResolvedValueOnce(advisoryAt('PUBLISHED', 6, edit))

        const result = await approveAndPublishAdvisory(openCandidate(status, 3), {
          ...edit,
          dirty: true,
        })
        expect(result).toEqual({ status: 'published' })
        expect(vi.mocked(apiRequest).mock.calls).toEqual([
          [ADVISORY_PATH, { method: 'PUT', body: { ...edit, version: 3 } }],
          [approvePath, { method: 'POST', body: { version: 4 } }],
          [publishPath, { method: 'POST', body: { version: 5 } }],
        ])
      },
    )

    it('이미 승인됨(APPROVED) · 고친 것 없음: 승인을 건너뛰고 publish 만 한다', async () => {
      vi.mocked(apiRequest).mockResolvedValueOnce(advisoryAt('PUBLISHED', 4))

      const result = await approveAndPublishAdvisory(openCandidate('APPROVED', 3), {
        ...edit,
        dirty: false,
      })
      expect(result).toEqual({ status: 'published' })
      expect(vi.mocked(apiRequest).mock.calls).toEqual([
        [publishPath, { method: 'POST', body: { version: 3 } }],
      ])
    })

    it('발행이 007 이면 승인됨으로 남음(approvedNotPublished)이고 목록을 다시 읽는다 — 발행 중인 동네는 빠져 후보가 없다', async () => {
      vi.mocked(apiRequest)
        .mockResolvedValueOnce(advisoryAt('APPROVED', 4))
        .mockRejectedValueOnce(apiError('ADVISORY_007', 409))
        .mockResolvedValueOnce(candidateListResponse([]))

      const result = await approveAndPublishAdvisory(openCandidate('DRAFT', 3), {
        ...edit,
        dirty: false,
      })
      expect(apiRequest).toHaveBeenLastCalledWith(ADMIN_CANDIDATES_PATH, {
        query: { week: CANDIDATE_WEEK },
      })
      expect(apiRequest).toHaveBeenCalledTimes(3)
      expect(result).toEqual({
        status: 'approvedNotPublished',
        list: expect.objectContaining({ candidates: [] }),
        candidate: null,
      })

      vi.mocked(apiRequest)
        .mockRejectedValueOnce(apiError('ADVISORY_007', 409))
        .mockRejectedValueOnce(unavailableError('network', 0))
      expect(
        await approveAndPublishAdvisory(openCandidate('APPROVED', 4), { ...edit, dirty: false }),
      ).toEqual({ status: 'unrefreshed', outcome: 'approvedNotPublished', step: 'publish' })
    })

    it('승인 단계 충돌(006)이면 멈추고 그 단계 · 다시 읽은 목록 · 그 후보 상세를 준다', async () => {
      vi.mocked(apiRequest)
        .mockResolvedValueOnce(advisoryAt('EDITED', 4, edit))
        .mockRejectedValueOnce(apiError('ADVISORY_006', 409))
        .mockResolvedValueOnce(
          candidateListResponse([
            candidateItem({ advisory: candidateAdvisory('EDITED', '701', 6) }),
          ]),
        )
        .mockResolvedValueOnce({ advisory: advisoryAt('EDITED', 6), histories: [] })

      const result = await approveAndPublishAdvisory(openCandidate('EDITED', 3), {
        ...edit,
        dirty: true,
      })
      expect(vi.mocked(apiRequest).mock.calls.slice(2)).toEqual([
        [ADMIN_CANDIDATES_PATH, { query: { week: CANDIDATE_WEEK } }],
        [ADVISORY_PATH],
      ])
      expect(result).toMatchObject({
        status: 'conflict',
        step: 'approve',
        candidate: { version: 6 },
      })
    })

    it('승인 뒤 발행 충돌(006)이면 step publish 의 충돌, 발행 일시 장애면 승인 뒤 버전을 실은 후보의 failed 다', async () => {
      vi.mocked(apiRequest)
        .mockResolvedValueOnce(advisoryAt('APPROVED', 4))
        .mockRejectedValueOnce(apiError('ADVISORY_006', 409))
        .mockResolvedValueOnce(
          candidateListResponse([
            candidateItem({ advisory: candidateAdvisory('APPROVED', '701', 5) }),
          ]),
        )
        .mockResolvedValueOnce({ advisory: advisoryAt('APPROVED', 5), histories: [] })
      expect(
        await approveAndPublishAdvisory(openCandidate(), { ...edit, dirty: false }),
      ).toMatchObject({ status: 'conflict', step: 'publish', candidate: { version: 5 } })

      vi.mocked(apiRequest)
        .mockResolvedValueOnce(advisoryAt('APPROVED', 4))
        .mockRejectedValueOnce(unavailableError('network', 0))
      expect(
        await approveAndPublishAdvisory(openCandidate(), { ...edit, dirty: false }),
      ).toMatchObject({
        status: 'failed',
        step: 'publish',
        candidate: { advisoryStatus: 'APPROVED', version: 4 },
        error: { code: 'UNAVAILABLE' },
      })
    })

    it('수정 저장 응답이 다른 동네의 안내면(계약과 다름) 이어 가지 않고 step save 의 failed 다', async () => {
      vi.mocked(apiRequest).mockResolvedValueOnce(
        advisoryAt('EDITED', 4, { districtCode: '11240670' }),
      )
      expect(
        await approveAndPublishAdvisory(openCandidate(), { ...edit, dirty: true }),
      ).toMatchObject({ status: 'failed', step: 'save', candidate: { version: 3 } })
      expect(apiRequest).toHaveBeenCalledTimes(1)
    })

    it('검증 거부는 그 단계의 invalid, 그 밖의 실패는 그 단계 · 처리된 단계까지 반영한 후보의 failed 다(거부하지 않는다)', async () => {
      vi.mocked(apiRequest).mockRejectedValueOnce(apiError('ADVISORY_105', 400))
      expect(await approveAndPublishAdvisory(openCandidate(), { ...edit, dirty: true })).toEqual({
        status: 'invalid',
        step: 'save',
        candidate: openCandidate(),
      })

      // 저장한 뒤 승인이 검증 거부 — 저장까지 반영한 후보(다음 요청의 버전)를 싣는다
      vi.mocked(apiRequest).mockReset()
      vi.mocked(apiRequest)
        .mockResolvedValueOnce(advisoryAt('EDITED', 4, edit))
        .mockRejectedValueOnce(apiError('ADVISORY_113', 400))
      expect(
        await approveAndPublishAdvisory(openCandidate(), { ...edit, dirty: true }),
      ).toMatchObject({
        status: 'invalid',
        step: 'approve',
        candidate: { advisoryStatus: 'EDITED', version: 4, draft: '고친 본문' },
      })

      vi.mocked(apiRequest).mockReset()
      vi.mocked(apiRequest)
        .mockResolvedValueOnce(advisoryAt('EDITED', 4, edit))
        .mockRejectedValueOnce(apiError('SECURITY_006', 403))
      const result = await approveAndPublishAdvisory(openCandidate(), { ...edit, dirty: true })
      expect(result).toMatchObject({
        status: 'failed',
        step: 'approve',
        candidate: { advisoryStatus: 'EDITED', version: 4, draft: '고친 본문' },
        error: { code: 'SECURITY_006' },
      })
      expect(refreshSession).toHaveBeenCalledTimes(1)
      // 실패 뒤 다시 읽지 않는다
      expect(apiRequest).toHaveBeenCalledTimes(2)
    })
  })
})
