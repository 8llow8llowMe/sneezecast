'use client'

import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'next/navigation'

import clsx from 'clsx'

import { AlertBox } from '@/components/alert-box'
import { formatIsoWeekOfMonth } from '@/lib/iso-week'
import { STATUS_TEXT_CLASS } from '@/lib/status'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import {
  type CandidateActionResult,
  type CandidateListResult,
  holdCandidate,
  listReviewCandidates,
  MOCK_ADMIN_PARAM,
  parseMockAdminScenario,
  publishCandidate,
  saveCandidateDraft,
} from './admin-review-client'
import { AdminShell } from './admin-shell'
import { type CandidateAction, CandidateDetail, type DetailMessage } from './candidate-detail'
import {
  CANDIDATE_KIND_LABEL,
  CANDIDATE_KINDS,
  CANDIDATE_STATE_LABEL,
  type CandidateKind,
  type CandidateState,
  type ReviewCandidate,
} from './types'

type Filter = CandidateKind | 'all'

/** 시안 설명 줄의 후보 기준. 기준선(+5%p)은 2단계 값이라 백엔드가 기준을 내려 주면 그 값으로 바꾼다 */
const CANDIDATE_CRITERIA =
  '후보 기준: 표본 100명 이상에서 기준선 대비 +5%p, 참여 급증, 반복 보고 의심'

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

const ACTION_MESSAGES: Record<CandidateAction, { done: string; failed: string }> = {
  save: {
    done: '고친 초안을 저장했어요.',
    failed: '초안을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
  },
  hold: {
    done: '이 후보를 보류했어요. 이번 주에는 안내를 내지 않아요.',
    failed: '보류하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
  },
  publish: { done: '', failed: '발행하지 못했어요. 잠시 뒤 다시 시도해 주세요.' },
}

/**
 * A01–A02 운영자 검토 대기 · 후보 상세 (`/admin/review`, #219, 시안 Admin — 데스크톱만). 가드는 레이아웃(`AdminGate`)이 건다.
 *
 * - 목록: 유형 필터(전체 · 기준선 변화 · 참여 급증 · 반복 보고 의심, 수 포함) → 후보 표(유형 · 행정동 · 참여 · 증상 보고 · 기준선 대비 · 상태).
 *   행정동 버튼(마우스는 행 어디를 눌러도 같다)으로 후보를 고르면 오른쪽(좁은 화면은 아래) 상세가 그 후보다. 처음은 첫 후보다.
 *   기준선 대비는 백엔드 1단계에 없어(2단계) 값이 있는 후보가 하나도 없으면 열째 숨기고, 없는 칸은 비운다
 * - 상세(`CandidateDetail`): 근거 · 이상 보고 확인 · 발행 전 확인 · 안내문 초안 · 연결된 예방수칙 · `보류` · `수정 저장` · `승인하고 발행`
 * - 처리 결과: 저장 · 보류는 후보를 바꿔 그 자리에 둔다. 발행한 후보는 목록에서 빠지고(발행 이력 A03 에 남는다) 목록 위에 알린 뒤 다음 후보를 고른다.
 *   실패는 상세에 빨강 상자, 충돌(409 — 다른 운영자가 먼저 고침)은 서버의 최신 후보로 바꿔 그리고 다시 확인하라고 알린다
 * - **실데이터는 운영자 API 가 없어(#214) 요청하지 않고** 아직 준비하고 있다고 알린다. 목 재현은 `?mock-admin=empty|fail|conflict`
 * - 좁은 화면(모바일 · 태블릿, 시안 없음): 한 단으로 쌓는다(목록 → 상세). 표는 행마다 두 줄 카드 모양이 된다(가로 스크롤 없음)
 * - **보내는 동안은 후보 고르기 · 필터를 막는다**(버튼 `disabled`). 늦게 온 결과는 보낼 때의 후보에 적용하는데, 그사이 다른 후보를
 *   보고 있으면 결과 안내가 엉뚱한 상세에 붙는다. 보내는 시간이 짧아 막는 쪽이 단순하다
 * - 포커스: 발행해 후보가 빠지면 새로 고른 상세 제목으로(남은 후보가 없으면 발행 알림으로), 충돌로 최신 내용을 받으면 초안 칸으로
 *   옮긴다 — 누른 버튼이 사라지거나 다시 그려져 포커스가 문서 처음으로 떨어지지 않게 한다
 */
export function AdminReviewScreen() {
  const source = useDataSource()
  const scenario = parseMockAdminScenario(useSearchParams().get(MOCK_ADMIN_PARAM))
  const active = useActiveRef()
  const detailRef = useRef<HTMLDivElement>(null)
  const [load, setLoad] = useState<CandidateListResult | null>(null)
  const [candidates, setCandidates] = useState<readonly ReviewCandidate[]>([])
  const [filter, setFilter] = useState<Filter>('all')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  // 충돌로 서버 값을 받아 오면 늘린다 — 상세의 입력(초안 · 확인)을 새 값으로 다시 시작한다
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<DetailMessage | null>(null)
  const [published, setPublished] = useState<string | null>(null)
  const noticeRef = useRef<HTMLDivElement>(null)
  // 처리 결과를 그린 뒤 옮길 포커스. 그린 다음 effect 가 한 번 쓰고 비운다
  const pendingFocus = useRef<'title' | 'draft' | 'notice' | null>(null)

  useEffect(() => {
    const target = pendingFocus.current
    if (!target) return
    pendingFocus.current = null
    const detail = detailRef.current
    const element =
      target === 'notice'
        ? noticeRef.current
        : target === 'title'
          ? detail?.querySelector('h2')
          : detail?.querySelector('textarea')
    ;(element ?? noticeRef.current)?.focus()
  })

  useEffect(() => {
    let live = true
    // 목은 실패하지 않고 실데이터는 요청하지 않는다. 연동 때 불러오는 중 · 실패 상태를 더한다
    void listReviewCandidates(source, { scenario }).then((result) => {
      if (!live) return
      setLoad(result)
      const list = result.status === 'ready' ? result.candidates : []
      setCandidates(list)
      setSelectedId(list[0]?.id ?? null)
      setFilter('all')
      setMessage(null)
      setPublished(null)
    })
    return () => {
      live = false
    }
  }, [source, scenario])

  const shown = filter === 'all' ? candidates : candidates.filter((item) => item.kind === filter)
  const selected = shown.find((item) => item.id === selectedId) ?? null
  const showBaseline = candidates.some((item) => item.baselineDeltaPp !== undefined)

  function select(id: string, { scroll = false } = {}) {
    if (busy || id === selectedId) return
    setSelectedId(id)
    setMessage(null)
    if (scroll && isStacked()) detailRef.current?.scrollIntoView?.({ block: 'start' })
  }

  function changeFilter(next: Filter) {
    if (busy) return
    setFilter(next)
    const list = next === 'all' ? candidates : candidates.filter((item) => item.kind === next)
    if (!list.some((item) => item.id === selectedId)) {
      setSelectedId(list[0]?.id ?? null)
      setMessage(null)
    }
  }

  /**
   * 처리 결과로 목록 · 선택을 바꾼다. 빠진 후보(발행)면 **지금 보이는 목록**(필터 적용)에서 같은 자리의 다음 후보를 고르고
   * (끝이었으면 앞 후보), 남은 후보가 있으면 그 상세 제목 · 없으면 발행 알림으로 포커스를 옮기게 한다
   */
  function apply(target: ReviewCandidate, next: ReviewCandidate | null) {
    if (next) {
      setCandidates((list) => list.map((item) => (item.id === next.id ? next : item)))
      return
    }
    const index = shown.findIndex((item) => item.id === target.id)
    const rest = candidates.filter((item) => item.id !== target.id)
    setCandidates(rest)
    const nextShown = shown.filter((item) => item.id !== target.id)
    const nextId = (nextShown[index] ?? nextShown[index - 1])?.id ?? null
    setSelectedId(nextId)
    pendingFocus.current = nextId ? 'title' : 'notice'
  }

  async function act(action: CandidateAction, target: ReviewCandidate, draft: string) {
    if (busy) return
    setBusy(true)
    setMessage(null)
    setPublished(null)
    const { id, version } = target
    let result: CandidateActionResult
    try {
      result = await (action === 'save'
        ? saveCandidateDraft(id, { draft, version }, source)
        : action === 'hold'
          ? holdCandidate(id, { version }, source)
          : publishCandidate(id, { draft, version }, source))
    } catch {
      if (!active.current) return
      setBusy(false)
      setMessage({ tone: 'danger', text: ACTION_MESSAGES[action].failed })
      return
    }
    if (!active.current) return
    setBusy(false)
    if (result.status === 'unavailable') {
      setMessage({ tone: 'danger', text: ACTION_MESSAGES[action].failed })
      return
    }
    if (result.status === 'conflict') {
      apply(target, result.candidate)
      setRevision((value) => value + 1)
      if (result.candidate) {
        pendingFocus.current = 'draft'
        setMessage({
          tone: 'neutral',
          text: '다른 운영자가 먼저 이 후보를 고쳤어요. 최신 내용으로 바꿔 두었어요. 확인한 뒤 다시 시도해 주세요.',
        })
      } else {
        setPublished(`다른 운영자가 ${target.districtName} 후보를 먼저 발행했어요.`)
      }
      return
    }
    apply(target, result.candidate)
    if (action === 'publish') setPublished(`${target.districtName} 안내를 발행했어요.`)
    else setMessage({ tone: 'info', text: ACTION_MESSAGES[action].done })
  }

  const ready = load?.status === 'ready'
  const pendingCount = ready ? candidates.filter((item) => item.state !== 'held').length : null

  return (
    <AdminShell
      current="review"
      weekLabel={ready ? formatIsoWeekOfMonth(load.isoWeek) : null}
      pendingCount={pendingCount}
    >
      <div className="flex flex-col gap-1">
        <h1 className="text-sheet-title font-bold text-fg">검토 대기</h1>
        {ready && (
          <p className="text-body-strong text-fg-sub">
            이번 주 후보 {candidates.length}건 · {CANDIDATE_CRITERIA}
          </p>
        )}
      </div>

      {load?.status === 'unavailable' && (
        <AlertBox tone="neutral">
          운영자 검토는 아직 준비하고 있어요. 지금은 검토 후보를 불러올 수 없어요.
        </AlertBox>
      )}

      {ready && (
        <div className="flex flex-col gap-6 desktop:flex-row desktop:items-start">
          <section
            aria-label="검토 후보"
            className="flex min-w-0 flex-col gap-4 desktop:w-135 desktop:shrink-0"
          >
            {published && (
              // 남은 후보가 없을 때 포커스를 받는 자리다(탭 순서에는 들지 않는다)
              <div ref={noticeRef} tabIndex={-1} className="focus:outline-none">
                <AlertBox tone="info">{published}</AlertBox>
              </div>
            )}
            <div className="flex flex-wrap gap-2" role="group" aria-label="유형">
              {(['all', ...CANDIDATE_KINDS] as const).map((kind) => {
                const count =
                  kind === 'all'
                    ? candidates.length
                    : candidates.filter((item) => item.kind === kind).length
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
                  ? '이번 주 검토할 후보가 없어요.'
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
                key={`${selected.id}:${revision}`}
                candidate={selected}
                busy={busy}
                message={message}
                onAction={(action, draft) => void act(action, selected, draft)}
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
          const summary = [
            CANDIDATE_KIND_LABEL[item.kind],
            `참여 ${item.participants}명`,
            `증상 보고 ${item.symptomRate}%`,
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
                {CANDIDATE_KIND_LABEL[item.kind]}
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
              <td className={wideOnly}>{item.symptomRate}%</td>
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
