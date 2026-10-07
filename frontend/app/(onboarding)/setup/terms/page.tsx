import { Suspense } from 'react'
import type { Metadata } from 'next'

import { TermsScreen } from '@/features/auth/terms-screen'

export const metadata: Metadata = { title: '가입 동의' }

/**
 * S02-3 가입 동의 (3 / 4). 화면이 서비스 안내 시트의 쿼리(`?info=privacy`, `useSearchParams`)를 읽어 정적 생성에 Suspense 경계가
 * 있어야 한다. 서버 그림에는 첫 진입 Provider 의 값(고른 동네 등)이 없어 화면이 늘 비므로 대체 그림도 비워 둔다.
 */
export default function SetupTermsPage() {
  return (
    <Suspense fallback={null}>
      <TermsScreen />
    </Suspense>
  )
}
