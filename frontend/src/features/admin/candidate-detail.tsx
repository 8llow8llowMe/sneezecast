'use client'

import { useEffect, useId, useState } from 'react'

import clsx from 'clsx'

import { AlertBox, type AlertBoxTone } from '@/components/alert-box'
import { Button } from '@/components/button'
import { SYMPTOM_OPTIONS } from '@/features/report/symptoms'
import { formatIsoWeekOfMonth } from '@/lib/iso-week'
import { STATUS_FILL_CLASS } from '@/lib/status'

import { CANDIDATE_KIND_TITLE, DRAFT_MAX_LENGTH, type ReviewCandidate } from './types'

export type CandidateAction = 'save' | 'hold' | 'publish'
export type DetailMessage = { tone: AlertBoxTone; text: string }

/** 발행 전 확인 (시안). 운영자가 직접 확인해 누른다 — 서버에 남기지 않고, 후보를 고르거나 최신 내용을 받으면 다시 시작한다 */
const PUBLISH_CHECKS = [
  '표본 기준(100명 이상) 충족',
  '반복·이상 보고 제외 완료',
  '질병관리청 공식 자료와 충돌 없음',
] as const

// 서비스 지역이 한국이라 기기 시간대와 무관하게 한국 시각으로 보인다
const CLOCK = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** `검토 시작 14:02 · 경과 6분` (시안). 읽을 수 없는 시각이면 null 이다 — 그 줄을 비운다 */
export function formatReviewTime(startedAt: string, now: number): string | null {
  const start = new Date(startedAt)
  if (Number.isNaN(start.getTime())) return null
  const parts = CLOCK.formatToParts(start)
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? ''
  const minutes = Math.max(0, Math.floor((now - start.getTime()) / 60_000))
  const hours = Math.floor(minutes / 60)
  const elapsed =
    hours === 0 ? `${minutes}분` : `${hours}시간${minutes % 60 ? ` ${minutes % 60}분` : ''}`
  return `검토 시작 ${part('hour')}:${part('minute')} · 경과 ${elapsed}`
}

/** 지금 시각(분 단위로 다시 그린다) — 검토 경과 시간 */
function useMinuteClock(enabled: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!enabled) return
    const timer = window.setInterval(() => setNow(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [enabled])
  return now
}

/** 안내문 초안 검사. 문제가 없으면 null 이다 */
function draftError(draft: string): string | null {
  if (draft.trim() === '') return '안내문 초안을 입력해 주세요.'
  if (draft.length > DRAFT_MAX_LENGTH) return `안내문은 ${DRAFT_MAX_LENGTH}자까지 쓸 수 있어요.`
  return null
}

/**
 * 후보 상세 (시안 Admin 의 오른쪽 패널). 고른 후보 하나의 근거와 처리 버튼이다.
 *
 * - 제목(`○○1동 · 기준선 대비 변화`) · 근거 줄(주 · 참여 · 증상 보고 · 기준선 · 많이 보고된 증상군)
 * - 기준선 막대: 최근 주 비율과 기준선이 모두 있을 때만 그린다(2단계 값)
 * - 이상 보고 확인: 값이 있는 행만 그리고, 하나도 없으면 묶음을 숨긴다(백엔드 1단계에 없는 값 — `types.ts`)
 * - 발행 전 확인: 세 항목을 모두 눌러야 발행한다. 시안처럼 버튼은 늘 눌리고, 빠졌으면 누를 때 알린다
 * - 안내문 초안: AI 가 쓰고 운영자가 고친다(운영자 입력이라 시민 자유 서술 수집과 무관하다). 비었거나 `DRAFT_MAX_LENGTH` 를 넘으면
 *   저장 · 발행하지 않고 칸 아래에 알린다
 * - `보류`(이미 보류면 꺼짐) · `수정 저장` · `승인하고 발행`. 보내는 동안은 모두 꺼진다. 확인 대화상자는 두지 않는다 —
 *   시안에 없고, 발행 전 확인 세 항목이 그 역할을 한다
 */
export function CandidateDetail({
  candidate,
  busy,
  message,
  onAction,
}: {
  candidate: ReviewCandidate
  busy: boolean
  message: DetailMessage | null
  onAction: (action: CandidateAction, draft: string) => void
}) {
  const id = useId()
  const [draft, setDraft] = useState(candidate.draft)
  const [checked, setChecked] = useState<readonly boolean[]>(() => PUBLISH_CHECKS.map(() => false))
  const [draftProblem, setDraftProblem] = useState<string | null>(null)
  const [checksMissing, setChecksMissing] = useState(false)
  const now = useMinuteClock(candidate.reviewStartedAt !== undefined)

  const week = formatIsoWeekOfMonth(candidate.isoWeek)
  const symptom = SYMPTOM_OPTIONS.find((option) => option.key === candidate.leadingSymptom)?.label
  const baseline = candidate.baselineRate
  const meta = [
    week,
    `참여 ${candidate.participants}명`,
    `증상 보고 ${candidate.symptomRate}%${baseline !== undefined ? ` (기준선 ${baseline}%)` : ''}`,
    symptom,
  ]
    .filter(Boolean)
    .join(' · ')
  const anomalies = anomalyRows(candidate)
  const reviewTime = candidate.reviewStartedAt
    ? formatReviewTime(candidate.reviewStartedAt, now)
    : null
  const draftHintId = `${id}-draft-hint`
  const draftErrorId = `${id}-draft-error`

  function submit(action: CandidateAction) {
    if (action === 'hold') {
      onAction(action, draft)
      return
    }
    const problem = draftError(draft)
    const missing = action === 'publish' && checked.some((value) => !value)
    setDraftProblem(problem)
    setChecksMissing(missing)
    if (problem || missing) return
    onAction(action, draft)
  }

  return (
    <section
      aria-labelledby={`${id}-title`}
      className="flex flex-col gap-4.5 rounded-card border border-divider p-5 desktop:p-6"
    >
      <div className="flex flex-col gap-1">
        {/* 발행 뒤 다음 후보로 바뀌면 화면이 스크립트로 포커스를 준다(탭 순서에는 들지 않는다) */}
        <h2
          id={`${id}-title`}
          tabIndex={-1}
          className="text-screen-title font-bold text-fg focus:outline-none"
        >
          {candidate.districtName} · {CANDIDATE_KIND_TITLE[candidate.kind]}
        </h2>
        <p className="text-body-strong text-fg-sub">{meta}</p>
      </div>

      {candidate.recentRates && candidate.recentRates.length > 1 && baseline !== undefined && (
        <BaselineChart rates={candidate.recentRates} baseline={baseline} />
      )}

      <div className="flex flex-col gap-6 tablet:flex-row">
        {anomalies.length > 0 && (
          <div className="flex grow basis-0 flex-col">
            <h3 className="mb-1 text-body font-bold text-fg">이상 보고 확인</h3>
            <dl>
              {anomalies.map((row) => (
                <div
                  key={row.label}
                  className="flex min-h-10 items-center justify-between gap-3 border-b border-divider text-body-strong"
                >
                  <dt className="text-fg-sub">{row.label}</dt>
                  <dd className={clsx('text-fg', row.strong ? 'font-semibold' : 'font-medium')}>
                    {row.value}
                  </dd>
                </div>
              ))}
            </dl>
          </div>
        )}
        <div role="group" aria-labelledby={`${id}-checks`} className="flex grow basis-0 flex-col">
          <h3 id={`${id}-checks`} className="mb-1 text-body font-bold text-fg">
            발행 전 확인
          </h3>
          {PUBLISH_CHECKS.map((label, index) => (
            <label
              key={label}
              className="flex min-h-9 cursor-pointer items-center gap-2.5 text-body-strong text-fg"
            >
              <input
                type="checkbox"
                checked={checked[index] ?? false}
                onChange={(event) => {
                  const value = event.target.checked
                  setChecked((list) => list.map((item, at) => (at === index ? value : item)))
                  setChecksMissing(false)
                }}
                className="size-4.5 shrink-0 accent-brand"
              />
              {label}
            </label>
          ))}
          {checksMissing && (
            <p role="alert" className="mt-1 text-sub text-danger">
              발행 전 확인을 모두 체크해 주세요.
            </p>
          )}
        </div>
      </div>

      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
          <h3 className="text-body font-bold text-fg">
            안내문 초안{' '}
            <span id={draftHintId} className="text-caption font-semibold text-fg-sub">
              AI 작성 · 운영자 수정 가능
            </span>
          </h3>
          <span className="text-caption text-fg-sub">
            출처 확인된 집계값과 공식 예방수칙만 사용
          </span>
        </div>
        <textarea
          aria-label="안내문 초안"
          aria-describedby={clsx(draftHintId, draftProblem && draftErrorId)}
          aria-invalid={draftProblem ? true : undefined}
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value)
            setDraftProblem(null)
          }}
          className="h-40 resize-none rounded-button border border-inactive-bar px-3.5 py-3 text-body-strong leading-[1.55] text-fg focus-visible:outline-2 focus-visible:outline-brand aria-invalid:border-danger desktop:h-28.5"
        />
        {draftProblem && (
          <p id={draftErrorId} role="alert" className="text-sub text-danger">
            {draftProblem}
          </p>
        )}
        {candidate.guides.length > 0 && (
          <span className="text-caption text-fg-sub">
            연결된 예방수칙: {candidate.guides.join(' · ')} (질병관리청)
          </span>
        )}
      </div>

      {message && <AlertBox tone={message.tone}>{message.text}</AlertBox>}

      <div className="flex flex-wrap items-center gap-2.5">
        <span className="grow text-sub text-fg-sub">{reviewTime}</span>
        {/* 좁은 화면은 보조 둘을 나란히, 발행을 그 아래 한 줄로 둔다(세 버튼이 한 줄에 들어가지 않는다) */}
        <div className="grid w-full grid-cols-2 gap-2.5 tablet:flex tablet:w-auto">
          <Button
            variant="secondary"
            size="sm"
            disabled={busy || candidate.state === 'held'}
            onClick={() => submit('hold')}
          >
            보류
          </Button>
          <Button variant="secondary" size="sm" disabled={busy} onClick={() => submit('save')}>
            수정 저장
          </Button>
          <Button
            size="sm"
            disabled={busy}
            onClick={() => submit('publish')}
            className="col-span-2"
          >
            승인하고 발행
          </Button>
        </div>
      </div>
    </section>
  )
}

type AnomalyRow = { label: string; value: string; strong?: boolean }

/** 이상 보고 확인 행. 값이 있는 것만 — 백엔드 1단계에 없는 값은 지어내지 않는다 */
function anomalyRows(candidate: ReviewCandidate): AnomalyRow[] {
  const rows: AnomalyRow[] = []
  const { burstCount, sameDeviceRepeatCount, newParticipantRate } = candidate
  if (burstCount !== undefined) {
    rows.push({ label: '짧은 시간 몰림', value: burstCount === 0 ? '없음' : `${burstCount}건` })
  }
  if (sameDeviceRepeatCount !== undefined) {
    rows.push(
      sameDeviceRepeatCount === 0
        ? { label: '같은 기기 반복 보고', value: '없음' }
        : {
            label: '같은 기기 반복 보고',
            value: `${sameDeviceRepeatCount}건 · 집계 제외`,
            strong: true,
          },
    )
  }
  if (newParticipantRate !== undefined) {
    rows.push({ label: '이번 주 신규 참여 비율', value: `${newParticipantRate}%` })
  }
  return rows
}

/**
 * 막대 높이(%). 가장 큰 값(`top`)이 96% 다. 값이 모두 0(또는 음수 · 숫자 아님)이면 0 으로 나누지 않고 바닥(0%)에 둔다
 */
export function barHeightPercent(value: number, top: number): number {
  if (!(top > 0) || !(value > 0)) return 0
  return (Math.min(value, top) / top) * 96
}

/**
 * 기준선 막대 (시안 8주). 오래된 주부터 이번 주까지 증상 보고 비율이고, 점선이 기준선이다. 이번 주 막대만 주황(시안)이다.
 * 가장 큰 값(막대 · 기준선)이 높이의 96% 다(시안 100px 상자에 96px 막대). 화면 읽기에는 기준선과 이번 주 값을 한 문장으로 준다
 */
function BaselineChart({ rates, baseline }: { rates: readonly number[]; baseline: number }) {
  const top = Math.max(baseline, ...rates)
  const height = (value: number) => `${barHeightPercent(value, top)}%`
  const last = rates.length - 1
  return (
    <div className="flex flex-col gap-1.5">
      <div
        role="img"
        aria-label={`최근 ${rates.length}주 증상 보고 비율. 기준선 ${baseline}%, 이번 주 ${rates[last]}%`}
        className="relative flex h-25 items-end gap-2.5"
      >
        <div
          className="absolute inset-x-0 border-t-emphasis border-dashed border-fg-muted"
          style={{ bottom: height(baseline) }}
        />
        <span
          className="absolute left-0 bg-bg px-1 text-caption text-fg-sub"
          style={{ bottom: `calc(${height(baseline)} + 4px)` }}
        >
          기준선 {baseline}%
        </span>
        {rates.map((rate, index) => (
          <div
            key={index}
            className={clsx(
              'grow rounded-progress',
              index === last ? STATUS_FILL_CLASS.high : 'bg-inactive-bar',
            )}
            style={{ height: height(rate) }}
          />
        ))}
      </div>
      <div aria-hidden="true" className="flex justify-between text-caption text-fg-sub">
        <span>{last}주 전</span>
        <span className="font-bold text-fg">이번 주</span>
      </div>
    </div>
  )
}
