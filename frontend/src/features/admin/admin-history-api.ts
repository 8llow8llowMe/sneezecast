import type { HistoryEntry, TimelineEvent, TimelineStep } from './types'

/*
 * 운영자 발행 이력(docs/api-contract-draft.md "운영자 정정 · 발행 이력", 백엔드 #267 · 연동 #271)의 응답 모양과 화면 모델(`HistoryEntry`)로
 * 옮기는 순수 함수. 요청은 `admin-history-client.ts` 가 보낸다.
 *
 * **응답에 없는 값은 지어내지 않는다**: 후보 유형(`kind`) · 담당 이름 · 검토 시간 · 타임라인 세부 문구(정정 · 철회 사유 밖)는 비워 둔다 —
 * 화면이 그 열 · 칸 · 문구를 숨긴다. 보류 행은 서버에 없다(보류는 기록되지 않는다 — 2단계 `review_signal`).
 */

/** `{ code, name, description }` — 상태 · 단계 */
type CodeName = { code: string; name: string; description: string }

/** 운영자가 보는 안내 한 건(backend `AdvisoryOperatorResponse`). 처리 응답 · 상세 · 이력 항목이 같은 모양이다 */
export type OperatorAdvisoryResponse = {
  advisoryId: string
  districtCode: string
  isoWeek: string
  status: CodeName
  title: string
  body: string
  /** 초안을 만들 때 고정한 인용 **수**(비율은 싣지 않는다) */
  citation: {
    level: CodeName
    participantCount: number
    symptomaticCount: number
    respiratoryCount: number
    entericCount: number
    ruleVersion: string
  }
  /** 이 안내가 정정한 이전 안내. 정정 발행이 아니면 null */
  correctsAdvisoryId: string | null
  publishedAt: string | null
  retractedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/** 안내 이력 한 행(backend `AdvisoryHistoryItem`). 생성이면 `fromStatus` 가 null 이다 */
export type AdvisoryHistoryRowResponse = {
  historyId: string
  fromStatus: CodeName | null
  toStatus: CodeName
  title: string
  body: string
  operatorId: string
  aiModel: string | null
  promptVersion: string | null
  /** 수정 · 정정 · 철회 사유(운영자 메모). 사용자 화면에는 보이지 않는다 */
  memo: string | null
  createdAt: string
}

/** 발행 이력 한 항목(backend `AdvisoryPublicationHistoryItem`) */
export type PublicationHistoryItemResponse = {
  advisory: OperatorAdvisoryResponse
  /** 행정동 행이 없으면 null */
  districtName: string | null
  sigungu: string | null
  /** 이 안내를 정정한 새 안내. 있으면 이 안내는 정정으로 내려간 것이다(정정됨) */
  correctedByAdvisoryId: string | null
  /** 오래된 것부터 */
  histories: AdvisoryHistoryRowResponse[]
}

/** `GET /api/v1/advisories/admin/history` 의 `dataBody` */
export type PublicationHistoryResponse = {
  fromIsoWeek: string
  toIsoWeek: string
  /** 발행 시각 최근 것부터 */
  entries: PublicationHistoryItemResponse[]
}

const KST_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/** UTC 시각(ISO-8601)의 한국 날짜(`YYYY-MM-DD`). 없거나 읽을 수 없으면 빈 문자열이다(동네 안내 매핑과 같다) */
function kstDateOf(instant: string | null): string {
  if (instant === null) return ''
  const date = new Date(instant)
  return Number.isNaN(date.getTime()) ? '' : KST_DATE.format(date)
}

/**
 * 인용 수로 계산한 비율 — 퍼센트, 소수 첫째 자리, 반올림(HALF_UP). 공개 집계 · 동네 안내의 `symptomRate`(backend `AggregateRate`)와 같은
 * 반올림이다(20 / 120 → 16.7, 1 / 16 → 6.3). 분모가 0 이하이거나 분자가 범위 밖이면 null 이다
 */
export function citedRate(count: number, whole: number): number | null {
  if (!Number.isInteger(count) || !Number.isInteger(whole)) return null
  if (whole <= 0 || count < 0 || count > whole) return null
  // 정수끼리 나눈 값이 정확히 x.5 면 부동소수로도 정확하다 — Math.round 가 양수에서 HALF_UP 이다
  return Math.round((count * 1000) / whole) / 10
}

/** 비어 있지 않은 사유만 문구로 쓴다 */
function memoOf(row: AdvisoryHistoryRowResponse | undefined): string | undefined {
  const memo = row?.memo?.trim()
  return memo ? memo : undefined
}

/**
 * 이력 행을 타임라인으로 옮긴다(오래된 것부터).
 * - 생성(null → DRAFT) `registered`, AI_DRAFTED `ai-drafted`, EDITED `edited`, PUBLISHED `published`
 * - **승인(APPROVED)은 발행과 묶는다** — 따로 그리지 않는다(시안 `승인·발행`)
 * - 정정한 새 안내는 생성(null → APPROVED) 행을 발행 행과 묶어 `correction-published` 하나다(사유는 생성 행의 memo)
 * - RETRACTED 는 정정됨이면 `corrected`, 아니면 `retracted`(사유는 그 행의 memo)
 * - 모르는 상태로 가는 행은 뺀다. 세부 문구는 정정 · 철회 사유뿐이다(수정 메모는 싣지 않는다)
 */
function toTimeline(
  histories: readonly AdvisoryHistoryRowResponse[],
  { correction, corrected }: { correction: boolean; corrected: boolean },
): TimelineEvent[] {
  const creation = histories.find((row) => row.fromStatus === null)
  const events: TimelineEvent[] = []
  const push = (step: TimelineStep, at: string, note?: string) => {
    events.push(note === undefined ? { step, at } : { step, at, note })
  }
  for (const row of histories) {
    if (row.fromStatus === null) {
      if (!correction) push('registered', row.createdAt)
      continue
    }
    switch (row.toStatus.code) {
      case 'AI_DRAFTED':
        push('ai-drafted', row.createdAt)
        break
      case 'EDITED':
        push('edited', row.createdAt)
        break
      case 'PUBLISHED':
        if (correction) push('correction-published', row.createdAt, memoOf(creation))
        else push('published', row.createdAt)
        break
      case 'RETRACTED':
        push(corrected ? 'corrected' : 'retracted', row.createdAt, memoOf(row))
        break
      default:
        // APPROVED(발행과 묶음) · 모르는 상태
        break
    }
  }
  return events
}

/**
 * 발행 이력 한 항목을 화면 모델로 옮긴다. 발행됨 · 철회됨이 아니거나 인용 수로 비율을 낼 수 없으면(계약과 다름) null 이다 —
 * 부르는 쪽이 그 행만 뺀다(한 행 때문에 운영자 화면 전체를 막지 않는다).
 */
export function toHistoryEntry(item: PublicationHistoryItemResponse): HistoryEntry | null {
  const { advisory, histories } = item
  const status = advisory.status.code
  if (status !== 'PUBLISHED' && status !== 'RETRACTED') return null
  const symptomRate = citedRate(
    advisory.citation.symptomaticCount,
    advisory.citation.participantCount,
  )
  if (symptomRate === null) return null

  const outcome =
    status === 'PUBLISHED' ? 'published' : item.correctedByAdvisoryId ? 'corrected' : 'retracted'
  const correction = advisory.correctsAdvisoryId !== null
  const firstPublished = histories.findIndex((row) => row.toStatus.code === 'PUBLISHED')
  const beforePublish = firstPublished < 0 ? histories : histories.slice(0, firstPublished)
  const retractedRow = histories.findLast((row) => row.toStatus.code === 'RETRACTED')

  return {
    id: advisory.advisoryId,
    // 행정동 행이 없으면(이름을 모름) 코드로 보인다 — 이름을 지어내지 않는다
    districtCode: advisory.districtCode,
    districtName: item.districtName ?? advisory.districtCode,
    isoWeek: advisory.isoWeek,
    participants: advisory.citation.participantCount,
    symptomRate,
    outcome,
    processedAt: advisory.publishedAt ?? '',
    edited: beforePublish.some((row) => row.toStatus.code === 'EDITED'),
    title: advisory.title,
    body: advisory.body,
    version: advisory.version,
    timeline: toTimeline(histories, { correction, corrected: outcome === 'corrected' }),
    ...(advisory.correctsAdvisoryId !== null && {
      correction: {
        previousId: advisory.correctsAdvisoryId,
        correctedOn: kstDateOf(advisory.publishedAt),
        reason: memoOf(histories.find((row) => row.fromStatus === null)) ?? '',
      },
    }),
    ...(outcome === 'retracted' && {
      retraction: {
        retractedOn: kstDateOf(advisory.retractedAt),
        reason: memoOf(retractedRow) ?? '',
      },
    }),
  }
}

/** 발행 이력 응답의 항목들(서버 순서 — 발행 시각 최근 것부터). 옮길 수 없는 항목은 뺀다 */
export function toHistoryEntries(response: PublicationHistoryResponse): HistoryEntry[] {
  return response.entries.flatMap((item) => {
    const entry = toHistoryEntry(item)
    return entry ? [entry] : []
  })
}
