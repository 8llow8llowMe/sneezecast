import { getMockSession, subscribeMockSession } from '@/features/auth/auth-client'
import { apiRequest } from '@/lib/api/client'
import type { DataSource } from '@/lib/data-source'

import {
  ADMIN_ADVISORIES_PATH,
  CONFLICT_CODES,
  errorCode,
  INVALID_CODES,
  syncRoleOnForbidden,
} from './admin-advisory-request'
import type { OperatorAdvisoryResponse } from './admin-history-api'
import { recordReviewOutcome } from './admin-history-client'
import {
  type OperatorAdvisoryDetailResponse,
  type ReviewCandidateListResponse,
  toReviewCandidateList,
  withAdvisoryDetail,
} from './admin-review-api'
import {
  createMockCandidates,
  MOCK_REVIEW_CRITERIA,
  MOCK_REVIEW_WEEK,
  type MockReviewCandidate,
  mockTitleOf,
} from './mock'
import type {
  ReviewAdvisoryStatus,
  ReviewCandidate,
  ReviewCandidateList,
  TimelineEvent,
  TimelineStep,
} from './types'

/* ── 운영자 검토 (A01–A02 `/admin/review`, #219) ────────────────────────────────────────────────
 *
 * 이번 주 검토 후보와 그 처리(초안 만들기 · 수정 저장 · 보류 · 승인하고 발행). 처리(초안 · 수정 · 승인 · 발행)는 백엔드 #246, 후보 목록은
 * #266 으로 확정됐다(docs/api-contract-draft.md "운영자 안내 처리" · "운영자 검토 후보"). 응답을 화면 모델로 옮기는 규칙은 `admin-review-api.ts` 다.
 *
 * - 실데이터(#272): 목록은 `listReviewCandidates` 가 후보 API 를 읽는다(`fetchReviewCandidates`). 처리는 제목 · 안내 ID 가 필요해 모양이
 *   달라 화면이 파일 끝 "실데이터 요청" 의 함수(`createAdvisoryDraft` · `saveAdvisoryEdit` · `approveAndPublishAdvisory`)를 바로 부른다.
 *   아래 목 처리 함수(`saveCandidateDraft` · `holdCandidate` · `publishCandidate`)는 실데이터에서 요청하지 않고 `unavailable` 이다 —
 *   보류는 서버에 없고(화면이 버튼을 숨긴다), 수정 · 발행은 위 함수가 맡는다. 검토 대기 수(`countPendingCandidates`)도 서버에 없어 null 이다
 * - 목: 모듈 메모리의 후보 목록(목 서버 흉내). 처리는 백엔드처럼 `version` 을 맞춰 보고, 다르면 충돌(409)로 답한다
 *
 * 상태 전이(backend/docs/entity-design.md §2-5): 수정 저장 → EDITED(목록 `검토 중`), 승인하고 발행 → APPROVED → PUBLISHED
 * (목록에서 빠짐 — 백엔드는 승인 · 발행 두 요청이다). 보류는 §2-5 에 없다 — 안내가 아니라 후보의 처리 결과(이번 주 이 후보로는 발행하지
 * 않음)로 보고 목록에 남긴다. 백엔드는 1단계에서 보류를 기록하지 않는다(#246 결정 — 후보 처리 기록은 `review_signal` 2단계).
 * 보류 · 발행은 같은 목 서버의 발행 이력(A03, `admin-history-client.ts`)에도 남는다(#220).
 */

/** 한 주의 검토 후보(기준 주 · 마감 여부 · 후보 기준 · 후보). 실데이터는 후보 API 응답을 옮긴 값이다 */
export type CandidateListResult = { status: 'ready' } & ReviewCandidateList

export type CandidateActionResult =
  /** 처리한 뒤의 후보. 발행해 목록에서 빠졌으면 null 이다 */
  | { status: 'ok'; candidate: ReviewCandidate | null }
  /** 다른 운영자가 먼저 고쳤다(409). 지금 서버의 후보이고, 이미 목록에서 빠졌으면(발행) null 이다 */
  | { status: 'conflict'; candidate: ReviewCandidate | null }
  /** 실데이터 — 목 처리 함수는 요청하지 않는다(실데이터 처리는 파일 끝 함수) */
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
type MockEntry = { candidate: MockReviewCandidate; published: boolean; log: TimelineEvent[] }
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
 * 검토 후보를 읽는다(발행한 후보는 빠진다). 실데이터는 후보 API(서버 기본 주 — 현재 주 − 1)이고, 실패 · 응답 모양이 계약과 다르면 거부한다
 * (403 이면 세션 요약을 다시 맞춘다 — `fetchReviewCandidates`). 목 재현 쿼리는 듣지 않는다.
 *
 * 목: 이번 주 목 후보다. `scenario` 는 목 재현이다 — 주어지면 목 목록을 그 상태로 다시 시작한다(화면을 열 때마다).
 * 주어지지 않으면 목록은 그대로 두고 재현(실패 · 충돌)만 끈다. 목은 실패하지 않는다.
 * 목 재현 `conflict` 는 목록을 준 직후 모든 후보의 `version` 을 올린다 — 다른 운영자가 그사이 고친 상황이다.
 * 그래서 후보마다 첫 처리가 충돌하고, 충돌 응답의 최신 후보로 다시 하면 된다
 */
export async function listReviewCandidates(
  source: DataSource,
  { scenario = null }: { scenario?: MockAdminScenario | null } = {},
): Promise<CandidateListResult> {
  if (source === 'api') return { status: 'ready', ...(await fetchReviewCandidates()) }
  return respond(() => {
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
    return {
      status: 'ready',
      isoWeek: MOCK_REVIEW_WEEK,
      finalized: true,
      criteria: MOCK_REVIEW_CRITERIA,
      candidates,
    }
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

/**
 * 안내문 초안(`title` 을 주면 제목도)을 고쳐 저장한다(EDITED). 보류한 후보도 고칠 수 있고, 고치면 다시 `검토 중` 이다. 목 처리 함수다 —
 * 실데이터는 요청하지 않는다(`saveAdvisoryEdit`)
 */
export function saveCandidateDraft(
  id: string,
  { title, draft, version }: { title?: string; draft: string; version: number },
  source: DataSource,
): Promise<CandidateActionResult> {
  return write(id, version, source, (candidate) => ({
    candidate: { ...startReview(candidate), draft, ...(title !== undefined && { title }) },
    steps: ['edited'],
  }))
}

/** 이번 주 이 후보로는 안내를 내지 않는다(보류). 후보는 목록에 남고, 나중에 고쳐 발행할 수도 있다. 보류는 서버에 없어 목에서만이다 */
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
 * 지금 제목 · 초안으로 승인하고 발행한다(APPROVED → PUBLISHED). 저장하지 않은 수정도 함께 보낸다 — 운영자가 본 제목 · 본문이 발행된다.
 * 발행한 후보는 목록에서 빠진다(`candidate: null`). 저장한 제목 · 초안과 다르면 운영자 수정(EDITED)을 거친 것으로 남긴다.
 * 목 처리 함수다 — 실데이터는 요청하지 않는다(`approveAndPublishAdvisory`)
 */
export function publishCandidate(
  id: string,
  { title, draft, version }: { title?: string; draft: string; version: number },
  source: DataSource,
): Promise<CandidateActionResult> {
  return write(id, version, source, (candidate) => {
    const unchanged =
      draft === candidate.draft && (title === undefined || title === mockTitleOf(candidate))
    return {
      candidate: { ...candidate, draft, ...(title !== undefined && { title }) },
      published: true,
      steps: unchanged ? ['published'] : ['edited', 'published'],
    }
  })
}

/**
 * `검토 대기` 메뉴 옆 수 — 처리하지 않은 후보(보류 · 발행 제외). 다른 운영자 화면(발행 이력)의 메뉴가 쓴다.
 * 목 목록 · 재현을 바꾸지 않고 읽기만 한다. 실데이터는 null 이다 — 수만 주는 API 가 없고, 메뉴 수 하나로 후보 목록을 읽지 않는다(#272 결정)
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

/* ── 실데이터 요청 (연동 #272) ──────────────────────────────────────────────────────────────────
 *
 * 후보 목록(`GET /candidates?week=`) · 안내 상세(`GET /{advisoryId}`) · 초안 만들기(`POST`) · 수정 저장(`PUT /{advisoryId}`) ·
 * 승인하고 발행(`POST /{advisoryId}/approve` → `/publish`). 계약은 docs/api-contract-draft.md "운영자 안내 처리"(#246) ·
 * "운영자 검토 후보"(#266)이고, 응답을 화면 모델로 옮기는 규칙은 `admin-review-api.ts` 다. 검토 대기 화면이 실데이터에서 부른다(위 머리말).
 *
 * - 처리 응답은 안내 한 건이라 그 후보에 바로 덮는다(`withAdvisoryDetail`) — 다시 읽지 않는다. 앞 응답의 `version` 을 다음 요청에 싣는다
 * - 충돌(409 `005` · `006` · `007`, 404 `001` — 초안 만들기는 `002` · `003` · `004` 도)이면 오류 봉투에 안내가 없어 **목록을 다시 읽고 그
 *   후보(동네 · 주)의 상세까지 다시 읽어** 최신 후보를 싣는다. 다시 읽지 못하면 `unrefreshed` 다(발행 이력과 같다)
 * - 검증 거부(400 — `100` · `105`~`113` · `198`)는 `invalid`. 그 밖의 실패는 거부한다 — 403 이면 세션 요약을 다시 맞추고(운영자 가드가
 *   권한 없음을 그린다), 401 은 API 계층이 맡는다. 목록 · 상세 응답 모양이 계약과 달라 옮기지 못해도 거부한다(초안 만들기 · 수정 저장이
 *   처리됐는데 응답을 옮기지 못하면 다시 읽는다 — 승인하고 발행은 그 단계의 `failed`)
 * - 보류 · 검토 대기 수는 서버에 없다(보류는 기록되지 않는다 — 2단계 `review_signal`). 실데이터로 지어내지 않는다
 */

/** 검토 후보 목록 API */
export const ADMIN_CANDIDATES_PATH = `${ADMIN_ADVISORIES_PATH}/candidates`

/** 초안 만들기만의 충돌 — 근거 집계 없음(`002`) · 마감 전(`003`) · 자료 부족(`004`). 읽은 후보가 낡았다(목록을 다시 읽으면 `draftable` 이 바뀐다) */
const CREATE_CONFLICT_CODES: ReadonlySet<string> = new Set([
  ...CONFLICT_CODES,
  'ADVISORY_002',
  'ADVISORY_003',
  'ADVISORY_004',
])

/** 처리할 안내(검토 중)가 있는 후보 — 수정 저장 · 승인하고 발행이 받는다 */
export type ProcessableCandidate = ReviewCandidate &
  Readonly<{ advisoryId: string; advisoryStatus: ReviewAdvisoryStatus; version: number }>

/** 처리할 안내가 있는 후보인지(`advisoryId` · 안내 상태 · `version` 이 모두 있다) */
export function hasOpenAdvisory(candidate: ReviewCandidate): candidate is ProcessableCandidate {
  return (
    candidate.advisoryId !== undefined &&
    candidate.advisoryStatus !== undefined &&
    candidate.version !== undefined
  )
}

/** 충돌 뒤 다시 읽은 목록과 그 후보(상세까지 읽음). 그 후보가 목록에서 빠졌으면(발행 · 후보 아님) null 이다 */
export type RefreshedCandidate = { list: ReviewCandidateList; candidate: ReviewCandidate | null }

export type AdvisoryWriteResult =
  /** 처리한 뒤의 후보(응답으로 덮음). 응답이 발행됨이면(계약 밖) null 이다 */
  | { status: 'ok'; candidate: ReviewCandidate | null }
  /** 다른 운영자가 먼저 바꿨다 — 다시 읽은 목록과 그 후보 */
  | ({ status: 'conflict' } & RefreshedCandidate)
  /** 서버가 입력을 받지 않았다(검증 400). 화면이 먼저 막아 보통은 닿지 않는다 */
  | { status: 'invalid' }
  /**
   * 처리(`ok` — 응답을 후보로 옮기지 못해 다시 읽으려 함) 또는 충돌까지는 알지만 다시 읽지 못했다(일시 장애 등). 목록을 다시 읽게 한다
   */
  | { status: 'unrefreshed'; outcome: 'ok' | 'conflict' }

export type CreateDraftResult =
  | AdvisoryWriteResult
  /**
   * 요청하지 않았다 — 초안을 만들 수 없는 후보(`draftable` 이 아님: 마감 전 · 자료 부족 — 서버도 `003` · `004` 로 거부한다)이거나 이미
   * 처리할 안내가 있다(같은 동네 · 주에 초안이 둘 생기지 않게 — 수정 저장으로 고친다)
   */
  | { status: 'rejected'; reason: 'not-draftable' | 'has-advisory' }

/** 승인하고 발행의 단계 — 수정 저장(`PUT`) → 승인 → 발행 */
export type PublishStep = 'save' | 'approve' | 'publish'

export type ApproveAndPublishResult =
  /** 발행했다 — 후보는 목록에서 빠진다 */
  | { status: 'published' }
  /**
   * 발행이 `ADVISORY_007`(같은 동네 · 주에 발행 중인 안내가 있음)로 거부돼 안내가 **승인됨으로 남았다**. 다시 읽은 목록과 그 후보다
   * (발행 중인 안내가 있는 동네는 목록에서 빠지므로 보통 그 후보는 null 이다)
   */
  | ({ status: 'approvedNotPublished' } & RefreshedCandidate)
  /** `step` 에서 충돌했다 — 그 앞 단계는 처리됐다. 다시 읽은 목록과 그 후보 */
  | ({ status: 'conflict'; step: PublishStep } & RefreshedCandidate)
  /** `step` 에서 검증 거부됐다 — 그 앞 단계는 처리됐다. `candidate` 는 처리된 단계까지 반영한 후보다(`failed` 와 같다) */
  | { status: 'invalid'; step: PublishStep; candidate: ReviewCandidate }
  /** `step` 에서 충돌 · 승인됨으로 남음까지는 알지만 다시 읽지 못했다 */
  | { status: 'unrefreshed'; outcome: 'conflict' | 'approvedNotPublished'; step: PublishStep }
  /**
   * `step` 에서 실패했다(일시 장애 · 권한 없음 · 응답 모양이 다름 등) — 그 앞 단계는 처리됐다. `candidate` 는 처리된 단계까지 반영한
   * 후보(다음 요청에 실을 `version`)다
   */
  | { status: 'failed'; step: PublishStep; candidate: ReviewCandidate; error: unknown }

function advisoryPath(advisoryId: string, action?: 'approve' | 'publish'): string {
  const path = `${ADMIN_ADVISORIES_PATH}/${encodeURIComponent(advisoryId)}`
  return action ? `${path}/${action}` : path
}

/** 403 이면 세션 요약을 다시 맞추고 그대로 거부한다 */
function rejectWith(error: unknown): never {
  syncRoleOnForbidden(error)
  throw error
}

async function requestCandidates(week: string | undefined): Promise<ReviewCandidateList> {
  const response = await apiRequest<ReviewCandidateListResponse>(ADMIN_CANDIDATES_PATH, {
    query: { week },
  })
  return toReviewCandidateList(response)
}

async function requestDetail(candidate: ReviewCandidate): Promise<ReviewCandidate | null> {
  if (candidate.advisoryId === undefined) return candidate
  const detail = await apiRequest<OperatorAdvisoryDetailResponse>(
    advisoryPath(candidate.advisoryId),
  )
  return withAdvisoryDetail(candidate, detail)
}

/** 처리 응답(안내 한 건)을 후보에 덮는다. 이력은 처리 응답에 없다 */
function applyAdvisory(
  candidate: ReviewCandidate,
  advisory: OperatorAdvisoryResponse,
): ReviewCandidate | null {
  return withAdvisoryDetail(candidate, { advisory, histories: [] })
}

/**
 * 충돌 뒤 그 후보의 주 목록을 다시 읽고, 그 후보(동네 · 주)가 있으면 상세까지 다시 읽는다. 목록의 그 행은 상세를 덮은 후보로 바꾸고
 * 그사이 발행됐으면 뺀다. 다시 읽지 못하면 null(403 이면 세션 요약을 다시 맞춘다)
 */
async function refreshCandidate(candidate: ReviewCandidate): Promise<RefreshedCandidate | null> {
  try {
    const list = await requestCandidates(candidate.isoWeek)
    const found = list.candidates.find((item) => item.id === candidate.id)
    const latest = found ? await requestDetail(found) : null
    return {
      list: {
        ...list,
        candidates: list.candidates.flatMap((item) => {
          if (item.id !== candidate.id) return [item]
          return latest ? [latest] : []
        }),
      },
      candidate: latest,
    }
  } catch (error) {
    syncRoleOnForbidden(error)
    return null
  }
}

type Sent =
  | { kind: 'done'; advisory: OperatorAdvisoryResponse }
  | { kind: 'invalid' }
  | { kind: 'conflict'; code: string }
  | { kind: 'failed'; error: unknown }

/** 처리 요청 하나를 보내고 결과를 가른다. 그 밖의 실패는 403 이면 세션 요약을 다시 맞춘다 */
async function send(
  path: string,
  method: 'POST' | 'PUT',
  body: unknown,
  conflictCodes: ReadonlySet<string> = CONFLICT_CODES,
): Promise<Sent> {
  try {
    return {
      kind: 'done',
      advisory: await apiRequest<OperatorAdvisoryResponse>(path, { method, body }),
    }
  } catch (error) {
    const code = errorCode(error)
    if (code !== null && INVALID_CODES.has(code)) return { kind: 'invalid' }
    if (code !== null && conflictCodes.has(code)) return { kind: 'conflict', code }
    syncRoleOnForbidden(error)
    return { kind: 'failed', error }
  }
}

/** 한 요청짜리 처리(초안 만들기 · 수정 저장). 그 밖의 실패는 거부한다 */
async function writeOnce(
  candidate: ReviewCandidate,
  sending: Promise<Sent>,
): Promise<AdvisoryWriteResult> {
  const sent = await sending
  switch (sent.kind) {
    case 'done': {
      try {
        return { status: 'ok', candidate: applyAdvisory(candidate, sent.advisory) }
      } catch {
        // 처리는 됐지만 응답 모양이 계약과 달라 옮기지 못했다 — 거부로 끝내면 화면이 처리할 안내를 모른 채 다시 눌러 초안이 둘 생긴다.
        // 다시 읽어 최신 후보(`advisoryId` 포함)를 준다
        const refreshed = await refreshCandidate(candidate)
        return refreshed
          ? { status: 'ok', candidate: refreshed.candidate }
          : { status: 'unrefreshed', outcome: 'ok' }
      }
    }
    case 'invalid':
      return { status: 'invalid' }
    case 'conflict': {
      const refreshed = await refreshCandidate(candidate)
      return refreshed
        ? { status: 'conflict', ...refreshed }
        : { status: 'unrefreshed', outcome: 'conflict' }
    }
    case 'failed':
      throw sent.error
  }
}

/**
 * 한 주의 검토 후보를 읽는다(`GET /candidates?week=`). `week` 를 주지 않으면 서버 기본(현재 주 − 1)이다 — 기준 주는 응답 `isoWeek` 다.
 * 실패 · 응답 모양이 계약과 다르면 거부한다(403 이면 세션 요약을 다시 맞춘다)
 */
export function fetchReviewCandidates({
  week,
}: { week?: string } = {}): Promise<ReviewCandidateList> {
  return requestCandidates(week).catch(rejectWith)
}

/**
 * 후보의 안내 상세(`GET /{advisoryId}`)를 읽어 제목 · 초안 · 버전 · 안내 상태를 덮는다. 처리할 안내가 없는 후보(대기)는 요청하지 않고
 * 그대로 준다. 그사이 발행됐으면 null(목록에서 뺀다). 실패하면 거부한다
 */
export function fetchCandidateDetail(candidate: ReviewCandidate): Promise<ReviewCandidate | null> {
  return requestDetail(candidate).catch(rejectWith)
}

/**
 * 후보 동네 · 주로 초안을 만든다(`POST` `{ districtCode, isoWeek, title, body }` → DRAFT). 제목 · 본문 모두 필수다.
 * 초안을 만들 수 없거나(`draftable` 이 아님) 이미 처리할 안내가 있으면 요청하지 않고 `rejected` 다
 */
export async function createAdvisoryDraft(
  candidate: ReviewCandidate,
  { title, body }: { title: string; body: string },
): Promise<CreateDraftResult> {
  if (candidate.draftable !== true) return { status: 'rejected', reason: 'not-draftable' }
  if (candidate.advisoryId !== undefined) return { status: 'rejected', reason: 'has-advisory' }
  const request = { districtCode: candidate.districtCode, isoWeek: candidate.isoWeek, title, body }
  return writeOnce(candidate, send(ADMIN_ADVISORIES_PATH, 'POST', request, CREATE_CONFLICT_CODES))
}

/** 제목 · 본문을 고쳐 저장한다(`PUT /{advisoryId}` `{ title, body, version }` → EDITED). 승인된 안내도 EDITED 로 돌아간다 */
export function saveAdvisoryEdit(
  candidate: ProcessableCandidate,
  { title, body }: { title: string; body: string },
): Promise<AdvisoryWriteResult> {
  return writeOnce(
    candidate,
    send(advisoryPath(candidate.advisoryId), 'PUT', { title, body, version: candidate.version }),
  )
}

/** 승인하고 발행이 멈춘 단계의 결과. 충돌이면 다시 읽는다 */
async function stopped(
  sent: Exclude<Sent, { kind: 'done' }>,
  step: PublishStep,
  candidate: ReviewCandidate,
): Promise<ApproveAndPublishResult> {
  switch (sent.kind) {
    case 'invalid':
      return { status: 'invalid', step, candidate }
    case 'failed':
      return { status: 'failed', step, candidate, error: sent.error }
    case 'conflict': {
      const outcome =
        step === 'publish' && sent.code === 'ADVISORY_007' ? 'approvedNotPublished' : 'conflict'
      const refreshed = await refreshCandidate(candidate)
      if (!refreshed) return { status: 'unrefreshed', outcome, step }
      return outcome === 'approvedNotPublished'
        ? { status: outcome, ...refreshed }
        : { status: outcome, step, ...refreshed }
    }
  }
}

/**
 * 승인하고 발행한다 — 백엔드는 두 요청(`approve` → `publish`)이고, 앞 응답의 `version` 을 다음 요청에 싣는다.
 * - 고친 것(`dirty`)이 있으면 먼저 수정 저장(`PUT` → EDITED)한다 — 운영자가 본 제목 · 본문이 발행된다
 * - 이미 승인됨(APPROVED)이고 고친 것이 없으면 승인을 건너뛰고 발행만 한다(승인됨에서 다시 승인하면 `005` 다)
 * - 발행이 `007` 이면 안내는 승인됨으로 남는다(`approvedNotPublished`). 어느 단계에서 멈췄는지는 `step` 이다
 * 단계 실패는 거부하지 않고 `failed` 로 준다 — 앞 단계가 이미 처리돼 버전이 바뀌었을 수 있다
 */
export async function approveAndPublishAdvisory(
  candidate: ProcessableCandidate,
  { title, body, dirty }: { title: string; body: string; dirty: boolean },
): Promise<ApproveAndPublishResult> {
  const { advisoryId } = candidate
  const before: ('save' | 'approve')[] = []
  if (dirty) before.push('save')
  if (dirty || candidate.advisoryStatus !== 'APPROVED') before.push('approve')

  let current: ReviewCandidate = candidate
  let { version } = candidate
  for (const step of before) {
    const sent =
      step === 'save'
        ? await send(advisoryPath(advisoryId), 'PUT', { title, body, version })
        : await send(advisoryPath(advisoryId, 'approve'), 'POST', { version })
    if (sent.kind !== 'done') return stopped(sent, step, current)
    try {
      current = applyAdvisory(current, sent.advisory) ?? current
    } catch (error) {
      // 응답 모양이 계약과 다르다 — 그 단계는 처리됐을 수 있지만 이어 가지 않는다(다시 하면 버전이 달라 충돌 → 다시 읽는다)
      return { status: 'failed', step, candidate: current, error }
    }
    version = sent.advisory.version
  }
  const sent = await send(advisoryPath(advisoryId, 'publish'), 'POST', { version })
  return sent.kind === 'done' ? { status: 'published' } : stopped(sent, 'publish', current)
}
