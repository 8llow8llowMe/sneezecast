import type { Load } from '@/features/auth/member-info'
import { classifyApiError } from '@/lib/api/error-kind'
import { kstIsoWeek } from '@/lib/iso-week'
import {
  getSessionSnapshot,
  refreshSession,
  type SessionSnapshot,
  subscribeSession,
} from '@/lib/session/session-store'

import { fetchCurrentReport } from './report-api'
import type { SubmittedReport } from './types'

/* ── 이번 주 보고 저장소 (실데이터 모드, #165) ─────────────────────────────────────────────────
 *
 * 세션이 **보고할 수 있는 회원**(`reportWritable`)이 되면 이번 주 보고(`GET /api/v1/reports/current`)를 읽어 이 모듈 메모리에 둔다.
 * 화면은 `use-submitted-report.ts` 의 훅으로 읽는다(목데이터 모드는 `report-client.ts` 의 목 보고를 쓰고 이 저장소를 보지 않는다).
 * 회원 정보 저장소(`features/auth/member-info.ts`)와 같은 모양이다.
 *
 * - **보고할 수 없으면 읽지 않는다.** 세 API 모두 `report:write` 가 있어야 해, 동의 전 회원은 요청 없이 비워 둔다(403 을 받으러 가지 않는다)
 * - **회원이 바뀌거나 보고할 수 있게 될 때만 읽는다.** `memberId` 가 바뀌면 · `reportWritable` 이 false → true 가 되면 다시 읽고,
 *   같은 회원의 재발급은 다시 읽지 않는다. 비회원이 되거나(로그아웃 · 만료 · 다른 탭 로그아웃) 보고할 수 없게 되면 바로 지운다 —
 *   같은 기기의 다음 사람에게 지난 보고가 보이지 않게 한다. 기다리던 응답이 그 뒤에 와도 버린다(`seq`)
 * - **한 회원에 한 번만 보낸다(single-flight).** 요청은 `loading` 으로 바뀔 때만 나간다. 다시 시도(`retryCurrentReport`)는 실패했을 때만 보낸다
 * - 보내기 · 고치기 · 되돌리기에 성공하면 응답을 바로 넣는다(`setCurrentReport`). 그보다 먼저 보낸 조회 응답이 늦게 와도 덮어쓰지 않는다
 * - **주가 바뀌면 다시 읽는다.** 읽은 값이 어느 주 것인지(`week` — 응답의 `isoWeek`, 미보고면 요청을 보낸 때의 KST 주)를 두고,
 *   화면이 다시 보일 때(`visibilitychange` → visible · `focus`) KST 기준 지금 주와 다르면 `loading` 으로 다시 읽는다. 같은 주면
 *   요청하지 않는다. 앱을 연 채 월요일 00:00 KST 를 넘기면 지난 주 보고가 완료로 남지 않게 한다. 화면을 계속 띄워 둔 채(포커스가
 *   그대로) 주가 바뀌면 다음에 다시 보일 때까지는 지난 주 값이다
 * - 읽기가 보고 권한 없음(`SECURITY_006`)이면 세션 요약을 다시 맞춘다(`refreshSession`) — 요약도 보고할 수 없다고 바뀌면 지운다
 *
 * 건강 · 증상은 민감정보라 브라우저 저장소 · 주소 · 로그에 남기지 않는다(새로고침하면 다시 읽는다).
 */

export type CurrentReportSnapshot = Readonly<{
  /** 이 값을 읽은 회원. 세션의 `memberId` 와 다르면(바뀌는 중) 쓰지 않는다 */
  memberId: string
  /** 이번 주 보고. 아직 보내지 않았으면 `ready` 에 null 이다 */
  report: Load<SubmittedReport | null>
  /** `ready` 값이 어느 주 것인지 (`2026-W40`, KST). 읽는 중 · 실패면 null */
  week: string | null
}>

const LOADING: Load<never> = Object.freeze({ status: 'loading' })
const FAILED: Load<never> = Object.freeze({ status: 'failed' })

let snapshot: CurrentReportSnapshot | null = null
/** 조회 차례. 회원이 바뀌거나 지울 때 · 결과를 넣을 때 늘려 늦은 응답을 버린다 */
let seq = 0
const listeners = new Set<() => void>()

function publish(next: CurrentReportSnapshot | null): void {
  if (next === snapshot) return
  snapshot = next
  listeners.forEach((listener) => listener())
}

function setLoad(
  memberId: string,
  report: Load<SubmittedReport | null>,
  week: string | null = null,
): void {
  if (snapshot?.memberId !== memberId) return
  publish(Object.freeze({ memberId, report, week }))
}

function ready(memberId: string, report: SubmittedReport | null, week: string): void {
  setLoad(memberId, Object.freeze({ status: 'ready', value: report }), week)
}

/** 지금 저장소 값. 바뀔 때만 새 객체다(`useSyncExternalStore` 의 getSnapshot). 보고할 수 없거나 시작 전이면 null */
export function getCurrentReportSnapshot(): CurrentReportSnapshot | null {
  return snapshot
}

export function subscribeCurrentReport(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** 세션이 보고할 수 있는 회원인지(`report:write`) */
function writableMemberOf(session: SessionSnapshot): string | null {
  return session.status === 'member' && session.summary.reportWritable
    ? session.summary.memberId
    : null
}

/** 이 세션의 이번 주 보고. 보고할 수 있는 회원이 아니거나 저장소가 다른(바뀌기 전) 회원 것이면 null 이다 */
export function currentReportOf(
  session: SessionSnapshot,
  current: CurrentReportSnapshot | null,
): CurrentReportSnapshot | null {
  const memberId = writableMemberOf(session)
  return memberId !== null && current?.memberId === memberId ? current : null
}

function load(memberId: string): void {
  const sent = seq
  // 미보고(null)면 응답에 주가 없다 — 보낸 때의 KST 주로 둔다(주 경계 직전에 보냈으면 다음에 보일 때 한 번 더 읽는다)
  const sentWeek = kstIsoWeek(new Date())
  fetchCurrentReport().then(
    (received) => {
      if (seq === sent) ready(memberId, received?.report ?? null, received?.isoWeek ?? sentWeek)
    },
    (error: unknown) => {
      if (seq !== sent) return
      // 일시 장애 · 권한(403) · 응답 계약 어긋남(`ReportResponseError`) 모두 "불러오지 못함" 이다
      setLoad(memberId, FAILED)
      // 권한 없음이면 요약을 다시 맞춘다. 보고할 수 없다고 바뀌면 세션 알림(sync)이 지운다
      if (classifyApiError(error) === 'forbidden') void refreshSession()
    },
  )
}

/** 읽어 둔 값이 지난 주 것이면 다시 읽는다. 읽는 중 · 실패 · 같은 주면 아무 일도 없다 */
function refreshIfWeekChanged(): void {
  const current = snapshot
  if (current?.report.status !== 'ready' || current.week === kstIsoWeek(new Date())) return
  seq += 1
  setLoad(current.memberId, LOADING)
  load(current.memberId)
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') refreshIfWeekChanged()
}

/** 세션에 맞춘다. 보고할 수 있는 회원이 바뀌었으면 다시 읽고, 아니게 되면 지운다 */
function sync(): void {
  const memberId = writableMemberOf(getSessionSnapshot())
  if (memberId === null) {
    if (snapshot === null) return
    seq += 1
    publish(null)
    return
  }
  if (snapshot?.memberId === memberId) return
  seq += 1
  publish(Object.freeze({ memberId, report: LOADING, week: null }))
  load(memberId)
}

/**
 * 세션 알림과 화면이 다시 보이는 때(`visibilitychange` · `focus`)를 받기 시작한다(지금 세션에도 바로 맞춘다).
 * 돌려준 함수로 그만 받는다(리스너도 뗀다) — 읽은 값은 지우지 않는다.
 * 루트 레이아웃의 `SessionBootstrap` 이 부른다. 여러 번 켜도 같은 회원이면 다시 읽지 않는다
 */
export function startCurrentReport(): () => void {
  const unsubscribe = subscribeSession(sync)
  window.addEventListener('focus', refreshIfWeekChanged)
  document.addEventListener('visibilitychange', onVisibilityChange)
  sync()
  return () => {
    unsubscribe()
    window.removeEventListener('focus', refreshIfWeekChanged)
    document.removeEventListener('visibilitychange', onVisibilityChange)
  }
}

/** 읽지 못했으면 다시 읽는다. 읽는 중이거나 이미 읽었으면 아무 일도 없다 */
export function retryCurrentReport(): void {
  const current = snapshot
  if (current?.report.status !== 'failed') return
  seq += 1
  setLoad(current.memberId, LOADING)
  load(current.memberId)
}

/**
 * 보내기 · 고치기(응답) · 되돌리기(null) 결과를 넣는다. 보낸 회원(`memberId`)이 지금 저장소의 회원일 때만 넣고,
 * 그보다 먼저 보낸 조회 응답은 버린다. `isoWeek` 는 응답의 주이고, 응답에 주가 없으면(되돌리기) 지금 KST 주다
 */
export function setCurrentReport(
  memberId: string,
  report: SubmittedReport | null,
  isoWeek: string = kstIsoWeek(new Date()),
): void {
  if (snapshot?.memberId !== memberId) return
  seq += 1
  ready(memberId, report, isoWeek)
}

/** 테스트 정리용. 저장소를 비우고 기다리던 응답을 버린다 — 구독은 건드리지 않는다 */
export function resetCurrentReportForTests(): void {
  seq += 1
  publish(null)
}
