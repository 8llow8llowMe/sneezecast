'use client'

import { useSyncExternalStore } from 'react'

import { getSubmittedReport, subscribeSubmittedReport } from './report-client'
import type { SubmittedReport } from './types'

// 서버는 보낸 보고를 모른다(목 모듈 메모리). 서버와 첫 그림(하이드레이션)은 보낸 보고 없이 그린다
const serverReportSnapshot = (): SubmittedReport | null => null

/**
 * 이번 주에 보낸 보고 (`report-client` 목). 없으면 null 이다.
 *
 * 보고 흐름(완료 단계 · 수정)과 보고 버튼 글자(`reportButtonLabel` — 보낸 뒤 "이번 주 보고 완료 · 수정하기"), 내 정보의
 * `내 보고` 항목이 같은 값을 읽는다. 홈 밖에 두어 홈을 떠났다 돌아와도(설치 안내 → 닫기) 이어진다.
 *
 * **서버 그림과 하이드레이션 첫 그림은 늘 null 이다** — 서버 그림과 맞춰야 하이드레이션이 어긋나지 않는다. 그다음 그림부터 보낸 보고를 읽는다.
 * 새로고침하면 목 메모리가 비어 null 이다(목 회원 세션과 같다).
 */
export function useSubmittedReport(): SubmittedReport | null {
  return useSyncExternalStore(subscribeSubmittedReport, getSubmittedReport, serverReportSnapshot)
}
