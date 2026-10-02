'use client'

import { useSyncExternalStore } from 'react'

import type { LoadStatus } from '@/features/auth/member-info'
import { useSession } from '@/lib/session/use-session'
import { useDataSource } from '@/lib/use-data-source'

import {
  currentReportOf,
  type CurrentReportSnapshot,
  getCurrentReportSnapshot,
  subscribeCurrentReport,
} from './current-report'
import { getSubmittedReport, subscribeSubmittedReport } from './report-client'
import type { SubmittedReport } from './types'

// 서버는 보낸 보고를 모른다(목 모듈 메모리 · 실데이터 저장소). 서버와 첫 그림(하이드레이션)은 보낸 보고 없이 그린다
const serverReportSnapshot = (): SubmittedReport | null => null
const serverCurrentSnapshot = (): CurrentReportSnapshot | null => null

/** 이 세션의 이번 주 보고 저장소 값(실데이터). 보고할 수 있는 회원이 아니면 null 이다 */
function useCurrentReport(): CurrentReportSnapshot | null {
  const session = useSession()
  const current = useSyncExternalStore(
    subscribeCurrentReport,
    getCurrentReportSnapshot,
    serverCurrentSnapshot,
  )
  return currentReportOf(session, current)
}

/**
 * 이번 주에 보낸 보고. 없으면 null 이다.
 *
 * - 실데이터: 이번 주 보고 저장소(`current-report.ts`, `GET /api/v1/reports/current`). **읽는 중 · 읽지 못함도 null 이다** —
 *   보고 버튼이 완료로 잘못 보이지 않게 한다. 셋을 가를 때는 `useSubmittedReportStatus()` 를 함께 쓴다
 * - 목데이터: `report-client` 의 목 모듈 메모리. 새로고침하면 비어 null 이다(목 회원 세션과 같다)
 *
 * 보고 흐름(완료 단계 · 수정)과 보고 버튼 글자(`reportButtonLabel` — 보낸 뒤 "이번 주 보고 완료 · 수정하기"), 내 정보의
 * `내 보고` 항목이 같은 값을 읽는다. 홈 밖에 두어 홈을 떠났다 돌아와도(설치 안내 → 닫기) 이어진다.
 *
 * **서버 그림과 하이드레이션 첫 그림은 늘 null 이다** — 서버 그림과 맞춰야 하이드레이션이 어긋나지 않는다. 그다음 그림부터 보낸 보고를 읽는다.
 */
export function useSubmittedReport(): SubmittedReport | null {
  const source = useDataSource()
  const mock = useSyncExternalStore(
    subscribeSubmittedReport,
    getSubmittedReport,
    serverReportSnapshot,
  )
  const current = useCurrentReport()
  if (source === 'api') return current?.report.status === 'ready' ? current.report.value : null
  return mock
}

/**
 * 이번 주 보고를 읽었는지. 목데이터 · 비회원 · 보고할 수 없는 회원은 늘 `ready` 다(읽을 것이 없다).
 * 실데이터의 보고할 수 있는 회원은 저장소 요청의 상태다(`retryCurrentReport()` 로 다시 읽는다)
 */
export function useSubmittedReportStatus(): LoadStatus {
  const source = useDataSource()
  const session = useSession()
  const current = useCurrentReport()
  if (source === 'mock') return 'ready'
  if (session.status !== 'member' || !session.summary.reportWritable) return 'ready'
  return current?.report.status ?? 'loading'
}
