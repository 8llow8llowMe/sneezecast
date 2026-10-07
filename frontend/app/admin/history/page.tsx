import type { Metadata } from 'next'

import { AdminHistoryScreen } from '@/features/admin/history-screen'

export const metadata: Metadata = { title: '발행 이력' }

/**
 * A03 운영자 발행 이력 · 정정 · 철회 (#220, 시안 History). 이력은 화면이 읽는다(운영자 확인은 레이아웃의 가드).
 * 실데이터는 운영자 API 가 없어(#214 · #215) 아직 준비하고 있다고 알린다.
 * 목데이터 QA: `?mock-auth=member&mock-role=operator`(운영자로 보기) · `?mock-admin=empty|fail|conflict`(목 재현).
 */
export default function AdminHistoryPage() {
  return <AdminHistoryScreen />
}
