import { getMockSession, subscribeMockSession } from '@/features/auth/auth-client'
import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import { classifyApiError } from '@/lib/api/error-kind'
import type { DataSource } from '@/lib/data-source'
import { refreshSession } from '@/lib/session/session-store'

import {
  type OperatorAdvisoryResponse,
  type PublicationHistoryResponse,
  toHistoryEntries,
} from './admin-history-api'
import type { MockAdminScenario } from './admin-review-client'
import { createMockHistory, mockCandidateTitle } from './mock'
import type { HistoryEntry, ReviewCandidate, TimelineEvent } from './types'

/* ── 발행 이력 · 정정 · 철회 (A03 `/admin/history`, #220 · 연동 #271) ───────────────────────────────
 *
 * 최근 발행 기록과 발행된 안내의 정정 발행 · 철회. 계약은 docs/api-contract-draft.md "운영자 안내 처리"(#246) · "운영자 정정 · 발행 이력"(#267)이고,
 * 화면 모델(`HistoryEntry`)로 옮기는 규칙은 `admin-history-api.ts` 다.
 *
 * - 실데이터: `GET /api/v1/advisories/admin/history`(끝 주 생략 — 현재 주 − 1 로 끝나는 4주) · `POST /{id}/correct` · `POST /{id}/retract`.
 *   처리한 뒤와 충돌(409) 뒤에는 이력을 **다시 읽어** 그린다(처리 응답 · 오류 봉투에 이력이 없다). 다시 읽지 못해도 처리 결과는 잃지 않는다
 *   (`unrefreshed`). 401 은 API 계층(재발급 · 만료), 403 은 세션 요약을 다시 맞춰 운영자 가드(`AdminGate`)가 권한 없음을 그리게 한다
 * - 목: 모듈 메모리의 이력(목 서버 흉내). 검토 대기 목(`admin-review-client.ts`)의 보류 · 발행이 `recordReviewOutcome` 으로 여기 더해진다 —
 *   같은 목 서버의 두 화면이라 한쪽 결과가 다른 쪽에 이어진다. 정정 · 철회는 `version` 을 맞춰 보고, 다르면 충돌(409)이다.
 *   목은 충돌 응답에 지금 이력을 바로 싣는다(실데이터는 다시 읽는다)
 *
 * **정정 발행**은 한 요청으로 이전 안내를 철회하고 고친 제목 · 본문을 새 안내로 발행해 `corrects_advisory_id` 로 잇는다(PUBLISHED 본문은
 * 고치지 않는다). 사유는 운영자 이력(`memo`)에만 남고 사용자 화면에는 보이지 않는다(#246 · #267 결정).
 */

export type HistoryListResult = { status: 'ready'; entries: readonly HistoryEntry[] }

export type HistoryActionResult =
  /** 처리한 뒤의 이력과 고를 행(정정은 새 안내, 철회는 그 안내) */
  | { status: 'ok'; entries: readonly HistoryEntry[]; selectedId: string }
  /** 다른 운영자가 먼저 바꿨다(409 · 없는 안내) — 지금 서버의 이력 */
  | { status: 'conflict'; entries: readonly HistoryEntry[] }
  /** 서버가 입력을 받지 않았다(검증 400). 화면이 먼저 막아 보통은 닿지 않는다 */
  | { status: 'invalid' }
  /**
   * 처리(`ok`) 또는 충돌(`conflict`)까지는 알지만 이력을 다시 읽지 못했다(일시 장애 등). 처리 결과를 알리고 목록은 다시 읽게 한다 —
   * 옛 목록을 그대로 두면 이미 정정 · 철회한 안내의 버튼이 남는다. `selectedId` 는 다시 읽은 뒤 고를 행이다
   */
  | { status: 'unrefreshed'; outcome: 'ok' | 'conflict'; selectedId: string | null }

/** 운영자 안내 처리 · 발행 이력 API 앞부분 */
export const ADMIN_ADVISORIES_PATH = '/api/v1/advisories/admin'

/**
 * 다시 읽어 그릴 처리 거부 — 지금 상태에서 할 수 없음(`005` — 이미 정정 · 철회), 버전 다름(`006`), 같은 동네 · 주에 발행 중인 안내가 있음
 * (`007`), 없는 안내(`001`). 거부되면 아무것도 바뀌지 않는다
 */
const CONFLICT_CODES: ReadonlySet<string> = new Set([
  'ADVISORY_001',
  'ADVISORY_005',
  'ADVISORY_006',
  'ADVISORY_007',
])

/** 입력 검증 거부 — 본문 형식(`100`), 제목 · 본문 · 사유 · version(`105`~`113`), 숫자가 아닌 ID(`198`) */
const INVALID_CODES: ReadonlySet<string> = new Set([
  'ADVISORY_100',
  ...Array.from({ length: 9 }, (_, index) => `ADVISORY_${105 + index}`),
  'ADVISORY_198',
])

function errorCode(error: unknown): string | null {
  return error instanceof ApiError ? error.code : null
}

/** 권한 없음(403)이면 세션 요약을 다시 맞춘다 — 역할이 바뀌었으면 운영자 가드가 권한 없음을 그린다. 오류는 그대로 둔다 */
function syncRoleOnForbidden(error: unknown): void {
  if (classifyApiError(error) === 'forbidden') void refreshSession()
}

async function fetchHistory(): Promise<HistoryEntry[]> {
  const response = await apiRequest<PublicationHistoryResponse>(`${ADMIN_ADVISORIES_PATH}/history`)
  return toHistoryEntries(response)
}

/**
 * 처리 요청 하나를 보내고 이력을 다시 읽는다. 충돌 · 없는 안내면 다시 읽어 `conflict`, 검증 거부면 `invalid`, 그 밖의 실패는 거부한다
 * (화면이 대화상자 안에 실패를 알린다)
 */
async function writeApi(path: string, body: unknown): Promise<HistoryActionResult> {
  // 처리한 안내 — 정정은 새 안내, 철회는 그 안내다. 충돌이면 null
  let selectedId: string | null
  try {
    const advisory = await apiRequest<OperatorAdvisoryResponse>(path, { method: 'POST', body })
    selectedId = advisory.advisoryId
  } catch (error) {
    const code = errorCode(error)
    if (code !== null && INVALID_CODES.has(code)) return { status: 'invalid' }
    if (code === null || !CONFLICT_CODES.has(code)) {
      syncRoleOnForbidden(error)
      throw error
    }
    selectedId = null
  }
  let entries: HistoryEntry[]
  try {
    entries = await fetchHistory()
  } catch (error) {
    syncRoleOnForbidden(error)
    return { status: 'unrefreshed', outcome: selectedId === null ? 'conflict' : 'ok', selectedId }
  }
  return selectedId === null
    ? { status: 'conflict', entries }
    : { status: 'ok', entries, selectedId }
}

type MockStore = {
  entries: HistoryEntry[]
  failWrites: boolean
  staleOnList: boolean
  serial: number
}

let mockStore: MockStore | null = null

// 목 세션이 비회원이 되면 지운다(검토 대기 목과 같다)
subscribeMockSession(() => {
  if (getMockSession() === 'guest') mockStore = null
})

function seed(scenario: MockAdminScenario | null): MockStore {
  return {
    entries: scenario === 'empty' ? [] : createMockHistory(),
    failWrites: scenario === 'fail',
    staleOnList: scenario === 'conflict',
    serial: 0,
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

/** 최근 처리한 것부터. 같은 시각이면 더한 순서다 */
function sorted(entries: readonly HistoryEntry[]): HistoryEntry[] {
  return [...entries].sort((a, b) => b.processedAt.localeCompare(a.processedAt))
}

const KST_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/** 그 시각의 한국 날짜(`YYYY-MM-DD`) — 동네 안내의 정정일과 같은 모양 */
export function kstDate(instant: Date): string {
  return KST_DATE.format(instant)
}

/**
 * 발행 이력을 읽는다(최근 것부터). 실데이터는 이력 API 이고 실패하면 거부한다(화면이 `다시 시도` 를 보인다) — 재현 쿼리는 듣지 않는다.
 * `scenario` 는 목 재현이다(검토 대기의 `?mock-admin=` 과 같다) — 주어지면 목 이력을 그 상태로 다시
 * 시작하고, 없으면 이력은 그대로 두고 재현만 끈다. `conflict` 는 목록을 준 직후 모든 행의 `version` 을 올린다(다른 운영자가 그사이 바꿈)
 */
export function listHistory(
  source: DataSource,
  { scenario = null }: { scenario?: MockAdminScenario | null } = {},
): Promise<HistoryListResult> {
  if (source === 'api') {
    return fetchHistory().then(
      (entries) => ({ status: 'ready', entries }),
      (error: unknown) => {
        syncRoleOnForbidden(error)
        throw error
      },
    )
  }
  return respond(() => {
    if (scenario) mockStore = seed(scenario)
    else Object.assign(store(), { failWrites: false, staleOnList: false })
    const current = store()
    const entries = sorted(current.entries)
    if (current.staleOnList) {
      current.entries = current.entries.map((entry) => ({ ...entry, version: entry.version + 1 }))
    }
    return { status: 'ready', entries }
  })
}

type Change = (entry: HistoryEntry, now: Date) => { entries: HistoryEntry[]; selectedId: string }

/** 목 서버의 처리 하나. 발행 중이 아니거나(이미 정정 · 철회) 버전이 다르면 충돌, 재현 `fail` 이면 거부한다 */
function writeMock(id: string, version: number, change: Change): Promise<HistoryActionResult> {
  return respond(() => {
    const current = store()
    if (current.failWrites) throw new Error('mock: history write failed')
    const entry = current.entries.find((item) => item.id === id)
    if (!entry || entry.outcome !== 'published' || entry.version !== version) {
      return { status: 'conflict', entries: sorted(current.entries) }
    }
    const next = change(entry, new Date())
    current.entries = next.entries
    return { status: 'ok', entries: sorted(current.entries), selectedId: next.selectedId }
  })
}

/**
 * 발행된 안내를 정정 발행한다(`POST /{id}/correct`). 이전 안내는 `정정됨`(타임라인에 정정 · 사유)으로 내려가고, 고친 제목 · 본문이 새 안내로
 * 발행된다 — 새 안내는 정정일 · 사유(`correction`)를 갖고 사용자 화면(S07)에는 정정일만 보인다(사유는 운영자에게만). 새 안내를 고른다
 */
export function correctAdvisory(
  id: string,
  {
    title,
    body,
    reason,
    version,
  }: { title: string; body: string; reason: string; version: number },
  source: DataSource,
): Promise<HistoryActionResult> {
  if (source === 'api') {
    return writeApi(`${ADMIN_ADVISORIES_PATH}/${encodeURIComponent(id)}/correct`, {
      title,
      body,
      reason,
      version,
    })
  }
  return writeMock(id, version, (entry, now) => {
    const current = store()
    current.serial += 1
    const at = now.toISOString()
    const newId = `${entry.id}-correction-${current.serial}`
    const corrected: HistoryEntry = {
      ...entry,
      outcome: 'corrected',
      version: entry.version + 1,
      timeline: [...entry.timeline, { step: 'corrected', at, note: reason }],
    }
    const correction: HistoryEntry = {
      id: newId,
      ...(entry.kind && { kind: entry.kind }),
      districtCode: entry.districtCode,
      districtName: entry.districtName,
      isoWeek: entry.isoWeek,
      // 인용값(advisory `cited_*`)은 이전 안내의 것을 그대로 옮긴다 — 백엔드 정정(#267)도 같다(집계가 다시 계산돼도 같은 근거,
      // 본문 숫자는 검증하지 않는다 — api-contract-draft.md "운영자 정정 · 발행 이력")
      participants: entry.participants,
      symptomRate: entry.symptomRate,
      outcome: 'published',
      processedAt: at,
      edited: false,
      title,
      body,
      version: 1,
      timeline: [{ step: 'correction-published', at, note: reason }],
      correction: { previousId: entry.id, correctedOn: kstDate(now), reason },
    }
    return {
      entries: [...current.entries.map((item) => (item.id === id ? corrected : item)), correction],
      selectedId: newId,
    }
  })
}

/**
 * 발행된 안내를 철회한다(`POST /{id}/retract` — RETRACTED, 끝 상태). 사유는 타임라인에 남고(운영자에게만), 사용자 화면에는 철회된 안내로
 * 남는다. 그 안내를 고른다
 */
export function retractAdvisory(
  id: string,
  { reason, version }: { reason: string; version: number },
  source: DataSource,
): Promise<HistoryActionResult> {
  if (source === 'api') {
    return writeApi(`${ADMIN_ADVISORIES_PATH}/${encodeURIComponent(id)}/retract`, {
      reason,
      version,
    })
  }
  return writeMock(id, version, (entry, now) => {
    const retracted: HistoryEntry = {
      ...entry,
      outcome: 'retracted',
      version: entry.version + 1,
      timeline: [...entry.timeline, { step: 'retracted', at: now.toISOString(), note: reason }],
      retraction: { retractedOn: kstDate(now), reason },
    }
    return {
      entries: store().entries.map((item) => (item.id === id ? retracted : item)),
      selectedId: id,
    }
  })
}

/**
 * 검토 대기 목의 보류 · 발행을 이력에 남긴다(목 서버 안의 일 — `admin-review-client.ts` 만 부른다). 같은 후보는 한 행이다 —
 * 보류한 후보를 나중에 발행하면 그 행이 발행으로 바뀐다. `log` 는 그 후보의 처리 기록이고 마지막이 보류 · 발행이다.
 * 검토 시간은 목 후보에 검토 시작 시각이 있을 때만 센다(백엔드에는 저장하는 곳이 없다). 목 후보는 늘 증상 보고 비율 · 초안이 있다 —
 * 없으면(자료 부족 · 안내 없음) 이력 행을 만들 수 없어 남기지 않는다.
 *
 * 그 행이 이미 정정됨 · 철회(끝 상태)면 덮지 않는다 — 검토 대기의 `?mock-admin=` 재설정 뒤 같은 후보를 다시 발행해도 끝 상태가
 * 되살아나지 않고, 같은 동네 · 주에 발행 중인 안내가 둘 생기지 않게 한다
 */
export function recordReviewOutcome(
  candidate: ReviewCandidate,
  log: readonly TimelineEvent[],
): void {
  const last = log.at(-1)
  if (!last || (last.step !== 'held' && last.step !== 'published')) return
  const outcome = last.step
  const { symptomRate, draft } = candidate
  if (symptomRate === undefined || draft === undefined) return
  const started = candidate.reviewStartedAt ? Date.parse(candidate.reviewStartedAt) : Number.NaN
  const minutes = Math.round((Date.parse(last.at) - started) / 60_000)
  const entry: HistoryEntry = {
    id: candidate.id,
    ...(candidate.kind && { kind: candidate.kind }),
    districtCode: candidate.districtCode,
    districtName: candidate.districtName,
    isoWeek: candidate.isoWeek,
    participants: candidate.participants,
    symptomRate,
    outcome,
    processedAt: last.at,
    edited: outcome === 'published' && log.some((event) => event.step === 'edited'),
    title: mockCandidateTitle(candidate),
    body: draft,
    version: 1,
    timeline: [...log],
    ...(Number.isFinite(minutes) && { reviewMinutes: Math.max(0, minutes) }),
  }
  const current = store()
  const existing = current.entries.find((item) => item.id === candidate.id)
  if (existing && (existing.outcome === 'corrected' || existing.outcome === 'retracted')) return
  current.entries = [...current.entries.filter((item) => item.id !== candidate.id), entry]
}

export type HistorySummary = {
  /** 후보에서 발행한 안내 수 — 지금 정정됨 · 철회여도 센다. 정정 발행(같은 안내를 바로잡은 새 안내)은 세지 않는다 */
  published: number
  held: number
  /** 평균 검토 시간(분, 반올림). 검토 시간이 있는 행이 없으면 null — 1단계에 없는 값이라 칸을 숨긴다 */
  averageReviewMinutes: number | null
  /** 발행한 안내 중 운영자 수정(EDITED)을 거친 비율(%, 반올림). 발행이 없으면 null */
  editedRate: number | null
}

/** 요약 칸. 목록에서 계산한다(고정값을 두지 않는다) */
export function summarizeHistory(entries: readonly HistoryEntry[]): HistorySummary {
  const fromCandidates = entries.filter((entry) => !entry.correction)
  const published = fromCandidates.filter((entry) => entry.outcome !== 'held')
  const minutes = fromCandidates.flatMap((entry) =>
    entry.reviewMinutes === undefined ? [] : [entry.reviewMinutes],
  )
  return {
    published: published.length,
    held: fromCandidates.length - published.length,
    averageReviewMinutes:
      minutes.length === 0
        ? null
        : Math.round(minutes.reduce((sum, value) => sum + value, 0) / minutes.length),
    editedRate:
      published.length === 0
        ? null
        : Math.round((published.filter((entry) => entry.edited).length / published.length) * 100),
  }
}

/** 테스트에서 목 이력을 처음(시안 이력)으로 되돌린다. 화면 코드는 부르지 않는다 */
export function resetMockHistory(): void {
  mockStore = null
}
