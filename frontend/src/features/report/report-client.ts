import { getMockSession, subscribeMockSession } from '@/features/auth/auth-client'

import type { ReportAnswer, SubmittedReport } from './types'

/**
 * 보고 보내기 · 고치기 · 되돌리기와 이번 주에 보낸 보고. **API 연동 전 목 구현이다.**
 *
 * 연동 이슈에서 `src/lib/api/` 를 거쳐 다음으로 바꾼다 (docs/api-contract-draft.md).
 * - 처음 보내기 → `POST /api/reports`
 * - 같은 주 고치기 → `PUT /api/reports/{week}`
 * - 되돌리기 → `DELETE /api/reports/{week}`
 * - 이번 주에 보낸 보고 읽기(`getSubmittedReport`) → 서버가 로그인한 회원의 이번 주 보고를 돌려주는 값 (계약 미정)
 *
 * 함수 모양(Promise)을 지금부터 맞춰 두어 화면 코드가 연동 때 바뀌지 않게 한다.
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

/** 이번 주에 보낸 보고. 없으면 null 이다 (`useSyncExternalStore` 의 getSnapshot) */
export function getSubmittedReport(): SubmittedReport | null {
  return mockReport
}

/** 보낸 보고가 바뀔 때 부른다 (`useSyncExternalStore` 의 subscribe). 돌려준 함수로 구독을 끊는다 */
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

export function submitReport(answer: ReportAnswer): Promise<SubmittedReport> {
  const result = { answer, reportedLabel: todayLabel() }
  setMockReport(result)
  return Promise.resolve(result)
}

export function updateReport(answer: ReportAnswer): Promise<SubmittedReport> {
  const result = { answer, reportedLabel: todayLabel() }
  setMockReport(result)
  return Promise.resolve(result)
}

export function cancelReport(): Promise<void> {
  setMockReport(null)
  return Promise.resolve()
}
