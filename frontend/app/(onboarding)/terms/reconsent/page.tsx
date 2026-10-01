import { Suspense } from 'react'
import type { Metadata } from 'next'

import { TermsReconsentScreen } from '@/features/auth/terms-reconsent-screen'

export const metadata: Metadata = { title: '약관 재동의' }

/**
 * S02-3 약관 재동의 (Setup-3-reconsent). 필수 약관이 개정된 회원을 홈 · 내 정보가 먼저 보낸다.
 * `?next=` 는 마친 뒤 돌아갈 곳(허용 목록 밖이면 홈)이고, 동네 · 목 덮어쓰기(`?mock-auth=` · `?mock-required=` 등)는 화면이 주소에서 읽는다.
 *
 * 화면이 주소 쿼리(`useSearchParams`)를 읽어 정적 생성에 Suspense 경계가 있어야 한다. 화면은 하이드레이션 뒤에야 그리므로
 * 대체 그림은 비워 둔다.
 */
export default function TermsReconsentPage() {
  return (
    <Suspense fallback={null}>
      <TermsReconsentScreen />
    </Suspense>
  )
}
