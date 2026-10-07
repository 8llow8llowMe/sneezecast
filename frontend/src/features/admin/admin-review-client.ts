import { getMockSession, subscribeMockSession } from '@/features/auth/auth-client'
import type { DataSource } from '@/lib/data-source'

import { recordReviewOutcome } from './admin-history-client'
import { createMockCandidates, MOCK_REVIEW_WEEK } from './mock'
import type { ReviewCandidate, TimelineEvent, TimelineStep } from './types'

/* ── 운영자 검토 (A01–A02 `/admin/review`, #219) ────────────────────────────────────────────────
 *
 * 이번 주 검토 후보와 그 처리(초안 수정 저장 · 보류 · 승인하고 발행). **BE 미정이다(#214)** — 화면이 가정한 모양은
 * docs/api-contract-draft.md "운영자 검토 (#219, 프론트 초안)" 다.
 *
 * - 실데이터: 요청하지 않고 `unavailable` 이다. BE 미정 API 는 보통 출처와 무관하게 목이지만(docs/conventions.md "데이터 출처"),
 *   운영자가 목 후보를 실제 집계로 알고 발행을 눌러도 시민에게 아무것도 나가지 않는다. 화면이 아직 준비하고 있다고 알린다
 *   (알림 설정 · 관심 동네와 같은 결)
 * - 목: 모듈 메모리의 후보 목록(목 서버 흉내). 처리는 백엔드처럼 `version` 을 맞춰 보고, 다르면 충돌(409)로 답한다
 *
 * 상태 전이(backend/docs/entity-design.md §2-5): 수정 저장 → EDITED(목록 `검토 중`), 승인하고 발행 → APPROVED → PUBLISHED
 * (목록에서 빠짐). 보류는 §2-5 에 없다 — 안내가 아니라 후보의 처리 결과(이번 주 이 후보로는 발행하지 않음)로 보고 목록에 남긴다.
 * 보류 · 발행은 같은 목 서버의 발행 이력(A03, `admin-history-client.ts`)에도 남는다(#220).
 */

export type CandidateListResult =
  | { status: 'ready'; isoWeek: string; candidates: readonly ReviewCandidate[] }
  /** 실데이터 — 운영자 API 가 아직 없다 */
  | { status: 'unavailable' }

export type CandidateActionResult =
  /** 처리한 뒤의 후보. 발행해 목록에서 빠졌으면 null 이다 */
  | { status: 'ok'; candidate: ReviewCandidate | null }
  /** 다른 운영자가 먼저 고쳤다(409). 지금 서버의 후보이고, 이미 목록에서 빠졌으면(발행) null 이다 */
  | { status: 'conflict'; candidate: ReviewCandidate | null }
  | { status: 'unavailable' }

/** 목 재현 쿼리. 목데이터 모드에서만 쓴다 */
export const MOCK_ADMIN_PARAM = 'mock-admin'

/** 목 재현: 후보 없음 · 처리가 실패함 · 처리가 한 번씩 충돌함(다른 운영자가 먼저 고침) */
const MOCK_SCENARIOS = ['empty', 'fail', 'conflict'] as const
export type MockAdminScenario = (typeof MOCK_SCENARIOS)[number]

/** 쿼리 값을 목 재현으로 읽는다. 모르는 값이면 null(시안 목록)이다 */
export function parseMockAdminScenario(value: string | null): MockAdminScenario | null {
  return MOCK_SCENARIOS.find((scenario) => scenario === value) ?? null
}

/** `log`: 이 후보의 처리 기록(수정 저장 · 보류 · 발행). 보류 · 발행하면 발행 이력에 넘긴다 */
type MockEntry = { candidate: ReviewCandidate; published: boolean; log: TimelineEvent[] }
type MockStore = { entries: MockEntry[]; failWrites: boolean; staleOnList: boolean }

let mockStore: MockStore | null = null

// 목 세션이 비회원이 되면(로그아웃 · 로그인 만료) 지운다. 같은 기기에서 다음에 로그인한 사람에게 처리 결과가 남지 않게 한다
subscribeMockSession(() => {
  if (getMockSession() === 'guest') mockStore = null
})

function seed(scenario: MockAdminScenario | null): MockStore {
  const candidates = scenario === 'empty' ? [] : createMockCandidates()
  return {
    entries: candidates.map((candidate) => ({ candidate, published: false, log: [] })),
    failWrites: scenario === 'fail',
    staleOnList: scenario === 'conflict',
  }
}

function store(): MockStore {
  mockStore ??= seed(null)
  return mockStore
}

/** 목 서버의 응답처럼 Promise 로 준다. `answer` 가 던지면 거부한다 */
function respond<T>(answer: () => T): Promise<T> {
  return new Promise((resolve) => resolve(answer()))
}

/**
 * 이번 주 검토 후보를 읽는다(발행한 후보는 빠진다). `scenario` 는 목 재현이다 — 주어지면 목 목록을 그 상태로 다시 시작한다(화면을 열 때마다).
 * 주어지지 않으면 목록은 그대로 두고 재현(실패 · 충돌)만 끈다. 목은 실패하지 않는다
 *
 * 목 재현 `conflict` 는 목록을 준 직후 모든 후보의 `version` 을 올린다 — 다른 운영자가 그사이 고친 상황이다.
 * 그래서 후보마다 첫 처리가 충돌하고, 충돌 응답의 최신 후보로 다시 하면 된다
 */
export function listReviewCandidates(
  source: DataSource,
  { scenario = null }: { scenario?: MockAdminScenario | null } = {},
): Promise<CandidateListResult> {
  return respond(() => {
    if (source === 'api') return { status: 'unavailable' }
    if (scenario) mockStore = seed(scenario)
    else Object.assign(store(), { failWrites: false, staleOnList: false })
    const current = store()
    const candidates = current.entries
      .filter((entry) => !entry.published)
      .map((entry) => entry.candidate)
    if (current.staleOnList) {
      current.entries = current.entries.map((entry) => ({
        ...entry,
        candidate: { ...entry.candidate, version: entry.candidate.version + 1 },
      }))
    }
    return { status: 'ready', isoWeek: MOCK_REVIEW_WEEK, candidates }
  })
}

type Change = (candidate: ReviewCandidate) => {
  candidate: ReviewCandidate
  published?: boolean
  /** 처리 기록에 더할 단계 */
  steps: TimelineStep[]
}

/**
 * 목 서버의 처리 하나. 버전이 다르거나 이미 발행했으면 충돌, 재현 `fail` 이면 거부한다.
 * 보류 · 발행이면 처리 기록을 발행 이력에 넘긴다(`recordReviewOutcome`)
 */
function write(
  id: string,
  version: number,
  source: DataSource,
  change: Change,
): Promise<CandidateActionResult> {
  return respond(() => {
    if (source === 'api') return { status: 'unavailable' }
    const current = store()
    if (current.failWrites) throw new Error('mock: review candidate write failed')
    const entry = current.entries.find((item) => item.candidate.id === id)
    if (!entry || entry.published) return { status: 'conflict', candidate: null }
    if (entry.candidate.version !== version)
      return { status: 'conflict', candidate: entry.candidate }
    const next = change(entry.candidate)
    entry.candidate = { ...next.candidate, version: version + 1 }
    entry.published = next.published ?? false
    const at = new Date().toISOString()
    entry.log = [...entry.log, ...next.steps.map((step) => ({ step, at }))]
    if (next.steps.includes('held') || next.published)
      recordReviewOutcome(entry.candidate, entry.log)
    return { status: 'ok', candidate: entry.published ? null : entry.candidate }
  })
}

/** 처음 고치면 검토를 시작한 것으로 본다(목 — 백엔드에 검토 시작 시각을 둘 곳이 아직 없다) */
function startReview(candidate: ReviewCandidate): ReviewCandidate {
  return {
    ...candidate,
    state: 'reviewing',
    reviewStartedAt: candidate.reviewStartedAt ?? new Date().toISOString(),
  }
}

/** 안내문 초안을 고쳐 저장한다(EDITED). 보류한 후보도 고칠 수 있고, 고치면 다시 `검토 중` 이다 */
export function saveCandidateDraft(
  id: string,
  { draft, version }: { draft: string; version: number },
  source: DataSource,
): Promise<CandidateActionResult> {
  return write(id, version, source, (candidate) => ({
    candidate: { ...startReview(candidate), draft },
    steps: ['edited'],
  }))
}

/** 이번 주 이 후보로는 안내를 내지 않는다(보류). 후보는 목록에 남고, 나중에 고쳐 발행할 수도 있다 */
export function holdCandidate(
  id: string,
  { version }: { version: number },
  source: DataSource,
): Promise<CandidateActionResult> {
  return write(id, version, source, (candidate) => ({
    candidate: { ...candidate, state: 'held' },
    steps: ['held'],
  }))
}

/**
 * 지금 초안으로 승인하고 발행한다(APPROVED → PUBLISHED). 저장하지 않은 수정도 함께 보낸다 — 운영자가 본 본문이 발행된다.
 * 발행한 후보는 목록에서 빠진다(`candidate: null`). 저장한 초안과 다르면 운영자 수정(EDITED)을 거친 것으로 남긴다
 */
export function publishCandidate(
  id: string,
  { draft, version }: { draft: string; version: number },
  source: DataSource,
): Promise<CandidateActionResult> {
  return write(id, version, source, (candidate) => ({
    candidate: { ...candidate, draft },
    published: true,
    steps: draft === candidate.draft ? ['published'] : ['edited', 'published'],
  }))
}

/**
 * `검토 대기` 메뉴 옆 수 — 처리하지 않은 후보(보류 · 발행 제외). 다른 운영자 화면(발행 이력)의 메뉴가 쓴다.
 * 목 목록 · 재현을 바꾸지 않고 읽기만 한다. 실데이터는 운영자 API 가 없어 null 이다
 */
export function countPendingCandidates(source: DataSource): Promise<number | null> {
  return respond(() =>
    source === 'api'
      ? null
      : store().entries.filter((entry) => !entry.published && entry.candidate.state !== 'held')
          .length,
  )
}

/** 테스트에서 목 목록을 처음(시안 목록)으로 되돌린다. 화면 코드는 부르지 않는다 */
export function resetMockReviewCandidates(): void {
  mockStore = null
}
