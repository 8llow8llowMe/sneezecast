import { getMockSession, subscribeMockSession } from '@/features/auth/auth-client'
import { reloadMemberRegion } from '@/features/auth/member-info'
import { ApiError } from '@/lib/api/api-error'
import { classifyApiError } from '@/lib/api/error-kind'
import type { DataSource } from '@/lib/data-source'
import { getSessionSnapshot, refreshSession } from '@/lib/session/session-store'

import { setCurrentReport } from './current-report'
import { deleteCurrentReport, putCurrentReport, type ReceivedReport } from './report-api'
import type { ReportAnswer, SubmittedReport } from './types'

/**
 * 보고 보내기 · 고치기 · 되돌리기. 데이터 출처(`api` | `mock`, docs/conventions.md "데이터 출처")를 마지막 인자로 받는다.
 *
 * - 실데이터(#165): 보내기 · 고치기 → `PUT /api/v1/reports/current { districtCode, symptomGroups }`(같은 요청 — 서버가 이번 주에
 *   처음이면 저장, 있으면 바꾼다), 되돌리기 → `DELETE /api/v1/reports/current`(멱등). 보고 주는 보내지 않는다(서버가 KST 로 정한다).
 *   성공하면 응답을 이번 주 보고 저장소(`current-report.ts`)에 바로 넣는다. 오류 해석은 아래 `SEND_FAILURES` · 각 함수 주석
 * - 목: 아래 모듈 메모리. 이번 주 보고 읽기도 목은 여기(`getSubmittedReport`), 실데이터는 저장소다(`use-submitted-report.ts` 가 고른다)
 *
 * 보고 내용은 브라우저 저장소에 남기지 않는다 — 건강 정보는 민감정보다 (루트 CLAUDE.md "개인정보").
 */

/*
 * 목 서버가 들고 있는 이번 주 보고. **모듈 메모리라 새로고침 · 새 탭이면 사라진다**(목 회원 세션과 같다).
 *
 * 홈 상태에 두면 홈을 떠났다 돌아올 때(보고 완료 → 홈 화면 추가 안내 → 닫기) 사라져 `?report=done` 이 시작 단계로 풀린다.
 * 서버가 이번 주 보고를 아는 흉내로 홈 밖에 둔다. 같은 사람의 같은 주 보고는 하나라(고치면 바뀌고 집계에는 한 번만 든다)
 * 값도 하나만 둔다.
 *
 * 목 회원 상태가 바뀌면(로그아웃 · 탈퇴 · 동의 철회 · 로그인 만료 · 다른 로그인) 지운다. 서버는 회원별로 보고를 돌려주고 철회 · 탈퇴 때
 * 보고를 지우므로, 같은 기기에서 다음에 로그인한 사람에게 지난 보고가 보이면 안 된다. 프로필만 바뀌면(동네 · 비밀번호) 그대로 둔다.
 */
let mockReport: SubmittedReport | null = null
const reportListeners = new Set<() => void>()

function setMockReport(next: SubmittedReport | null) {
  if (mockReport === next) return
  mockReport = next
  reportListeners.forEach((listener) => listener())
}

let reportSession = getMockSession()
subscribeMockSession(() => {
  const session = getMockSession()
  if (session === reportSession) return
  reportSession = session
  setMockReport(null)
})

/** 목: 이번 주에 보낸 보고. 없으면 null 이다 (`useSyncExternalStore` 의 getSnapshot) */
export function getSubmittedReport(): SubmittedReport | null {
  return mockReport
}

/** 목: 보낸 보고가 바뀔 때 부른다 (`useSyncExternalStore` 의 subscribe). 돌려준 함수로 구독을 끊는다 */
export function subscribeSubmittedReport(listener: () => void): () => void {
  reportListeners.add(listener)
  return () => {
    reportListeners.delete(listener)
  }
}

/** 오늘 날짜를 "11월 19일" 형식으로 */
function todayLabel(now = new Date()): string {
  return `${now.getMonth() + 1}월 ${now.getDate()}일`
}

/**
 * 보내기 · 고치기 결과.
 * - `ok`: 보냈다(저장소에도 넣었다). `firstSubmission` 은 **서버 기준으로** 이번 주 첫 저장인지다 — 실데이터는 응답의
 *   `reportedAt === updatedAt`(초 단위라 같은 초 안에 고친 보고도 첫 저장으로 보인다, `report-api.ts`), 목은 보내기 전에 목 보고가
 *   없었는지다. 화면은 이 값이 true 인 "증상 없음" 에만 되돌리기를 준다 — 이 탭이 몰랐던 보고(다른 기기 · 탭)를 고친 경우
 *   되돌리면 그 보고까지 지워진다
 * - `consent-required`: 보고 권한이 없다(`SECURITY_006`, 403 — 건강정보 동의가 없는 토큰). 세션 요약을 다시 맞춘 뒤 돌려준다 —
 *   요약도 보고할 수 없다고 바뀌면 홈의 보고 진입(`guardReportEntry`)이 건강정보 동의 시트로 바꾼다
 * - `region-changed`: 보낸 동네를 쓸 수 없다(`REPORT_003` 폐지 · `REPORT_002` 행정동 서비스에 없음, 400). 내 동네를 다시 읽게 한 뒤
 *   돌려준다 — 다시 읽은 내 동네가 폐지(`abolished` — 서비스에 없는 코드도 폐지로 온다)면 회원 조건이 동네 다시 고르기로 보내고,
 *   다른 탭에서 이미 바꿨으면 새 동네로 다시 보낼 수 있다
 */
export type SendReportResult =
  | { status: 'ok'; report: SubmittedReport; firstSubmission: boolean }
  | { status: 'consent-required' }
  | { status: 'region-changed' }

/**
 * 오류 코드 → 결과. 표에 없는 오류는 거부한다 — 화면은 "보내지 못했어요. 잠시 뒤 다시 보내 주세요." 다:
 * `REPORT_001`(같은 주 동시 처리, 409 — 서버가 이미 한 번 다시 했다, 잠시 뒤 같은 요청이면 풀린다) · 일시 장애(`UNAVAILABLE` · 503 · 504) ·
 * 검증 오류(`REPORT_100` · `101~105` — 화면이 고른 값만 보내 나지 않는 오류다. 다시 고르게 할 입력이 없다)
 */
const SEND_FAILURES: Readonly<Record<string, 'region-changed'>> = {
  REPORT_002: 'region-changed',
  REPORT_003: 'region-changed',
}

/** 실데이터 요청을 보낼 회원. 세션이 회원이 아니면 보내지 않고 거부한다 — 토큰 없이 보내 401(`SECURITY_001`)을 받으러 가지 않는다 */
function sessionMemberId(what: string): string {
  const session = getSessionSnapshot()
  if (session.status !== 'member') throw new Error(`${what}: no member session`)
  return session.summary.memberId
}

async function sendReport(
  answer: ReportAnswer,
  districtCode: string | null,
): Promise<SendReportResult> {
  const memberId = sessionMemberId('send report')
  // 보고 동네(내 동네)를 모르면 보내지 않는다. 화면이 먼저 막는다(report-flow.tsx) — 여기 닿으면 프로그램 오류다
  if (districtCode === null) throw new Error('send report: no district')
  let received: ReceivedReport
  try {
    received = await putCurrentReport(districtCode, answer)
  } catch (error) {
    if (classifyApiError(error) === 'forbidden') {
      await refreshSession()
      return { status: 'consent-required' }
    }
    const code = error instanceof ApiError ? error.code : null
    if (code !== null && Object.hasOwn(SEND_FAILURES, code)) {
      reloadMemberRegion()
      return { status: 'region-changed' }
    }
    throw error
  }
  setCurrentReport(memberId, received.report, received.isoWeek)
  return { status: 'ok', report: received.report, firstSubmission: received.firstSubmission }
}

function sendMockReport(answer: ReportAnswer): SendReportResult {
  const firstSubmission = mockReport === null
  const report = { answer, reportedLabel: todayLabel() }
  setMockReport(report)
  return { status: 'ok', report, firstSubmission }
}

/**
 * 이번 주 처음 보내기. `districtCode` 는 보고 동네(회원의 내 동네) 코드다 — 실데이터는 null 이면 보내지 않는다(목은 쓰지 않는다)
 */
export async function submitReport(
  answer: ReportAnswer,
  districtCode: string | null,
  source: DataSource,
): Promise<SendReportResult> {
  if (source === 'api') return sendReport(answer, districtCode)
  return sendMockReport(answer)
}

/** 같은 주 고치기. 실데이터는 처음 보내기와 같은 요청이다(`PUT` — 서버가 이번 주 보고를 바꾼다) */
export async function updateReport(
  answer: ReportAnswer,
  districtCode: string | null,
  source: DataSource,
): Promise<SendReportResult> {
  if (source === 'api') return sendReport(answer, districtCode)
  return sendMockReport(answer)
}

/**
 * 이번 주 보고 되돌리기(지우기). 실데이터는 `DELETE`(멱등 — 이미 없어도 성공)이고 성공하면 저장소를 비운다.
 * 실패는 거부한다(화면은 "되돌리지 못했어요"). 권한 없음(`SECURITY_006`)이면 보내기처럼 세션 요약을 다시 맞춘 뒤 거부한다
 */
export async function cancelReport(source: DataSource): Promise<void> {
  if (source === 'api') {
    const memberId = sessionMemberId('cancel report')
    try {
      await deleteCurrentReport()
    } catch (error) {
      if (classifyApiError(error) === 'forbidden') await refreshSession()
      throw error
    }
    setCurrentReport(memberId, null)
    return
  }
  setMockReport(null)
}
