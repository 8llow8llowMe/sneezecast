import { type ReactNode, Suspense } from 'react'
import type { Metadata } from 'next'

import { AdminGate } from '@/features/admin/admin-gate'

/** 운영자 화면은 검색에 싣지 않는다 */
export const metadata: Metadata = { robots: { index: false, follow: false } }

/**
 * 운영자 화면(`/admin/*`, #219 — 검토 대기 A01–A02, 발행 이력 A03 은 #220). 운영자(`OPERATOR` · `ADMIN`)만 본다 —
 * 비회원은 로그인으로 보내고, 일반 회원에게는 권한 없음을 알린다(`AdminGate`).
 * 가드는 주소 쿼리(`useSearchParams` — 목 덮어쓰기)를 읽어 빌드의 정적 생성에서 Suspense 경계가 있어야 한다. 정해지기 전에는
 * 아무것도 그리지 않으므로 대체 그림도 없다.
 */
export default function AdminLayout({ children }: { children: ReactNode }) {
  return (
    <Suspense fallback={null}>
      <AdminGate>{children}</AdminGate>
    </Suspense>
  )
}
