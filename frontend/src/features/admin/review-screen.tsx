'use client'

import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'next/navigation'

import clsx from 'clsx'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { formatIsoWeekOfMonth, shiftIsoWeek } from '@/lib/iso-week'
import { STATUS_TEXT_CLASS } from '@/lib/status'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import {
  type AdvisoryWriteResult,
  approveAndPublishAdvisory,
  type ApproveAndPublishResult,
  type CandidateActionResult,
  createAdvisoryDraft,
  type CreateDraftResult,
  fetchCandidateDetail,
  hasOpenAdvisory,
  holdCandidate,
  listReviewCandidates,
  MOCK_ADMIN_PARAM,
  parseMockAdminScenario,
  publishCandidate,
  reviewWeekBounds,
  reviewWeekError,
  saveAdvisoryEdit,
  saveCandidateDraft,
} from './admin-review-client'
import { AdminShell } from './admin-shell'
import {
  type CandidateAction,
  CandidateDetail,
  type DetailMessage,
  type DraftInput,
  type DraftStatus,
} from './candidate-detail'
import { mockTitleOf } from './mock'
import {
  CANDIDATE_KIND_LABEL,
  CANDIDATE_KINDS,
  CANDIDATE_STATE_LABEL,
  type CandidateKind,
  type CandidateState,
  type ReviewCandidate,
  type ReviewCandidateList,
} from './types'

type Filter = CandidateKind | 'all'

/** 목록 설명 줄의 후보 기준 — 응답의 지금 기준(참여 급증 · 반복 보고 %)으로 그린다. 기준선 변화는 집계 단계(조금 · 많이 늘었어요)다 */
function criteriaText({
  surgeChangePercent,
  repeatRevisedPercent,
}: ReviewCandidateList['criteria']) {
  return `후보 기준: 표본 100명 이상에서 기준선 대비 증가, 참여 급증(전주 대비 +${surgeChangePercent}% 이상), 반복 보고 의심(수정된 보고 ${repeatRevisedPercent}% 이상)`
}

/** 유형 필터 · 유형별 수는 `signals`(신호 전체)로 센다 — 신호가 없는 후보는 `전체` 에만 든다(#272). 신호 값이 없으면 대표 유형으로 본다 */
function hasKind(candidate: ReviewCandidate, kind: CandidateKind): boolean {
  return (candidate.signals ?? (candidate.kind ? [candidate.kind] : [])).includes(kind)
}

function filterBy(list: readonly ReviewCandidate[], filter: Filter): readonly ReviewCandidate[] {
  return filter === 'all' ? list : list.filter((item) => hasKind(item, filter))
}

const STATE_CHIP_CLASS: Record<CandidateState, string> = {
  reviewing: 'bg-brand text-bg',
  waiting: 'bg-section text-fg-sub',
  held: 'border-hairline border-inactive-bar bg-bg text-fg-sub',
}

/** 기준선 대비 (`+9%p`). 늘었으면 시안처럼 주황 글자다 */
function formatDelta(value: number): string {
  return `${value > 0 ? '+' : ''}${value}%p`
}

/** 좁은 화면(데스크톱 미만)이면 상세가 목록 아래에 있다 — 후보를 고르면 상세로 내려 준다 */
function isStacked(): boolean {
  return typeof window.matchMedia === 'function' && !window.matchMedia('(min-width: 80rem)').matches
}

const DONE_MESSAGES: Record<Exclude<CandidateAction, 'publish'>, string> = {
  create: '초안을 만들었어요.',
  save: '고친 초안을 저장했어요.',
  hold: '이 후보를 보류했어요. 이번 주에는 안내를 내지 않아요.',
}

const FAILED_MESSAGES: Record<CandidateAction, string> = {
  create: '초안을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.',
  save: '초안을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
  hold: '보류하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
  publish: '발행하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
}

const INVALID_MESSAGES: Record<CandidateAction, string> = {
  create: '초안을 만들지 못했어요. 입력한 내용을 확인해 주세요.',
  save: '초안을 저장하지 못했어요. 입력한 내용을 확인해 주세요.',
  hold: '보류하지 못했어요. 입력한 내용을 확인해 주세요.',
  publish: '발행하지 못했어요. 입력한 내용을 확인해 주세요.',
}

const CONFLICT_MESSAGE =
  '다른 운영자가 먼저 이 후보를 고쳤어요. 최신 내용으로 바꿔 두었어요. 확인한 뒤 다시 시도해 주세요.'

const goneMessage = (name: string) => `다른 운영자가 ${name} 후보를 먼저 발행했어요.`

/** 초안 만들기 충돌 뒤 그 후보가 목록에 없다 — 발행됐는지 알 수 없다(후보 조건이 바뀌었을 수도 있다) */
const NOT_CANDIDATE_MESSAGE = '이 후보는 더 이상 검토 대상이 아니에요. 목록을 다시 확인해 주세요.'

/** 발행이 `ADVISORY_007` 로 거부됨 — 안내는 승인됨으로 남는다 */
const approvedOnlyMessage = (name: string) =>
  `${name} 안내는 승인됨으로 남았어요. 같은 동네 · 같은 주에 이미 발행된 안내가 있어 발행하지 못했어요.`

/** 승인하고 발행이 단계 실패로 멈췄을 때. 그 앞 단계(고친 내용 저장 · 승인)가 처리됐으면 그 사실을 함께 알린다 */
function stoppedMessage(step: 'save' | 'approve' | 'publish', dirty: boolean): string {
  if (step === 'save')
    return '고친 내용을 저장하지 못해 발행을 멈췄어요. 잠시 뒤 다시 시도해 주세요.'
  if (step === 'approve') {
    return `${dirty ? '고친 내용은 저장했어요. ' : ''}승인하지 못해 발행을 멈췄어요. 잠시 뒤 다시 시도해 주세요.`
  }
  return '승인은 됐지만 발행하지 못했어요. 잠시 뒤 다시 시도해 주세요.'
}

/**
 * 처리 결과를 화면이 그릴 갈래로 옮긴 것. 목 · 실데이터의 결과 모양이 달라 여기서 맞춘다.
 * - updated: 그 후보를 바꿔 그 자리에 둔다(null 이면 목록에서 뺀다 — 발행됨)
 * - published: 발행해 목록에서 빠진다
 * - refreshed: 다른 운영자가 먼저 바꿨거나(충돌) 승인됨으로 남았다 — 다시 읽은 목록(실데이터)과 그 후보로 바꾼다
 * - message: 후보는 그대로 두고 상세에 알린다(검증 거부 · 요청하지 않음 · 실패)
 * - unrefreshed: 처리(또는 충돌)는 서버에서 끝났을 수 있는데 목록을 다시 읽지 못했다 — 알리고 목록을 다시 읽게 한다
 */
type Outcome =
  | { kind: 'updated'; candidate: ReviewCandidate | null; message?: DetailMessage }
  | { kind: 'published' }
  | {
      kind: 'refreshed'
      list?: readonly ReviewCandidate[]
      candidate: ReviewCandidate | null
      message: string
      goneNotice: string
    }
  | { kind: 'message'; message: DetailMessage }
  | { kind: 'unrefreshed'; notice: DetailMessage }

/** 목 처리 결과 */
function mockOutcome(
  action: CandidateAction,
  target: ReviewCandidate,
  result: CandidateActionResult,
): Outcome {
  if (result.status === 'unavailable') {
    return { kind: 'message', message: { tone: 'danger', text: FAILED_MESSAGES[action] } }
  }
  if (result.status === 'conflict') {
    return {
      kind: 'refreshed',
      candidate: result.candidate,
      message: CONFLICT_MESSAGE,
      goneNotice: goneMessage(target.districtName),
    }
  }
  if (action === 'publish') return { kind: 'published' }
  return {
    kind: 'updated',
    candidate: result.candidate,
    message: { tone: 'info', text: DONE_MESSAGES[action] },
  }
}

/** 실데이터 초안 만들기 · 수정 저장 결과 */
function writeOutcome(
  action: 'create' | 'save',
  target: ReviewCandidate,
  result: AdvisoryWriteResult | CreateDraftResult,
): Outcome {
  switch (result.status) {
    case 'ok':
      return {
        kind: 'updated',
        candidate: result.candidate,
        message: { tone: 'info', text: DONE_MESSAGES[action] },
      }
    case 'conflict':
      return {
        kind: 'refreshed',
        list: result.list.candidates,
        candidate: result.candidate,
        message: CONFLICT_MESSAGE,
        goneNotice: action === 'create' ? NOT_CANDIDATE_MESSAGE : goneMessage(target.districtName),
      }
    case 'invalid':
      return { kind: 'message', message: { tone: 'danger', text: INVALID_MESSAGES[action] } }
    case 'rejected':
      return {
        kind: 'message',
        message: {
          tone: 'neutral',
          text:
            result.reason === 'has-advisory'
              ? '이 후보에는 이미 검토 중인 안내가 있어요. 새로고침한 뒤 다시 확인해 주세요.'
              : '이 후보로는 초안을 만들 수 없어요. 자료가 부족하거나 집계가 아직 마감되지 않았어요.',
        },
      }
    case 'unrefreshed':
      return {
        kind: 'unrefreshed',
        notice:
          result.outcome === 'ok'
            ? { tone: 'info', text: DONE_MESSAGES[action] }
            : { tone: 'neutral', text: '다른 운영자가 먼저 이 후보를 바꿨어요.' },
      }
  }
}

/** 실데이터 승인하고 발행 결과 */
function publishOutcome(
  target: ReviewCandidate,
  dirty: boolean,
  result: ApproveAndPublishResult,
): Outcome {
  switch (result.status) {
    case 'published':
      return { kind: 'published' }
    case 'approvedNotPublished':
      return {
        kind: 'refreshed',
        list: result.list.candidates,
        candidate: result.candidate,
        message: approvedOnlyMessage(target.districtName),
        goneNotice: approvedOnlyMessage(target.districtName),
      }
    case 'conflict':
      return {
        kind: 'refreshed',
        list: result.list.candidates,
        candidate: result.candidate,
        message: CONFLICT_MESSAGE,
        goneNotice: goneMessage(target.districtName),
      }
    case 'invalid':
      // 앞 단계(고친 내용 저장)가 처리됐을 수 있어 그 단계까지 반영한 후보로 바꿔 둔다(`failed` 와 같다)
      return {
        kind: 'updated',
        candidate: result.candidate,
        message: {
          tone: 'danger',
          text:
            result.step === 'save'
              ? INVALID_MESSAGES.save
              : `${dirty ? '고친 내용은 저장했어요. ' : ''}${INVALID_MESSAGES.publish}`,
        },
      }
    case 'unrefreshed':
      return {
        kind: 'unrefreshed',
        notice: {
          tone: 'neutral',
          text:
            result.outcome === 'approvedNotPublished'
              ? approvedOnlyMessage(target.districtName)
              : '다른 운영자가 먼저 이 후보를 바꿨어요.',
        },
      }
    case 'failed':
      // 앞 단계가 처리됐을 수 있어 그 단계까지 반영한 후보(다음 요청의 버전)로 바꿔 둔다
      return {
        kind: 'updated',
        candidate: result.candidate,
        message: { tone: 'danger', text: stoppedMessage(result.step, dirty) },
      }
  }
}

/**
 * 고른 주를 서버가 받지 않았다(#286) — 다시 시도해도 같아 `다시 시도` 대신 머리줄의 주 이동으로 다른 주를 고르게 한다.
 * 조회 창은 `reviewWeekBounds` 와 같다(이번 주 ~ 51주 전)
 */
const WEEK_ERROR_MESSAGES: Record<'out-of-range' | 'malformed', string> = {
  'out-of-range':
    '이 주의 검토 후보는 볼 수 없어요. 이번 주부터 51주 전까지만 볼 수 있어요. 위의 이전 주 · 다음 주로 다른 주를 골라 주세요.',
  malformed:
    '기준 주를 읽지 못해 검토 후보를 불러오지 못했어요. 위의 이전 주 · 다음 주로 다른 주를 골라 주세요.',
}

/**
 * 읽는 중(null) · 후보 목록(기준 주 · 후보 기준) · 읽지 못함. `weekError` 가 있으면 고른 주 때문이고(다른 주를 고른다), 없으면 다시 시도할 수 있다
 */
type Load =
  | null
  | ({ status: 'ready' } & Pick<ReviewCandidateList, 'isoWeek' | 'criteria'>)
  | { status: 'failed'; weekError: 'out-of-range' | 'malformed' | null }

/**
 * A01–A02 운영자 검토 대기 · 후보 상세 (`/admin/review`, #219, 시안 Admin — 데스크톱만). 가드는 레이아웃(`AdminGate`)이 건다.
 *
 * - 목록: 유형 필터(전체 · 기준선 변화 · 참여 급증 · 반복 보고 의심, 수 포함 — `signals` 로 센다) → 후보 표(유형 · 행정동 · 참여 · 증상 보고 ·
 *   기준선 대비 · 상태). 행정동 버튼(마우스는 행 어디를 눌러도 같다)으로 후보를 고르면 오른쪽(좁은 화면은 아래) 상세가 그 후보다. 처음은 첫 후보다.
 *   기준선 대비는 자료 부족 후보에 없어(#266) 값이 있는 후보가 하나도 없으면 열째 숨기고, 없는 칸은 비운다. 자료 부족 후보는 증상 보고 칸에
 *   수치 · 상태색 없이 `자료 부족` 이다
 * - 상세(`CandidateDetail`): 근거 · 이상 보고 확인 · 발행 전 확인 · 안내 제목 · 안내문 초안 · 연결된 예방수칙 · 처리 버튼
 * - 처리 결과: 저장 · 초안 만들기 · 보류는 후보를 바꿔 그 자리에 둔다. 발행한 후보는 목록에서 빠지고(발행 이력 A03 에 남는다) 목록 위에 알린 뒤
 *   다음 후보를 고른다. 실패는 상세에 빨강 상자, 충돌(409 — 다른 운영자가 먼저 고침)은 서버의 최신 목록 · 후보로 바꿔 그리고 다시 확인하라고 알린다
 * - **실데이터**(#272): 후보 API 를 읽고(읽는 동안 "불러오고 있어요", 읽지 못하면 빨강 상자 + `다시 시도`), 처리할 안내가 있는 후보를 고르면
 *   안내 상세(제목 · 본문 · 버전)를 읽는다. 처리는 초안 만들기(안내 없음) · 수정 저장 · 승인하고 발행이고 보류는 없다(서버에 기록되지 않는다).
 *   처리 뒤 목록을 다시 읽지 못하면(`unrefreshed`) 처리 결과를 알리고 목록 실패 상태(`다시 시도`)로 둔다 — 옛 목록의 버튼으로 다시 처리하지
 *   않게(발행 이력과 같다). 다시 읽으면 처리한 후보를 고른다. 목 재현은 `?mock-admin=empty|fail|conflict`(목데이터에서만)
 * - **다른 주**(#286, 실데이터만): 머리줄 기준 주 양옆의 이전 주 · 다음 주로 한 주씩 옮겨 그 주(`week`)의 후보를 다시 읽는다(읽는 중 · 실패 ·
 *   빈 목록은 위와 같고 선택 · 필터 · 상세 · 알림은 처음부터다). 첫 진입은 주를 주지 않아 서버 기본(현재 주 − 1)이고, 고를 수 있는 주는 이번 주 ~
 *   51주 전이다(서버 조회 창과 같다 — 미래 주는 막는다). 처리 중에는 옮기지 않는다. 실데이터의 설명 줄 · 빈 목록 문구는 서버 기본 주도
 *   `이번 주` 가 아니라 그 주 이름(`11월 2주`)이다. 목은 한 주뿐이라 주 이동을 그리지 않고 문구는 시안대로 `이번 주` 다
 * - 좁은 화면(모바일 · 태블릿, 시안 없음): 한 단으로 쌓는다(목록 → 상세). 표는 행마다 두 줄 카드 모양이 된다(가로 스크롤 없음)
 * - **보내는 동안은 후보 고르기 · 필터를 막는다**(버튼 `disabled`). 늦게 온 결과는 보낼 때의 후보에 적용하는데, 그사이 다른 후보를
 *   보고 있으면 결과 안내가 엉뚱한 상세에 붙는다. 보내는 시간이 짧아 막는 쪽이 단순하다
 * - 포커스: 발행해 후보가 빠지면 새로 고른 상세 제목으로(남은 후보가 없으면 발행 알림으로), 충돌로 최신 내용을 받으면 초안 칸으로,
 *   목록을 다시 읽지 못하면 `다시 시도` 로 옮긴다 — 누른 버튼이 사라지거나 다시 그려져 포커스가 문서 처음으로 떨어지지 않게 한다
 */
export function AdminReviewScreen() {
  const source = useDataSource()
  const scenario = parseMockAdminScenario(useSearchParams().get(MOCK_ADMIN_PARAM))
  const active = useActiveRef()
  const detailRef = useRef<HTMLDivElement>(null)
  const retryRef = useRef<HTMLDivElement>(null)
  const [load, setLoad] = useState<Load>(null)
  // 다시 시도 · 주 이동 때 올린다(목록을 다시 읽는다)
  const [loadKey, setLoadKey] = useState(0)
  // 읽을 주(실데이터). null 이면 주를 주지 않는다 — 서버 기본(현재 주 − 1)
  const [week, setWeek] = useState<string | null>(null)
  // 머리줄의 기준 주 — 읽은 주, 옮기는 중이면 옮겨 갈 주. 처리 뒤 다시 읽지 못해도 남는다
  const [shownWeek, setShownWeek] = useState<string | null>(null)
  // 처리 뒤 목록을 다시 읽지 못했을 때의 처리 결과 알림. 목록 실패 상자 위에 둔다
  const [loadNotice, setLoadNotice] = useState<DetailMessage | null>(null)
  // 다음에 읽은 목록에서 고를 후보(처리한 후보). 없거나 목록에 없으면 첫 후보다
  const preferredId = useRef<string | null>(null)
  const [candidates, setCandidates] = useState<readonly ReviewCandidate[]>([])
  const [filter, setFilter] = useState<Filter>('all')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  // 충돌로 서버 값을 받아 오면 늘린다 — 상세의 입력(제목 · 초안 · 확인)을 새 값으로 다시 시작한다
  const [revision, setRevision] = useState(0)
  // 안내 상세를 읽지 못한 후보(실데이터). 다시 시도하면 `detailKey` 를 올려 다시 읽는다
  const [detailFailedId, setDetailFailedId] = useState<string | null>(null)
  const [detailKey, setDetailKey] = useState(0)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<DetailMessage | null>(null)
  // 목록 위 알림 — 발행함 · 다른 운영자가 먼저 발행함 · 승인됨으로 남음(그 후보가 목록에서 빠졌을 때)
  const [notice, setNotice] = useState<DetailMessage | null>(null)
  const noticeRef = useRef<HTMLDivElement>(null)
  // 처리 결과 · 다시 읽기를 그린 뒤 옮길 포커스. 그린 다음 effect 가 한 번 쓰고 비운다
  const pendingFocus = useRef<
    'title' | 'draft' | 'field' | 'notice' | 'retry' | 'detailRetry' | null
  >(null)
  // 누른 `다시 시도`(목록 · 안내 상세) — 다시 읽은 결과에 맞춰 포커스를 옮긴다
  const retried = useRef<'list' | 'detail' | null>(null)

  useEffect(() => {
    const target = pendingFocus.current
    if (!target) return
    pendingFocus.current = null
    if (target === 'retry') {
      retryRef.current?.querySelector<HTMLElement>('button')?.focus()
      return
    }
    const detail = detailRef.current
    const heading = detail?.querySelector<HTMLElement>('h2')
    // 입력 칸이 꺼져 있으면(초안을 만들 수 없는 후보) 상세 제목으로
    const field = (selector: string) => {
      const element = detail?.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)
      return element && !element.disabled ? element : heading
    }
    const element =
      target === 'notice'
        ? noticeRef.current
        : target === 'title'
          ? heading
          : target === 'draft'
            ? field('textarea')
            : target === 'field'
              ? field('input:not([type=checkbox])')
              : detail?.querySelector<HTMLElement>('[role=alert] button')
    ;(element ?? noticeRef.current)?.focus()
  })

  useEffect(() => {
    let live = true
    // 늦게 온 응답(화면을 떠남 · 출처나 재현이 바뀜 · 다시 시도 · 다른 주로 옮김)은 버린다. 목은 실패하지 않는다
    listReviewCandidates(source, { scenario, ...(week !== null && { week }) }).then(
      (result) => {
        if (!live) return
        const list = result.candidates
        const preferred = list.find((item) => item.id === preferredId.current)
        preferredId.current = null
        setLoad({ status: 'ready', isoWeek: result.isoWeek, criteria: result.criteria })
        setShownWeek(result.isoWeek)
        setCandidates(list)
        setSelectedId((preferred ?? list[0])?.id ?? null)
        setFilter('all')
        setMessage(null)
        setNotice(null)
        if (retried.current === 'list') pendingFocus.current = 'title'
        retried.current = null
      },
      (error: unknown) => {
        if (!live) return
        // 주를 주지 않은 요청(서버 기본 주)은 고른 주 때문에 거부될 수 없다 — 다시 시도로 둔다
        setLoad({ status: 'failed', weekError: week === null ? null : reviewWeekError(error) })
        if (retried.current === 'list') pendingFocus.current = 'retry'
        retried.current = null
      },
    )
    return () => {
      live = false
    }
  }, [source, scenario, week, loadKey])

  const shown = filterBy(candidates, filter)
  const selected = shown.find((item) => item.id === selectedId) ?? null
  const showBaseline = candidates.some((item) => item.baselineDeltaPp !== undefined)
  // 실데이터는 목록에 제목 · 본문이 없다 — 처리할 안내가 있는 후보는 상세를 읽어야 고칠 수 있다
  const detailTarget =
    source === 'api' && selected?.advisoryId !== undefined && selected.draft === undefined
      ? selected
      : null
  const draftStatus: DraftStatus =
    detailTarget === null ? 'ready' : detailFailedId === detailTarget.id ? 'failed' : 'loading'

  useEffect(() => {
    if (!detailTarget) return
    let live = true
    setDetailFailedId(null)
    fetchCandidateDetail(detailTarget).then(
      (next) => {
        if (!live) return
        if (retried.current === 'detail') pendingFocus.current = 'field'
        retried.current = null
        if (next) {
          setCandidates((list) => list.map((item) => (item.id === next.id ? next : item)))
          return
        }
        // 그사이 발행됐다 — 지금 목록 · 필터로 뺀다(이 effect 를 시작한 그림의 값은 낡았을 수 있다)
        settleRef.current(detailTarget, null)
        setNotice({ tone: 'info', text: goneMessage(detailTarget.districtName) })
      },
      () => {
        if (!live) return
        setDetailFailedId(detailTarget.id)
        if (retried.current === 'detail') pendingFocus.current = 'detailRetry'
        retried.current = null
      },
    )
    return () => {
      live = false
    }
    // 고른 후보(상세가 필요한 동안)와 다시 시도만 듣는다 — 목록의 다른 후보가 바뀌어도 다시 읽지 않는다
  }, [detailTarget?.id, detailKey])

  function retryLoad() {
    retried.current = 'list'
    setLoad(null)
    setLoadNotice(null)
    setLoadKey((key) => key + 1)
  }

  /** 한 주 앞뒤로 옮겨 그 주의 후보를 읽는다. 선택 · 필터 · 상세 · 알림은 처음부터다(읽은 뒤 첫 후보) */
  function moveWeek(weeks: -1 | 1) {
    if (busy || shownWeek === null) return
    const next = shiftIsoWeek(shownWeek, weeks)
    if (next === null) return
    preferredId.current = null
    retried.current = null
    setWeek(next)
    setShownWeek(next)
    setLoad(null)
    setLoadNotice(null)
    setLoadKey((key) => key + 1)
    setCandidates([])
    setSelectedId(null)
    setFilter('all')
    setMessage(null)
    setNotice(null)
    setDetailFailedId(null)
  }

  function select(id: string, { scroll = false } = {}) {
    if (busy || id === selectedId) return
    setSelectedId(id)
    setMessage(null)
    if (scroll && isStacked()) detailRef.current?.scrollIntoView?.({ block: 'start' })
  }

  function changeFilter(next: Filter) {
    if (busy) return
    setFilter(next)
    const list = filterBy(candidates, next)
    if (!list.some((item) => item.id === selectedId)) {
      setSelectedId(list[0]?.id ?? null)
      setMessage(null)
    }
  }

  /**
   * 목록 · 선택을 바꾼다. `list` 를 주면(다시 읽은 목록) 목록을 통째로 바꾸고, 아니면 그 후보만 바꾸거나(`latest`) 뺀다.
   * 그 후보가 빠졌으면 **지금 보이는 목록**(필터 적용)에서 같은 자리의 다음 후보를 고르고(끝이었으면 앞 후보), 남은 후보가 있으면 그 상세
   * 제목 · 없으면 목록 위 알림으로 포커스를 옮기게 한다
   */
  function settle(
    target: ReviewCandidate,
    latest: ReviewCandidate | null,
    list?: readonly ReviewCandidate[],
  ) {
    const next =
      list ??
      (latest
        ? candidates.map((item) => (item.id === target.id ? latest : item))
        : candidates.filter((item) => item.id !== target.id))
    setCandidates(next)
    if (latest) return
    const index = Math.max(
      0,
      shown.findIndex((item) => item.id === target.id),
    )
    const nextShown = filterBy(next, filter)
    const nextId = nextShown[Math.min(index, nextShown.length - 1)]?.id ?? null
    setSelectedId(nextId)
    pendingFocus.current = nextId ? 'title' : 'notice'
  }
  // 늦게 온 응답(안내 상세)이 그 때의 목록 · 필터가 아니라 지금 값으로 고치게 한다
  const settleRef = useRef(settle)
  useEffect(() => {
    settleRef.current = settle
  })

  /** 처리 결과를 그린다 */
  function show(outcome: Outcome, target: ReviewCandidate) {
    switch (outcome.kind) {
      case 'updated':
        settle(target, outcome.candidate)
        if (outcome.candidate) setMessage(outcome.message ?? null)
        else setNotice({ tone: 'info', text: goneMessage(target.districtName) })
        return
      case 'published':
        settle(target, null)
        setNotice({ tone: 'info', text: `${target.districtName} 안내를 발행했어요.` })
        return
      case 'refreshed':
        settle(target, outcome.candidate, outcome.list)
        setRevision((value) => value + 1)
        if (outcome.candidate) {
          pendingFocus.current = 'draft'
          setMessage({ tone: 'neutral', text: outcome.message })
        } else {
          setNotice({ tone: 'neutral', text: outcome.goneNotice })
        }
        return
      case 'message':
        setMessage(outcome.message)
        return
      case 'unrefreshed':
        // 서버에서 처리(또는 충돌)가 끝났을 수 있다 — 그 사실을 알리고, 옛 목록 대신 다시 읽게 한다
        preferredId.current = target.id
        setLoad({ status: 'failed', weekError: null })
        setCandidates([])
        setMessage(null)
        setLoadNotice(outcome.notice)
        pendingFocus.current = 'retry'
        return
    }
  }

  /** 처리 하나를 보낸다. 목은 목 처리 함수, 실데이터는 처리 API(초안 만들기 · 수정 저장 · 승인하고 발행)다 */
  async function send(
    action: CandidateAction,
    target: ReviewCandidate,
    { title, body, dirty }: DraftInput,
  ): Promise<Outcome> {
    if (source === 'mock') {
      const { id, version } = target
      // 목 후보는 늘 버전이 있다. 버전이 없는 후보(초안 만들기)는 목 서버에 없다
      if (version === undefined) {
        return { kind: 'message', message: { tone: 'danger', text: FAILED_MESSAGES[action] } }
      }
      const result = await (action === 'hold'
        ? holdCandidate(id, { version }, source)
        : action === 'publish'
          ? publishCandidate(id, { title, draft: body, version }, source)
          : saveCandidateDraft(id, { title, draft: body, version }, source))
      return mockOutcome(action, target, result)
    }
    if (action === 'create') {
      return writeOutcome(action, target, await createAdvisoryDraft(target, { title, body }))
    }
    // 보류는 실데이터에 없다(버튼을 숨긴다). 처리할 안내가 없으면 초안 만들기부터다(버튼이 바뀐다)
    if (action === 'hold' || !hasOpenAdvisory(target)) {
      return { kind: 'message', message: { tone: 'danger', text: FAILED_MESSAGES[action] } }
    }
    if (action === 'save') {
      return writeOutcome(action, target, await saveAdvisoryEdit(target, { title, body }))
    }
    return publishOutcome(
      target,
      dirty,
      await approveAndPublishAdvisory(target, { title, body, dirty }),
    )
  }

  async function act(action: CandidateAction, target: ReviewCandidate, input: DraftInput) {
    if (busy) return
    setBusy(true)
    setMessage(null)
    setNotice(null)
    let outcome: Outcome
    try {
      outcome = await send(action, target, input)
    } catch {
      // 실패(일시 장애 · 권한 없음 — 403 이면 클라이언트가 세션 요약을 다시 맞춰 가드가 권한 없음을 그린다). 입력은 그대로다
      if (!active.current) return
      setBusy(false)
      setMessage({ tone: 'danger', text: FAILED_MESSAGES[action] })
      return
    }
    if (!active.current) return
    setBusy(false)
    show(outcome, target)
  }

  const ready = load?.status === 'ready'
  const pendingCount = ready ? candidates.filter((item) => item.state !== 'held').length : null
  // 실데이터는 늘 그 주 이름(`11월 2주`)이다 — 서버 기본 주도 현재 주 − 1 이라 `이번 주` 로 부르면 진짜 이번 주(조회 창 · 009 문구)와
  // 뜻이 둘이 된다. 목은 한 주뿐이라 시안대로 `이번 주` 다
  const weekWord =
    ready && source === 'api' ? (formatIsoWeekOfMonth(load.isoWeek) ?? load.isoWeek) : '이번 주'
  // 고를 수 있는 주(이번 주 ~ 51주 전). ISO 주 문자열은 순서가 시간 순서다. 목은 한 주뿐이라 옮기지 않는다
  const bounds = reviewWeekBounds(new Date())
  const weekNav =
    source === 'api' && shownWeek !== null
      ? {
          canPrevious: !busy && shownWeek > bounds.earliest,
          canNext: !busy && shownWeek < bounds.latest,
          onPrevious: () => moveWeek(-1),
          onNext: () => moveWeek(1),
        }
      : null

  return (
    <AdminShell
      current="review"
      weekLabel={shownWeek === null ? null : formatIsoWeekOfMonth(shownWeek)}
      weekNav={weekNav}
      pendingCount={pendingCount}
    >
      <div className="flex flex-col gap-1">
        <h1 className="text-sheet-title font-bold text-fg">검토 대기</h1>
        {ready && (
          <p className="text-body-strong text-fg-sub">
            {weekWord} 후보 {candidates.length}건 · {criteriaText(load.criteria)}
          </p>
        )}
      </div>

      {load === null && (
        <p role="status" className="text-body text-fg-sub">
          검토 후보를 불러오고 있어요
        </p>
      )}

      {load?.status === 'failed' && (
        <div ref={retryRef} className="flex flex-col gap-3">
          {loadNotice && (
            <AlertBox tone={loadNotice.tone} role="alert">
              {loadNotice.text}
            </AlertBox>
          )}
          {load.weekError ? (
            <AlertBox tone="danger">{WEEK_ERROR_MESSAGES[load.weekError]}</AlertBox>
          ) : (
            <AlertBox
              tone="danger"
              action={
                <Button variant="secondary" size="sm" className="self-start" onClick={retryLoad}>
                  다시 시도
                </Button>
              }
            >
              검토 후보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
            </AlertBox>
          )}
        </div>
      )}

      {ready && (
        <div className="flex flex-col gap-6 desktop:flex-row desktop:items-start">
          <section
            aria-label="검토 후보"
            className="flex min-w-0 flex-col gap-4 desktop:w-135 desktop:shrink-0"
          >
            {notice && (
              // 남은 후보가 없을 때 포커스를 받는 자리다(탭 순서에는 들지 않는다)
              <div ref={noticeRef} tabIndex={-1} className="focus:outline-none">
                <AlertBox tone={notice.tone}>{notice.text}</AlertBox>
              </div>
            )}
            <div className="flex flex-wrap gap-2" role="group" aria-label="유형">
              {(['all', ...CANDIDATE_KINDS] as const).map((kind) => {
                const count = filterBy(candidates, kind).length
                const pressed = filter === kind
                return (
                  <button
                    key={kind}
                    type="button"
                    aria-pressed={pressed}
                    disabled={busy}
                    onClick={() => changeFilter(kind)}
                    className={clsx(
                      'h-9 cursor-pointer rounded-chip px-3.5 text-sub disabled:cursor-not-allowed disabled:opacity-disabled',
                      pressed
                        ? 'border-emphasis border-brand bg-brand font-semibold text-bg'
                        : 'border-hairline border-inactive-bar bg-bg font-medium text-fg',
                    )}
                  >
                    {kind === 'all' ? '전체' : CANDIDATE_KIND_LABEL[kind]} {count}
                  </button>
                )
              })}
            </div>

            {shown.length === 0 ? (
              <p className="border-y border-divider py-6 text-center text-body text-fg-sub">
                {candidates.length === 0
                  ? `${weekWord} 검토할 후보가 없어요.`
                  : '이 유형의 후보가 없어요.'}
              </p>
            ) : (
              <CandidateTable
                candidates={shown}
                selectedId={selected?.id ?? null}
                showBaseline={showBaseline}
                disabled={busy}
                onSelect={(id) => select(id, { scroll: true })}
              />
            )}

            <p className="text-sub leading-[1.5] text-fg-sub">
              표본이 100명 미만인 행정동은 후보에 올라오지 않아요. 사용자 화면에는 “자료 부족”으로만
              표시돼요.
            </p>
          </section>

          <div ref={detailRef} className="flex min-w-0 grow scroll-mt-4 flex-col">
            {selected && (
              <CandidateDetail
                // 안내 상세를 다 읽으면 그 값으로 입력을 다시 시작한다
                key={`${selected.id}:${revision}:${draftStatus}`}
                candidate={selected}
                initialTitle={source === 'mock' ? mockTitleOf(selected) : (selected.title ?? '')}
                holdable={source === 'mock'}
                status={draftStatus}
                busy={busy}
                message={message}
                onRetry={() => {
                  retried.current = 'detail'
                  setDetailKey((key) => key + 1)
                }}
                onAction={(action, input) => void act(action, selected, input)}
              />
            )}
          </div>
        </div>
      )}
    </AdminShell>
  )
}

/**
 * 후보 표. 데스크톱은 시안의 6열 표, 좁은 화면은 같은 표를 행마다 두 줄(행정동 · 상태 / 유형 · 수치)로 쌓는다 —
 * 좁은 화면에서는 머리 줄과 수치 칸을 숨기고 행정동 칸 아래 요약 줄을 보인다.
 *
 * 고르기는 행정동 칸의 버튼 하나가 맡는다(키보드 · 화면 읽기는 이 버튼으로 고른다). 행 어디를 눌러도 고르는 것은 `tr` 의 onClick 으로
 * 마우스에만 준다. 버튼의 `::after` 덮개를 행 전체로 넓히는 방식은 쓰지 않는다 — 데스크톱의 `tr` 은 `display: table-row` 라
 * `position: relative` 를 기준 상자로 쓰지 않는 브라우저에서는 덮개가 행 밖(표 · 화면 전체)으로 퍼져 다른 후보 · 버튼을 가린다.
 */
function CandidateTable({
  candidates,
  selectedId,
  showBaseline,
  disabled,
  onSelect,
}: {
  candidates: readonly ReviewCandidate[]
  selectedId: string | null
  showBaseline: boolean
  /** 보내는 동안 고르기를 막는다 */
  disabled: boolean
  onSelect: (id: string) => void
}) {
  const headers = [
    '유형',
    '행정동',
    '참여',
    '증상 보고',
    ...(showBaseline ? ['기준선 대비'] : []),
    '상태',
  ]
  const cell = 'desktop:h-14 desktop:border-b desktop:border-divider desktop:px-3'
  const wideOnly = clsx('hidden desktop:table-cell', cell)
  return (
    <table className="block w-full border-collapse border-t border-divider text-left desktop:table desktop:border-t-0">
      <thead className="hidden desktop:table-header-group">
        <tr>
          {headers.map((header) => (
            <th
              key={header}
              scope="col"
              className="h-10 border-b border-divider px-3 text-caption font-semibold text-fg-sub"
            >
              {header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="block desktop:table-row-group">
        {candidates.map((item) => {
          const selected = item.id === selectedId
          const delta = item.baselineDeltaPp
          const deltaClass = delta !== undefined && delta > 0 && STATUS_TEXT_CLASS.high
          // 자료 부족 후보는 수치 · 상태색 없이 `자료 부족` 이다(증상 보고 비율이 없다)
          const insufficient = item.level === 'insufficient'
          const summary = [
            ...(item.kind ? [CANDIDATE_KIND_LABEL[item.kind]] : []),
            `참여 ${item.participants}명`,
            ...(insufficient ? ['자료 부족'] : []),
            ...(item.symptomRate !== undefined ? [`증상 보고 ${item.symptomRate}%`] : []),
            ...(delta !== undefined ? [`기준선 대비 ${formatDelta(delta)}`] : []),
          ].join(' · ')
          return (
            // 마우스 편의만 준다 — 키보드 · 화면 읽기는 행정동 버튼으로 고른다(위 주석)
            <tr
              key={item.id}
              onClick={() => {
                if (!disabled) onSelect(item.id)
              }}
              className={clsx(
                // 좁은 화면의 `tr` 은 grid 라 선택 막대(absolute)의 기준 상자가 된다. 데스크톱 막대는 첫 칸(td)이 기준이다
                'relative grid grid-cols-[1fr_auto] items-center gap-x-3 border-b border-divider px-3 py-3 text-body-strong text-fg desktop:static desktop:table-row desktop:border-b-0 desktop:p-0',
                disabled ? 'cursor-not-allowed' : 'cursor-pointer',
                selected ? 'bg-section' : 'bg-bg',
              )}
            >
              <td className={clsx(wideOnly, 'relative font-semibold')}>
                {selected && (
                  <span aria-hidden="true" className="absolute inset-y-0 left-0 w-0.75 bg-brand" />
                )}
                {item.kind && CANDIDATE_KIND_LABEL[item.kind]}
              </td>
              <td className={clsx('flex min-w-0 flex-col gap-1 desktop:table-cell', cell)}>
                {selected && (
                  <span
                    aria-hidden="true"
                    className="absolute inset-y-0 left-0 w-0.75 bg-brand desktop:hidden"
                  />
                )}
                {/* 누르면(Enter · Space 포함) click 이 행으로 올라가 행의 onClick 이 고른다 — 고르는 곳을 한 군데로 둔다 */}
                <button
                  type="button"
                  aria-current={selected ? 'true' : undefined}
                  disabled={disabled}
                  className="cursor-pointer text-left font-semibold disabled:cursor-not-allowed desktop:font-normal"
                >
                  {item.districtName}
                </button>
                <span className="text-sub text-fg-sub desktop:hidden">{summary}</span>
              </td>
              <td className={wideOnly}>{item.participants}명</td>
              <td className={clsx(wideOnly, insufficient && 'text-fg-sub')}>
                {insufficient
                  ? '자료 부족'
                  : item.symptomRate !== undefined && `${item.symptomRate}%`}
              </td>
              {showBaseline && (
                <td className={clsx(wideOnly, deltaClass && ['font-semibold', deltaClass])}>
                  {delta !== undefined && formatDelta(delta)}
                </td>
              )}
              <td className={clsx('desktop:table-cell', cell)}>
                <span
                  className={clsx(
                    'inline-flex h-6 items-center rounded-chip px-2 text-caption font-semibold whitespace-nowrap',
                    STATE_CHIP_CLASS[item.state],
                  )}
                >
                  {CANDIDATE_STATE_LABEL[item.state]}
                </span>
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
