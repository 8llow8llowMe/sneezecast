import { getMockSession, subscribeMockSession } from '@/features/auth/auth-client'
import type { DataSource } from '@/lib/data-source'

import type { MockAdminScenario } from './admin-review-client'
import { createMockHistory } from './mock'
import type { HistoryEntry, ReviewCandidate, TimelineEvent } from './types'

/* ── 발행 이력 · 정정 · 철회 (A03 `/admin/history`, #220) ─────────────────────────────────────────
 *
 * 최근 발행 · 보류 기록과 발행된 안내의 정정 발행 · 철회. **BE 미정이다(#214 · #215)** — 화면이 가정한 모양은
 * docs/api-contract-draft.md "발행 이력 (#220, 프론트 초안)" 이다.
 *
 * - 실데이터: 요청하지 않고 `unavailable` 이다(검토 대기와 같은 까닭 — 목 안내를 철회해도 시민 화면은 바뀌지 않는다)
 * - 목: 모듈 메모리의 이력(목 서버 흉내). 검토 대기 목(`admin-review-client.ts`)의 보류 · 발행이 `recordReviewOutcome` 으로 여기 더해진다 —
 *   같은 목 서버의 두 화면이라 한쪽 결과가 다른 쪽에 이어진다. 정정 · 철회는 `version` 을 맞춰 보고, 다르면 충돌(409)이다
 *
 * **정정 발행은 백엔드 §2-5 에 없다**(PUBLISHED 본문은 고치지 않는다 — 철회하고 새 안내를 만든다). 목은 그 모양대로 이전 안내를
 * `정정됨`(정정일 · 사유)으로 내리고 고친 본문을 새 안내로 발행한다. 백엔드는 두 안내를 잇는 값(예: `corrects_advisory_id`)이 필요하다.
 */

export type HistoryListResult =
  | { status: 'ready'; entries: readonly HistoryEntry[] }
  /** 실데이터 — 운영자 API 가 아직 없다 */
  | { status: 'unavailable' }

export type HistoryActionResult =
  /** 처리한 뒤의 이력과 고를 행(정정은 새 안내, 철회는 그 안내) */
  | { status: 'ok'; entries: readonly HistoryEntry[]; selectedId: string }
  /** 다른 운영자가 먼저 바꿨다(409) — 지금 서버의 이력 */
  | { status: 'conflict'; entries: readonly HistoryEntry[] }
  | { status: 'unavailable' }

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
 * 발행 이력을 읽는다(최근 것부터). `scenario` 는 목 재현이다(검토 대기의 `?mock-admin=` 과 같다) — 주어지면 목 이력을 그 상태로 다시
 * 시작하고, 없으면 이력은 그대로 두고 재현만 끈다. `conflict` 는 목록을 준 직후 모든 행의 `version` 을 올린다(다른 운영자가 그사이 바꿈)
 */
export function listHistory(
  source: DataSource,
  { scenario = null }: { scenario?: MockAdminScenario | null } = {},
): Promise<HistoryListResult> {
  return respond(() => {
    if (source === 'api') return { status: 'unavailable' }
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
function write(
  id: string,
  version: number,
  source: DataSource,
  change: Change,
): Promise<HistoryActionResult> {
  return respond(() => {
    if (source === 'api') return { status: 'unavailable' }
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
 * 발행된 안내를 정정 발행한다. 이전 안내는 `정정됨`(타임라인에 정정 · 사유)으로 내려가고, 고친 본문이 새 안내로 발행된다 —
 * 새 안내는 정정일 · 사유(`correction`)를 갖고 사용자 화면(S07)의 정정 이력이 된다. 새 안내를 고른다
 */
export function correctAdvisory(
  id: string,
  { body, reason, version }: { body: string; reason: string; version: number },
  source: DataSource,
): Promise<HistoryActionResult> {
  return write(id, version, source, (entry, now) => {
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
      kind: entry.kind,
      districtCode: entry.districtCode,
      districtName: entry.districtName,
      isoWeek: entry.isoWeek,
      // 인용값(advisory `cited_*`)은 이전 안내의 것을 그대로 옮긴다. 인용값은 초안 생성 때 마감 집계에서 복사해 고정하고
      // (backend §2-3) 마감 집계는 바뀌지 않아, 숫자를 바로잡는 정정이 무엇을 바꾸는지는 계약에 없다 — api-contract-draft.md #220 "정할 것"
      participants: entry.participants,
      symptomRate: entry.symptomRate,
      outcome: 'published',
      processedAt: at,
      edited: false,
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

/** 발행된 안내를 철회한다(RETRACTED — 끝 상태). 사유는 타임라인에 남고, 사용자 화면에는 철회된 안내로 남는다 */
export function retractAdvisory(
  id: string,
  { reason, version }: { reason: string; version: number },
  source: DataSource,
): Promise<HistoryActionResult> {
  return write(id, version, source, (entry, now) => {
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
 * 검토 시간은 목 후보에 검토 시작 시각이 있을 때만 센다(백엔드에는 저장하는 곳이 없다).
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
  const started = candidate.reviewStartedAt ? Date.parse(candidate.reviewStartedAt) : Number.NaN
  const minutes = Math.round((Date.parse(last.at) - started) / 60_000)
  const entry: HistoryEntry = {
    id: candidate.id,
    kind: candidate.kind,
    districtCode: candidate.districtCode,
    districtName: candidate.districtName,
    isoWeek: candidate.isoWeek,
    participants: candidate.participants,
    symptomRate: candidate.symptomRate,
    outcome,
    processedAt: last.at,
    edited: outcome === 'published' && log.some((event) => event.step === 'edited'),
    body: candidate.draft,
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
