'use client'

import { useId, useState } from 'react'
import Link from 'next/link'

import clsx from 'clsx'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { Modal } from '@/components/modal'
import { noticePath } from '@/features/notice/paths'
import { formatMonthDay } from '@/lib/format'
import { formatIsoWeekOfMonth } from '@/lib/iso-week'

import { kstDate } from './admin-history-client'
import type { DetailMessage } from './candidate-detail'
import {
  CANDIDATE_KIND_LABEL,
  DRAFT_MAX_LENGTH,
  type HistoryEntry,
  REASON_MAX_LENGTH,
  TIMELINE_STEP_LABEL,
  type TimelineEvent,
} from './types'

export type HistoryAction = 'correct' | 'retract'

// 서비스 지역이 한국이라 기기 시간대와 무관하게 한국 시각으로 보인다(검토 대기와 같다)
const CLOCK = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** 시각의 한국 날짜 `11월 18일`. 읽을 수 없는 값이면 null 이다 — 그 칸을 비운다 */
export function formatKstMonthDay(iso: string): string | null {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : formatMonthDay(kstDate(date))
}

/** 타임라인 시각. 첫 단계와 같은 날이면 `14:08`(시안), 다른 날(정정 · 철회)이면 `11월 19일 14:08` 이다 */
export function formatTimelineTime(iso: string, firstIso: string): string | null {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return null
  const parts = CLOCK.formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? ''
  const time = `${part('hour')}:${part('minute')}`
  const first = new Date(firstIso)
  if (!Number.isNaN(first.getTime()) && kstDate(first) === kstDate(date)) return time
  return `${formatMonthDay(kstDate(date))} ${time}`
}

/**
 * 결과 이름. 발행 중인 안내는 정정 발행 · 수정 후 발행 · 발행으로 나눈다(시안 `수정 후 발행` · `발행`).
 * 정정됨 · 철회는 #220 에서 더한 결과다(시안 없음)
 */
export function outcomeLabel(entry: HistoryEntry): string {
  if (entry.outcome === 'held') return '보류'
  if (entry.outcome === 'corrected') return '정정됨'
  if (entry.outcome === 'retracted') return '철회'
  if (entry.correction) return '정정 발행'
  return entry.edited ? '수정 후 발행' : '발행'
}

/**
 * `사용자 화면에서 보기` 주소 — 동네 안내(S07) `/notice/[동네]/[주]`. 보류는 안내가 없어 null 이다.
 * 동네 안내의 안내 내용은 아직 목(`?mock=`)이라 이 행의 본문이 아니라 상태만 맞춘다: 정정을 거친 안내는 `corrected`, 철회는 `none`
 * (동네 안내에 철회된 안내 모양이 아직 없다 — SCREENS.md S07), 그 밖은 `published`. 실데이터는 이 화면에 이력이 없어 쓰지 않는다
 */
export function noticeViewHref(entry: HistoryEntry): string | null {
  if (entry.outcome === 'held') return null
  const state =
    entry.outcome === 'retracted'
      ? 'none'
      : entry.outcome === 'corrected' || entry.correction
        ? 'corrected'
        : 'published'
  return noticePath(entry.districtCode, entry.isoWeek, `mock=${state}`)
}

/**
 * 이력 상세 (시안 History 의 오른쪽 패널). 고른 행 하나의 근거 · 타임라인 · 본문과 정정 · 철회다.
 *
 * - 제목(`○○2동 · 11월 3주`) · 근거 줄(후보 유형 · 참여 · 증상 보고)
 * - 타임라인: 전이 시각과 세부 문구. 백엔드 이력에서 나올 수 없는 세부 문구는 값이 있을 때만 보인다(`TimelineEvent.note`).
 *   처리를 끝낸 승인·발행 · 보류 단계에는 담당 · 검토 시간을 붙인다(둘 다 1단계에 없는 값 — 없으면 뺀다)
 * - 본문: 발행 본문(보류는 그때의 초안)
 * - `정정 발행` · `발행 철회`: **발행 중인 안내에만** 있다. 정정됨 · 철회(RETRACTED 는 끝 상태)는 고칠 수 없다
 * - `사용자 화면에서 보기`: 동네 안내(S07). 보류에는 없다
 */
export function HistoryDetail({
  entry,
  busy,
  message,
  onAction,
}: {
  entry: HistoryEntry
  busy: boolean
  message: DetailMessage | null
  onAction: (action: HistoryAction) => void
}) {
  const id = useId()
  const week = formatIsoWeekOfMonth(entry.isoWeek)
  const viewHref = noticeViewHref(entry)
  const first = entry.timeline[0]?.at ?? entry.processedAt
  // 담당 · 검토 시간을 붙일 단계 — 처리를 끝낸 마지막 승인·발행 또는 보류 하나다(보류했다가 나중에 발행한 후보는 앞의 보류에 붙이지 않는다)
  const decisionIndex = entry.timeline.reduce(
    (found, event, index) => (event.step === 'published' || event.step === 'held' ? index : found),
    -1,
  )
  return (
    <section
      aria-labelledby={`${id}-title`}
      className="flex flex-col gap-4 rounded-card border border-divider p-5 desktop:p-6"
    >
      <div className="flex flex-col gap-1">
        {/* 처리 결과를 그린 뒤 화면이 스크립트로 포커스를 준다(탭 순서에는 들지 않는다) */}
        <h2
          id={`${id}-title`}
          tabIndex={-1}
          className="text-lead font-bold text-fg focus:outline-none"
        >
          {[entry.districtName, week].filter(Boolean).join(' · ')}
        </h2>
        <p className="text-sub text-fg-sub">
          {CANDIDATE_KIND_LABEL[entry.kind]} · 참여 {entry.participants}명 · 증상 보고{' '}
          {entry.symptomRate}%
        </p>
      </div>

      <ol aria-label="처리 이력">
        {entry.timeline.map((event, index) => (
          <TimelineItem
            key={`${event.step}-${index}`}
            event={event}
            time={formatTimelineTime(event.at, first)}
            extra={
              index === decisionIndex
                ? [
                    entry.operatorName,
                    entry.reviewMinutes !== undefined && `검토 ${entry.reviewMinutes}분`,
                  ]
                : []
            }
            last={index === entry.timeline.length - 1}
          />
        ))}
      </ol>

      <div>
        <h3 className="sr-only">{entry.outcome === 'held' ? '보류한 초안' : '발행 본문'}</h3>
        <p className="rounded-button bg-section p-3.5 text-sub leading-[1.6] whitespace-pre-line text-fg">
          {entry.body}
        </p>
      </div>

      {message && <AlertBox tone={message.tone}>{message.text}</AlertBox>}

      {entry.outcome === 'published' && (
        <>
          <div className="flex gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={busy}
              onClick={() => onAction('correct')}
              className="flex-1"
            >
              정정 발행
            </Button>
            {/* 시안은 빨강 테두리 · 흰 바탕이다. Button 의 danger 는 빨강 바탕이라 색을 덮지 않고 따로 그린다 */}
            <button
              type="button"
              disabled={busy}
              onClick={() => onAction('retract')}
              className="h-button-sm flex-1 cursor-pointer rounded-button border-emphasis border-danger bg-bg px-5 text-body font-semibold text-danger disabled:cursor-not-allowed disabled:opacity-disabled"
            >
              발행 철회
            </button>
          </div>
          <p className="text-caption leading-[1.55] text-fg-sub">
            정정하면 사용자 화면에 정정일과 사유가 표시돼요. 철회하면 안내가 내려가고 “철회된
            안내예요”가 남아요.
          </p>
        </>
      )}
      {entry.outcome === 'corrected' && (
        <p className="text-caption leading-[1.55] text-fg-sub">
          새 안내로 정정 발행했어요. 이 안내는 사용자 화면에서 내려갔어요.
        </p>
      )}
      {entry.outcome === 'retracted' && (
        <p className="text-caption leading-[1.55] text-fg-sub">
          철회한 안내는 다시 발행하거나 정정할 수 없어요.
        </p>
      )}

      {viewHref && (
        <Link
          href={viewHref}
          className="-my-3 inline-flex min-h-touch items-center self-start text-body-strong font-semibold text-brand underline"
        >
          사용자 화면에서 보기
        </Link>
      )}
    </section>
  )
}

function TimelineItem({
  event,
  time,
  extra,
  last,
}: {
  event: TimelineEvent
  time: string | null
  /** 세부 문구 뒤에 붙일 값(담당 · 검토 시간). 없는 값은 뺀다 */
  extra: ReadonlyArray<string | false | undefined>
  last: boolean
}) {
  const note = [event.note, ...extra].filter(Boolean).join(' · ')
  return (
    <li className="flex gap-3.5">
      <div aria-hidden="true" className="flex flex-col items-center">
        <span className="mt-1 size-3 shrink-0 rounded-chip bg-brand" />
        {!last && <span className="w-0.5 grow bg-divider" />}
      </div>
      <div className="flex min-w-0 flex-col gap-0.5 pb-4.5">
        <span className="text-body-strong font-bold text-fg">
          {TIMELINE_STEP_LABEL[event.step]}
          {time && <span className="font-medium text-fg-sub"> · {time}</span>}
        </span>
        {note && <span className="text-sub leading-[1.5] text-fg-sub">{note}</span>}
      </div>
    </li>
  )
}

const DIALOG_CONTENT: Record<
  HistoryAction,
  { title: string; items: string[]; reasonLabel: string; action: string }
> = {
  correct: {
    title: '정정 발행할까요?',
    items: [
      '고친 본문이 새 안내로 발행돼요.',
      '사용자 화면에 정정일과 사유가 함께 보여요.',
      '지금 안내는 이력에 정정됨으로 남아요.',
    ],
    reasonLabel: '정정 사유',
    action: '정정 발행',
  },
  retract: {
    title: '발행을 철회할까요?',
    items: [
      '안내가 사용자 화면에서 내려가요.',
      '사용자 화면에는 “철회된 안내예요”가 남아요.',
      '철회하면 되돌릴 수 없어요.',
    ],
    reasonLabel: '철회 사유',
    action: '발행 철회',
  },
}

/** 사유 검사. 문제가 없으면 null 이다 */
function reasonError(action: HistoryAction, reason: string): string | null {
  if (reason.trim() === '') return `${DIALOG_CONTENT[action].reasonLabel}를 입력해 주세요.`
  if (reason.length > REASON_MAX_LENGTH) return `사유는 ${REASON_MAX_LENGTH}자까지 쓸 수 있어요.`
  return null
}

/** 고친 본문 검사(검토 대기의 안내문 초안과 같은 상한). 문제가 없으면 null 이다 */
function bodyError(body: string): string | null {
  if (body.trim() === '') return '고친 본문을 입력해 주세요.'
  if (body.length > DRAFT_MAX_LENGTH) return `안내문은 ${DRAFT_MAX_LENGTH}자까지 쓸 수 있어요.`
  return null
}

/**
 * 정정 발행 · 발행 철회 확인 대화상자(시안 없음 — 모양은 내 정보의 확인 대화상자). 모바일은 바텀시트, 태블릿 · 데스크톱은 가운데 대화상자다.
 *
 * - 정정: 사유 + 고친 본문(처음 값은 지금 본문). 철회: 사유. 사유는 필수이고 500자(`memo` VARCHAR(500))까지, 본문은 500자까지다.
 *   틀리면 보내지 않고 칸 아래에 알린다. 운영자 입력이라 시민 자유 서술 수집 금지와 무관하다
 * - 보내는 동안(`busy`)은 두 버튼이 꺼진 모양(`aria-disabled` — 포커스를 지킨다)이고 닫기 · Esc · 바깥 누르기를 부모가 막는다
 * - `failure`: 실패하면 대화상자 안 빨강 상자로 알리고 입력은 그대로 둔다
 *
 * 열림은 주소 쿼리에 두지 않는다(docs/conventions.md 의 시트 규칙과 다르다 — SCREENS.md A03 "시안과 다르게 둔 것")
 */
export function HistoryActionDialog({
  action,
  entry,
  open,
  busy,
  failure,
  onSubmit,
  onClose,
}: {
  action: HistoryAction
  entry: HistoryEntry
  open: boolean
  busy: boolean
  failure: string | null
  onSubmit: (input: { reason: string; body: string }) => void
  onClose: () => void
}) {
  const content = DIALOG_CONTENT[action]
  const [reason, setReason] = useState('')
  const [body, setBody] = useState(entry.body)
  const [problems, setProblems] = useState<{ reason: string | null; body: string | null }>({
    reason: null,
    body: null,
  })

  function submit() {
    if (busy) return
    const next = {
      reason: reasonError(action, reason),
      body: action === 'correct' ? bodyError(body) : null,
    }
    setProblems(next)
    if (next.reason || next.body) return
    onSubmit({ reason: reason.trim(), body })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={content.title}
      compactSheet
      footer={
        <div className="flex gap-2.5">
          <Button
            variant="secondary"
            className="flex-1"
            aria-disabled={busy || undefined}
            onClick={() => {
              if (!busy) onClose()
            }}
          >
            취소
          </Button>
          <Button
            variant={action === 'retract' ? 'danger' : 'primary'}
            className="flex-1"
            aria-disabled={busy || undefined}
            onClick={submit}
          >
            {content.action}
          </Button>
        </div>
      }
    >
      <ul className="flex flex-col gap-2.5 rounded-button bg-section px-4.5 py-4">
        {content.items.map((item) => (
          <li key={item} className="flex gap-2.5 text-body leading-[1.55] text-fg">
            <span aria-hidden="true" className="mt-2.25 size-1.5 shrink-0 rounded-chip bg-fg-sub" />
            <span>{item}</span>
          </li>
        ))}
      </ul>
      <TextArea
        label={content.reasonLabel}
        value={reason}
        error={problems.reason}
        rows={3}
        readOnly={busy}
        onChange={(value) => {
          setReason(value)
          setProblems((list) => ({ ...list, reason: null }))
        }}
      />
      {action === 'correct' && (
        <TextArea
          label="고친 본문"
          value={body}
          error={problems.body}
          rows={6}
          readOnly={busy}
          onChange={(value) => {
            setBody(value)
            setProblems((list) => ({ ...list, body: null }))
          }}
        />
      )}
      {failure && <AlertBox tone="danger">{failure}</AlertBox>}
    </Modal>
  )
}

/** 여러 줄 입력칸(라벨 위 · 오류 아래). 검토 대기의 안내문 초안 칸과 같은 모양이다 */
function TextArea({
  label,
  value,
  error,
  rows,
  readOnly,
  onChange,
}: {
  label: string
  value: string
  error: string | null
  rows: number
  /** 보내는 동안 고치지 못하게 한다 */
  readOnly: boolean
  onChange: (value: string) => void
}) {
  const id = useId()
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-body-strong font-semibold text-fg">
        {label}
      </label>
      <textarea
        id={id}
        rows={rows}
        value={value}
        readOnly={readOnly}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        onChange={(event) => onChange(event.target.value)}
        className={clsx(
          'resize-none rounded-button border border-inactive-bar px-3.5 py-3 text-body-strong leading-[1.55] text-fg',
          'focus-visible:outline-2 focus-visible:outline-brand aria-invalid:border-danger',
        )}
      />
      {error && (
        <p id={`${id}-error`} role="alert" className="text-sub text-danger">
          {error}
        </p>
      )}
    </div>
  )
}
