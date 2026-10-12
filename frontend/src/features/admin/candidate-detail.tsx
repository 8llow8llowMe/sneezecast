'use client'

import { useEffect, useId, useState } from 'react'

import clsx from 'clsx'

import { AlertBox, type AlertBoxTone } from '@/components/alert-box'
import { Button } from '@/components/button'
import { TextField } from '@/components/text-field'
import { SYMPTOM_OPTIONS } from '@/features/report/symptoms'
import { formatMonthDayTime } from '@/lib/format'
import { formatIsoWeekOfMonth } from '@/lib/iso-week'
import { STATUS_FILL_CLASS } from '@/lib/status'

import {
  CANDIDATE_KIND_TITLE,
  DRAFT_MAX_LENGTH,
  type ReviewCandidate,
  TITLE_MAX_LENGTH,
} from './types'

/** 상세의 처리. `create` 는 안내가 없는 후보의 초안 만들기, `hold` 는 목에서만 있다 */
export type CandidateAction = 'create' | 'save' | 'hold' | 'publish'
export type DetailMessage = { tone: AlertBoxTone; text: string }
/** 처리에 싣는 입력. `dirty` 는 상세에서 읽은 제목 · 본문과 다른지다(승인하고 발행이 수정 저장을 먼저 할지) */
export type DraftInput = { title: string; body: string; dirty: boolean }
/** 안내문(제목 · 본문)을 읽었는지 — 실데이터는 처리할 안내가 있으면 상세를 읽어야 제목 · 본문이 있다 */
export type DraftStatus = 'ready' | 'loading' | 'failed'

/** 발행 전 확인 (시안). 운영자가 직접 확인해 누른다 — 서버에 남기지 않고, 후보를 고르거나 최신 내용을 받으면 다시 시작한다 */
const PUBLISH_CHECKS = [
  '표본 기준(100명 이상) 충족',
  '반복·이상 보고 제외 완료',
  '질병관리청 공식 자료와 충돌 없음',
] as const

/**
 * `검토 시작 14:02 · 경과 6분` (시안). 시각은 한국 시각이다(서비스 지역이 한국이라 기기 시간대와 무관). 읽을 수 없는 시각이면 null 이다 — 그 줄을 비운다.
 * 실데이터의 검토 시작은 초안을 만든 시각(#286)이라 며칠 전일 수 있다 — 오늘(KST)이 아니면 날짜를 붙이고(`검토 시작 11월 17일 10:00`),
 * 경과가 24시간 이상이면 `3일 4시간` 으로 접는다. 0 인 뒤 단위(`0분` · `0시간`)는 적지 않는다
 */
export function formatReviewTime(startedAt: string, now: number): string | null {
  const start = new Date(startedAt)
  const at = formatMonthDayTime(start)
  if (at === null) return null
  // `11월 20일 14:02` 의 날짜 부분이 지금과 같으면 오늘이다
  const day = at.slice(0, at.lastIndexOf(' '))
  const today = formatMonthDayTime(new Date(now))?.startsWith(`${day} `) ?? false
  const minutes = Math.max(0, Math.floor((now - start.getTime()) / 60_000))
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)
  const elapsed =
    days > 0
      ? `${days}일${hours % 24 ? ` ${hours % 24}시간` : ''}`
      : hours > 0
        ? `${hours}시간${minutes % 60 ? ` ${minutes % 60}분` : ''}`
        : `${minutes}분`
  return `검토 시작 ${today ? at.slice(at.lastIndexOf(' ') + 1) : at} · 경과 ${elapsed}`
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

/** 안내 제목 검사(advisory `title` 상한 — 넘으면 서버도 `ADVISORY_106`). 문제가 없으면 null 이다. 발행 이력의 정정 제목도 쓴다 */
export function titleError(title: string): string | null {
  if (title.trim() === '') return '제목을 입력해 주세요.'
  if (title.length > TITLE_MAX_LENGTH) return `제목은 ${TITLE_MAX_LENGTH}자까지 쓸 수 있어요.`
  return null
}

/**
 * 초안을 만들거나 발행할 수 없는 까닭. 후보 API 의 `draftable` 이 거짓일 때만 있다(목 후보에는 값이 없다) — 자료 부족이면 그 문구,
 * 아니면 마감 전이다
 */
function blockedReason(candidate: ReviewCandidate): string | null {
  if (candidate.draftable !== false) return null
  return candidate.level === 'insufficient'
    ? '자료 부족 후보예요. 증상 보고 비율을 셀 수 없어 초안을 만들거나 발행할 수 없어요.'
    : '이 주 집계가 아직 마감되지 않았어요. 마감된 뒤에 초안을 만들 수 있어요.'
}

/**
 * 초안 칸 옆 작성 표시. 안내가 없으면 운영자가 처음 쓰는 초안이다. AI 초안(AI_DRAFTED)이거나 목(시안 — 안내 상태 없이 버전만 있음)이면
 * 시안의 `AI 작성 · 운영자 수정 가능`, 그 밖의 안내(운영자가 만든 초안 · 수정본 · 승인됨)는 AI 를 쓴 것으로 보이지 않는다
 */
function draftHint(candidate: ReviewCandidate): string {
  if (candidate.version === undefined) return '운영자 작성'
  const status = candidate.advisoryStatus
  return status === undefined || status === 'AI_DRAFTED'
    ? 'AI 작성 · 운영자 수정 가능'
    : '운영자 수정 가능'
}

/**
 * 후보 상세 (시안 Admin 의 오른쪽 패널). 고른 후보 하나의 근거와 처리 버튼이다.
 *
 * - 제목(`○○1동 · 기준선 대비 변화`) · 근거 줄(주 · 참여 · 증상 보고 · 기준선 · 많이 보고된 증상군)
 * - 초안을 만들 수 없는 후보(`draftable: false` — 자료 부족 · 마감 전)는 근거 줄 아래 까닭을 알리고 입력 · 처리 버튼을 끈다
 * - 기준선 막대: 최근 주 비율과 기준선이 모두 있을 때만 그린다(2단계 값)
 * - 이상 보고 확인: 값이 있는 행만 그리고, 하나도 없으면 묶음을 숨긴다(백엔드 1단계에 없는 값 — `types.ts`)
 * - 발행 전 확인: 세 항목을 모두 눌러야 발행한다. 시안처럼 버튼은 늘 눌리고, 빠졌으면 누를 때 알린다
 * - 안내 제목(#272 — 시안에 없다. advisory `title` 이 필수라 초안 칸 위에 둔다, `TITLE_MAX_LENGTH`) · 안내문 초안: 운영자가 쓰고 고친다
 *   (운영자 입력이라 시민 자유 서술 수집과 무관하다). 비었거나 상한을 넘으면 처리하지 않고 칸 아래에 알린다.
 *   실데이터에서 안내 상세를 읽는 중이거나 읽지 못하면(`status`) 두 칸 대신 그 상태와 `다시 시도` 를 보이고 처리 버튼을 끈다
 * - 버튼: `보류`(목에서만 — `holdable`, 이미 보류면 꺼짐) · 처리할 안내(버전)가 있으면 `수정 저장` · `승인하고 발행`, 없으면 `초안 만들기`.
 *   보내는 동안은 모두 꺼진다. 확인 대화상자는 두지 않는다 — 시안에 없고, 발행 전 확인 세 항목이 그 역할을 한다
 */
export function CandidateDetail({
  candidate,
  initialTitle,
  holdable,
  status,
  busy,
  message,
  onRetry,
  onAction,
}: {
  candidate: ReviewCandidate
  /** 제목 칸의 처음 값 — 읽은 안내 제목(목은 예시 제목), 안내가 없으면 빈 칸이다 */
  initialTitle: string
  /** `보류` 를 보일지 — 보류는 서버에 기록되지 않아 목에서만 있다 */
  holdable: boolean
  status: DraftStatus
  busy: boolean
  message: DetailMessage | null
  /** 안내 상세를 다시 읽는다(`status` 가 failed 일 때) */
  onRetry: () => void
  onAction: (action: CandidateAction, input: DraftInput) => void
}) {
  const id = useId()
  const initialDraft = candidate.draft ?? ''
  const [title, setTitle] = useState(initialTitle)
  const [draft, setDraft] = useState(initialDraft)
  const [checked, setChecked] = useState<readonly boolean[]>(() => PUBLISH_CHECKS.map(() => false))
  const [titleProblem, setTitleProblem] = useState<string | null>(null)
  const [draftProblem, setDraftProblem] = useState<string | null>(null)
  const [checksMissing, setChecksMissing] = useState(false)
  const now = useMinuteClock(candidate.reviewStartedAt !== undefined)

  const week = formatIsoWeekOfMonth(candidate.isoWeek)
  const symptom = SYMPTOM_OPTIONS.find((option) => option.key === candidate.leadingSymptom)?.label
  const baseline = candidate.baselineRate
  const meta = [
    week,
    `참여 ${candidate.participants}명`,
    candidate.symptomRate !== undefined &&
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
  const blocked = blockedReason(candidate)
  const ready = status === 'ready'
  // 처리할 안내가 있으면 고치고 발행하고, 없으면 초안부터 만든다
  const hasAdvisory = candidate.version !== undefined
  const locked = busy || !ready || blocked !== null
  const secondaryCount = (holdable ? 1 : 0) + (hasAdvisory ? 1 : 0)

  function submit(action: CandidateAction) {
    const input = { title, body: draft, dirty: title !== initialTitle || draft !== initialDraft }
    if (action === 'hold') {
      onAction(action, input)
      return
    }
    const titleIssue = titleError(title)
    const draftIssue = draftError(draft)
    const missing = action === 'publish' && checked.some((value) => !value)
    setTitleProblem(titleIssue)
    setDraftProblem(draftIssue)
    setChecksMissing(missing)
    if (titleIssue || draftIssue || missing) return
    onAction(action, input)
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
          {candidate.districtName}
          {candidate.kind && ` · ${CANDIDATE_KIND_TITLE[candidate.kind]}`}
        </h2>
        <p className="text-body-strong text-fg-sub">{meta}</p>
      </div>

      {blocked && <AlertBox tone="neutral">{blocked}</AlertBox>}

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

      {status === 'loading' && (
        <p role="status" className="text-body text-fg-sub">
          안내문을 불러오고 있어요
        </p>
      )}
      {status === 'failed' && (
        <AlertBox
          tone="danger"
          action={
            <Button variant="secondary" size="sm" className="self-start" onClick={onRetry}>
              다시 시도
            </Button>
          }
        >
          안내문을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </AlertBox>
      )}
      {ready && (
        <>
          <TextField
            label="안내 제목"
            value={title}
            error={titleProblem ?? undefined}
            counter={{ current: title.length, max: TITLE_MAX_LENGTH }}
            disabled={blocked !== null}
            onChange={(event) => {
              setTitle(event.target.value)
              setTitleProblem(null)
            }}
          />
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
              <h3 className="text-body font-bold text-fg">
                안내문 초안{' '}
                <span id={draftHintId} className="text-caption font-semibold text-fg-sub">
                  {draftHint(candidate)}
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
              disabled={blocked !== null}
              onChange={(event) => {
                setDraft(event.target.value)
                setDraftProblem(null)
              }}
              className="h-40 resize-none rounded-button border border-inactive-bar px-3.5 py-3 text-body-strong leading-[1.55] text-fg focus-visible:outline-2 focus-visible:outline-brand disabled:border-divider disabled:bg-section aria-invalid:border-danger desktop:h-28.5"
            />
            {draftProblem && (
              <p id={draftErrorId} role="alert" className="text-sub text-danger">
                {draftProblem}
              </p>
            )}
            {candidate.guides && candidate.guides.length > 0 && (
              <span className="text-caption text-fg-sub">
                연결된 예방수칙: {candidate.guides.join(' · ')} (질병관리청)
              </span>
            )}
          </div>
        </>
      )}

      {message && <AlertBox tone={message.tone}>{message.text}</AlertBox>}

      <div className="flex flex-wrap items-center gap-2.5">
        <span className="grow text-sub text-fg-sub">{reviewTime}</span>
        {/* 좁은 화면은 보조 버튼을 나란히, 주 버튼을 그 아래 한 줄로 둔다(세 버튼이 한 줄에 들어가지 않는다). 보조가 하나면 한 줄을 다 쓴다 */}
        <div className="grid w-full grid-cols-2 gap-2.5 tablet:flex tablet:w-auto">
          {holdable && (
            <Button
              variant="secondary"
              size="sm"
              disabled={busy || candidate.state === 'held'}
              onClick={() => submit('hold')}
              className={clsx(secondaryCount === 1 && 'col-span-2')}
            >
              보류
            </Button>
          )}
          {hasAdvisory && (
            <Button
              variant="secondary"
              size="sm"
              disabled={locked}
              onClick={() => submit('save')}
              className={clsx(secondaryCount === 1 && 'col-span-2')}
            >
              수정 저장
            </Button>
          )}
          <Button
            size="sm"
            disabled={locked}
            onClick={() => submit(hasAdvisory ? 'publish' : 'create')}
            className="col-span-2"
          >
            {hasAdvisory ? '승인하고 발행' : '초안 만들기'}
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
