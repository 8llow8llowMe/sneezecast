'use client'

import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'next/navigation'

import clsx from 'clsx'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { formatIsoWeekOfMonth } from '@/lib/iso-week'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import {
  correctAdvisory,
  type HistoryActionResult,
  listHistory,
  retractAdvisory,
  summarizeHistory,
} from './admin-history-client'
import {
  countPendingCandidates,
  MOCK_ADMIN_PARAM,
  parseMockAdminScenario,
} from './admin-review-client'
import { AdminShell } from './admin-shell'
import type { DetailMessage } from './candidate-detail'
import {
  formatKstMonthDay,
  type HistoryAction,
  HistoryActionDialog,
  HistoryDetail,
  outcomeLabel,
} from './history-detail'
import { CANDIDATE_KIND_LABEL, type HistoryEntry } from './types'

/** 결과 칩 (시안: 발행 네이비 바탕 · 수정 후 발행 네이비 테두리 · 보류 회색). 정정됨 · 철회는 검토 대기의 보류 칩 모양이다 */
function outcomeChipClass(entry: HistoryEntry): string {
  if (entry.outcome === 'held') return 'bg-section text-fg-sub'
  if (entry.outcome !== 'published') return 'border-hairline border-inactive-bar bg-bg text-fg-sub'
  if (entry.edited || entry.correction) return 'border-emphasis border-brand bg-bg text-brand'
  return 'bg-brand text-bg'
}

/** 좁은 화면(데스크톱 미만)이면 상세가 목록 아래에 있다 — 행을 고르면 상세로 내려 준다(검토 대기와 같다) */
function isStacked(): boolean {
  return typeof window.matchMedia === 'function' && !window.matchMedia('(min-width: 80rem)').matches
}

const ACTION_MESSAGES: Record<HistoryAction, { done: string; failed: string; invalid: string }> = {
  correct: {
    done: '정정 발행했어요. 사용자 화면에 정정일이 함께 보여요.',
    failed: '정정 발행하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    invalid: '정정 발행하지 못했어요. 입력한 내용을 확인해 주세요.',
  },
  retract: {
    done: '발행을 철회했어요. 사용자 화면에는 철회된 안내로 남아요.',
    failed: '철회하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    invalid: '철회하지 못했어요. 입력한 내용을 확인해 주세요.',
  },
}

const CONFLICT_MESSAGE =
  '다른 운영자가 먼저 이 안내를 바꿨어요. 최신 내용으로 바꿔 두었어요. 확인한 뒤 다시 시도해 주세요.'

type Dialog = { action: HistoryAction; open: boolean; key: number }

/** 읽는 중(null) · 이력 · 읽지 못함(다시 시도할 수 있다) */
type Load = null | { status: 'ready' } | { status: 'failed' }

/** 처리 뒤 받을 포커스 — 상세 제목(성공 · 충돌) 또는 `다시 시도`(처리 뒤 이력을 다시 읽지 못함) */
type FocusTarget = 'detail' | 'retry'

/**
 * A03 운영자 발행 이력 · 정정 · 철회 (`/admin/history`, #220, 시안 History — 데스크톱만). 가드는 레이아웃(`AdminGate`)이 건다.
 *
 * - 요약: 발행 · 보류 수, 평균 검토 시간, 수정 후 발행 비율 — 목록에서 계산한다(`summarizeHistory`). 평균 검토 시간은 1단계에 없는 값이라
 *   검토 시간이 있는 행이 없으면 칸을 숨긴다
 * - 표(발행·처리일 · 행정동 · 기준 주 · 후보 유형 · 결과 · 담당 · 검토 시간): 담당 · 검토 시간은 값이 있는 행이 하나도 없으면 열째 숨긴다.
 *   고르기는 검토 대기와 같다 — 행정동 버튼(키보드 · 화면 읽기) + `tr` 의 onClick(마우스), 덮개 없음. 처음은 첫 행(최근 것)이다
 * - 상세(`HistoryDetail`): 타임라인 · 본문 · `정정 발행` · `발행 철회`(발행 중인 안내만) · `사용자 화면에서 보기`
 * - 정정 · 철회는 확인 대화상자(`HistoryActionDialog`)에서 보낸다. 성공하면 대화상자를 닫고 새 이력을 그린다 — 정정은 새 안내,
 *   철회는 그 안내를 고른다. 실패는 대화상자 안에 알리고 입력을 둔다. 충돌(409)은 대화상자를 닫고 최신 이력으로 바꿔 그린 뒤 알린다
 * - **보내는 동안은 행 고르기 · 대화상자 닫기를 막는다**(검토 대기와 같은 까닭 — 늦게 온 결과가 다른 행의 상세에 붙지 않게)
 * - 포커스: 처리 결과를 그리면(성공 · 충돌) 상세 제목으로 옮긴다 — 누른 버튼이 사라져(철회 · 정정) 포커스가 문서 처음으로 떨어지지 않게 한다
 * - **실데이터**(#271): 이력 API(최근 4주의 발행됨 · 철회됨 안내)를 읽고, 읽는 동안 "불러오고 있어요", 읽지 못하면 빨강 상자 + `다시 시도` 다.
 *   처리 뒤 · 충돌 뒤에는 클라이언트가 이력을 다시 읽어 준다. 처리는 됐는데 다시 읽지 못하면(`unrefreshed`) 대화상자를 닫고 처리 결과
 *   (또는 충돌)를 알린 뒤 목록 실패 상태(`다시 시도`)로 둔다 — 옛 목록의 버튼으로 다시 처리하지 않게. 다시 읽으면 처리한 안내를 고른다.
 *   보류는 서버에 기록되지 않아(2단계) 요약의 `보류` 칸과 보류를 약속하는 부제 · 빈 목록 문구를 실데이터에서 바꾼다. 후보 유형은 이력
 *   응답에 없어 열을 숨긴다(담당 · 검토 시간과 같은 규칙). 목 재현은 `?mock-admin=empty|fail|conflict`(목데이터에서만)
 * - 좁은 화면(시안 없음): 한 단으로 쌓는다(요약 → 목록 → 상세). 표는 행마다 두 줄(행정동 · 결과 / 나머지)이다
 */
export function AdminHistoryScreen() {
  const source = useDataSource()
  const scenario = parseMockAdminScenario(useSearchParams().get(MOCK_ADMIN_PARAM))
  const active = useActiveRef()
  const detailRef = useRef<HTMLDivElement>(null)
  const retryRef = useRef<HTMLDivElement>(null)
  const [load, setLoad] = useState<Load>(null)
  // 다시 시도할 때 올린다(이력을 다시 읽는다)
  const [loadKey, setLoadKey] = useState(0)
  // 처리 뒤 이력을 다시 읽지 못했을 때의 처리 결과 알림. 목록 실패 상자 위에 둔다
  const [notice, setNotice] = useState<DetailMessage | null>(null)
  // 다음에 읽은 이력에서 고를 행(처리한 안내). 없거나 목록에 없으면 첫 행이다
  const preferredId = useRef<string | null>(null)
  const [entries, setEntries] = useState<readonly HistoryEntry[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [pendingCount, setPendingCount] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [message, setMessage] = useState<DetailMessage | null>(null)
  // 처리 결과를 그린 뒤 포커스를 옮긴다. 그린 다음 effect 가 한 번 쓰고 비운다
  const pendingFocus = useRef<FocusTarget | null>(null)

  useEffect(() => {
    const target = pendingFocus.current
    if (!target) return
    pendingFocus.current = null
    const root = target === 'detail' ? detailRef.current : retryRef.current
    root?.querySelector<HTMLElement>(target === 'detail' ? 'h2' : 'button')?.focus()
  })

  useEffect(() => {
    let live = true
    // 늦게 온 응답(화면을 떠남 · 출처나 재현이 바뀜 · 다시 시도)은 버린다. 목은 실패하지 않는다
    listHistory(source, { scenario }).then(
      (result) => {
        if (!live) return
        const list = result.entries
        const preferred = list.find((item) => item.id === preferredId.current)
        preferredId.current = null
        setLoad({ status: 'ready' })
        setEntries(list)
        setSelectedId((preferred ?? list[0])?.id ?? null)
        setMessage(null)
      },
      () => {
        if (live) setLoad({ status: 'failed' })
      },
    )
    return () => {
      live = false
    }
  }, [source, scenario, loadKey])

  useEffect(() => {
    let live = true
    void countPendingCandidates(source).then((count) => {
      if (live) setPendingCount(count)
    })
    return () => {
      live = false
    }
  }, [source])

  function retryLoad() {
    setLoad(null)
    setNotice(null)
    setLoadKey((key) => key + 1)
  }

  const selected = entries.find((item) => item.id === selectedId) ?? null

  function select(id: string) {
    if (busy || id === selectedId) return
    setSelectedId(id)
    setMessage(null)
    if (isStacked()) detailRef.current?.scrollIntoView?.({ block: 'start' })
  }

  function openDialog(action: HistoryAction) {
    setFailure(null)
    setMessage(null)
    setDialog((current) => ({ action, open: true, key: (current?.key ?? 0) + 1 }))
  }

  function closeDialog() {
    if (busy) return
    setDialog((current) => current && { ...current, open: false })
  }

  async function act(
    action: HistoryAction,
    target: HistoryEntry,
    { reason, title, body }: { reason: string; title: string; body: string },
  ) {
    if (busy) return
    setBusy(true)
    setFailure(null)
    const { id, version } = target
    let result: HistoryActionResult
    try {
      result = await (action === 'correct'
        ? correctAdvisory(id, { title, body, reason, version }, source)
        : retractAdvisory(id, { reason, version }, source))
    } catch {
      if (!active.current) return
      setBusy(false)
      setFailure(ACTION_MESSAGES[action].failed)
      return
    }
    if (!active.current) return
    setBusy(false)
    if (result.status === 'invalid') {
      setFailure(ACTION_MESSAGES[action].invalid)
      return
    }
    setDialog((current) => current && { ...current, open: false })
    if (result.status === 'unrefreshed') {
      // 처리(또는 충돌)는 서버에서 끝났다 — 그 사실을 알리고, 옛 목록 대신 다시 읽게 한다
      preferredId.current = result.selectedId ?? id
      setLoad({ status: 'failed' })
      setEntries([])
      setMessage(null)
      setNotice(
        result.outcome === 'ok'
          ? { tone: 'info', text: ACTION_MESSAGES[action].done }
          : { tone: 'neutral', text: '다른 운영자가 먼저 이 안내를 바꿨어요.' },
      )
      pendingFocus.current = 'retry'
      return
    }
    setEntries(result.entries)
    pendingFocus.current = 'detail'
    if (result.status === 'conflict') {
      if (!result.entries.some((item) => item.id === id))
        setSelectedId(result.entries[0]?.id ?? null)
      setMessage({ tone: 'neutral', text: CONFLICT_MESSAGE })
      return
    }
    setSelectedId(result.selectedId)
    setMessage({ tone: 'info', text: ACTION_MESSAGES[action].done })
  }

  const ready = load?.status === 'ready'
  // 보류는 서버에 기록되지 않는다(2단계) — 실데이터에는 보류 행이 없어 보류 칸 · 문구를 두지 않는다
  const tracksHeld = source === 'mock'
  const summary = summarizeHistory(entries)
  const stats = [
    { label: '발행', value: `${summary.published}건` },
    ...(tracksHeld ? [{ label: '보류', value: `${summary.held}건` }] : []),
    ...(summary.averageReviewMinutes === null
      ? []
      : [{ label: '평균 검토 시간', value: `${summary.averageReviewMinutes}분` }]),
    ...(summary.editedRate === null
      ? []
      : [{ label: '수정 후 발행 비율', value: `${summary.editedRate}%` }]),
  ]

  return (
    <AdminShell current="history" pendingCount={pendingCount}>
      <div className="flex flex-col gap-1">
        <h1 className="text-sheet-title font-bold text-fg">발행 이력</h1>
        <p className="text-body-strong text-fg-sub">
          {tracksHeld
            ? '모든 후보의 승인·수정·보류 기록이 남아요.'
            : '최근 4주 동안 발행한 안내의 승인·수정·정정·철회 기록이 남아요.'}
          {/* 검토 시간(1단계에 없는 값)을 숨긴 상태에서는 그 설명도 뺀다 */}
          {ready &&
            summary.averageReviewMinutes !== null &&
            ' 검토 시간은 시범 운영 지표로 씁니다.'}
        </p>
      </div>

      {load === null && <p className="text-body text-fg-sub">발행 이력을 불러오고 있어요</p>}

      {load?.status === 'failed' && (
        <div ref={retryRef} className="flex flex-col gap-3">
          {notice && (
            <AlertBox tone={notice.tone} role="alert">
              {notice.text}
            </AlertBox>
          )}
          <AlertBox
            tone="danger"
            action={
              <Button variant="secondary" size="sm" className="self-start" onClick={retryLoad}>
                다시 시도
              </Button>
            }
          >
            발행 이력을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
          </AlertBox>
        </div>
      )}

      {ready && (
        <>
          <dl className="grid grid-cols-2 gap-3 tablet:flex tablet:gap-4">
            {stats.map((stat) => (
              <div
                key={stat.label}
                className="flex flex-col gap-1.5 rounded-button border border-divider px-4.5 py-4 tablet:grow tablet:basis-0"
              >
                <dt className="text-sub text-fg-sub">{stat.label}</dt>
                <dd className="text-dialog-title font-bold text-fg">{stat.value}</dd>
              </div>
            ))}
          </dl>

          <div className="flex flex-col gap-6 desktop:flex-row desktop:items-start">
            <section aria-label="발행 · 처리 기록" className="flex min-w-0 grow flex-col">
              {entries.length === 0 ? (
                <p className="border-y border-divider py-6 text-center text-body text-fg-sub">
                  {tracksHeld
                    ? '아직 발행하거나 보류한 기록이 없어요.'
                    : '최근 4주 동안 발행한 안내가 없어요.'}
                </p>
              ) : (
                <HistoryTable
                  entries={entries}
                  selectedId={selected?.id ?? null}
                  disabled={busy}
                  onSelect={select}
                />
              )}
            </section>

            {/* 시안의 상세는 content-box 380 + 안쪽 여백 48 + 테두리 2 = 430 이다(검토 대기 메뉴와 같은 계산) */}
            <div
              ref={detailRef}
              className="flex min-w-0 scroll-mt-4 flex-col desktop:w-107.5 desktop:shrink-0"
            >
              {selected && (
                <HistoryDetail
                  key={selected.id}
                  entry={selected}
                  source={source}
                  busy={busy}
                  message={message}
                  onAction={openDialog}
                />
              )}
            </div>
          </div>
        </>
      )}

      {dialog && selected && (
        <HistoryActionDialog
          key={dialog.key}
          action={dialog.action}
          entry={selected}
          open={dialog.open}
          busy={busy}
          failure={failure}
          onSubmit={(input) => void act(dialog.action, selected, input)}
          onClose={closeDialog}
        />
      )}
    </AdminShell>
  )
}

/**
 * 이력 표. 데스크톱은 시안의 7열 표, 좁은 화면은 같은 표를 행마다 두 줄(행정동 · 결과 / 처리일 · 기준 주 · 유형 · 담당 · 검토 시간)로 쌓는다.
 * 고르기는 검토 대기의 후보 표와 같다(행정동 칸의 버튼 하나 + 마우스용 `tr` onClick — `review-screen.tsx` 의 `CandidateTable` 주석).
 * 후보 유형 · 담당 · 검토 시간은 이력 응답에 없는 값이라 값이 있는 행이 하나도 없으면 열째 숨기고, 없는 칸은 비운다
 */
function HistoryTable({
  entries,
  selectedId,
  disabled,
  onSelect,
}: {
  entries: readonly HistoryEntry[]
  selectedId: string | null
  disabled: boolean
  onSelect: (id: string) => void
}) {
  const showKind = entries.some((item) => item.kind !== undefined)
  const showOperator = entries.some((item) => item.operatorName !== undefined)
  const showMinutes = entries.some((item) => item.reviewMinutes !== undefined)
  const headers = [
    '발행·처리일',
    '행정동',
    '기준 주',
    ...(showKind ? ['후보 유형'] : []),
    '결과',
    ...(showOperator ? ['담당'] : []),
    ...(showMinutes ? ['검토 시간'] : []),
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
              className="h-10 border-b border-divider px-3 text-caption font-semibold whitespace-nowrap text-fg-sub"
            >
              {header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="block desktop:table-row-group">
        {entries.map((item) => {
          const selected = item.id === selectedId
          const date = formatKstMonthDay(item.processedAt)
          const week = formatIsoWeekOfMonth(item.isoWeek)
          const minutes = item.reviewMinutes !== undefined ? `${item.reviewMinutes}분` : undefined
          const kind = item.kind && CANDIDATE_KIND_LABEL[item.kind]
          const summary = [date, week, kind, item.operatorName, minutes].filter(Boolean).join(' · ')
          return (
            // 마우스 편의만 준다 — 키보드 · 화면 읽기는 행정동 버튼으로 고른다
            <tr
              key={item.id}
              onClick={() => {
                if (!disabled) onSelect(item.id)
              }}
              className={clsx(
                'relative grid grid-cols-[1fr_auto] items-center gap-x-3 border-b border-divider px-3 py-3 text-body-strong text-fg desktop:static desktop:table-row desktop:border-b-0 desktop:p-0',
                disabled ? 'cursor-not-allowed' : 'cursor-pointer',
                selected ? 'bg-section' : 'bg-bg',
              )}
            >
              <td className={clsx(wideOnly, 'relative whitespace-nowrap')}>
                {selected && (
                  <span aria-hidden="true" className="absolute inset-y-0 left-0 w-0.75 bg-brand" />
                )}
                {date}
              </td>
              <td className={clsx('flex min-w-0 flex-col gap-1 desktop:table-cell', cell)}>
                {selected && (
                  <span
                    aria-hidden="true"
                    className="absolute inset-y-0 left-0 w-0.75 bg-brand desktop:hidden"
                  />
                )}
                {/* 누르면(Enter · Space 포함) click 이 행으로 올라가 행의 onClick 이 고른다 */}
                <button
                  type="button"
                  aria-current={selected ? 'true' : undefined}
                  disabled={disabled}
                  className="cursor-pointer text-left font-semibold whitespace-nowrap disabled:cursor-not-allowed"
                >
                  {item.districtName}
                </button>
                <span className="text-sub text-fg-sub desktop:hidden">{summary}</span>
              </td>
              <td className={clsx(wideOnly, 'whitespace-nowrap')}>{week}</td>
              {showKind && <td className={clsx(wideOnly, 'whitespace-nowrap')}>{kind}</td>}
              <td className={clsx('desktop:table-cell', cell)}>
                <span
                  className={clsx(
                    'inline-flex h-6 items-center rounded-chip px-2 text-caption font-semibold whitespace-nowrap',
                    outcomeChipClass(item),
                  )}
                >
                  {outcomeLabel(item)}
                </span>
              </td>
              {showOperator && (
                <td className={clsx(wideOnly, 'whitespace-nowrap')}>{item.operatorName}</td>
              )}
              {showMinutes && <td className={clsx(wideOnly, 'whitespace-nowrap')}>{minutes}</td>}
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
