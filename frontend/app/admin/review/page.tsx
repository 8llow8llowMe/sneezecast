import type { Metadata } from 'next'

import { AdminReviewScreen } from '@/features/admin/review-screen'

export const metadata: Metadata = { title: '검토 대기' }

/**
 * A01–A02 운영자 검토 대기 · 후보 상세 (#219, 시안 Admin). 후보는 화면이 읽는다(운영자 확인은 레이아웃의 가드).
 * 실데이터는 운영자 API 가 없어(#214) 아직 준비하고 있다고 알린다.
 * 목데이터 QA: `?mock-auth=member&mock-role=operator`(운영자로 보기) · `?mock-admin=empty|fail|conflict`(목 재현).
 */
export default function AdminReviewPage() {
  return <AdminReviewScreen />
}
