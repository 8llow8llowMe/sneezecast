'use client'

import type { ReactNode } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'

import { useSessionRole } from '@/features/auth/use-auth'
import { useMemberGate } from '@/features/me/member-gate'
import { HOME_PATH } from '@/features/onboarding/paths'
import type { SessionRole } from '@/lib/session/session-store'

/** 운영자 화면을 볼 수 있는 역할 (backend `SecurityRole`) */
const OPERATOR_ROLES: readonly SessionRole[] = ['OPERATOR', 'ADMIN']

/**
 * 운영자 화면(`/admin/*`, #219) 가드. 레이아웃(`app/admin/layout.tsx`)이 건다.
 *
 * - **비회원**은 회원 화면과 같은 가드(`useMemberGate`)로 로그인에 보낸다 — 회원 상태가 정해진 뒤에 판단하고, 로그인 뒤 이 경로로
 *   돌아온다(허용 목록 `NEXT_PATHS` 의 `/admin/review`. 목록 밖 경로면 홈)
 * - **운영자가 아닌 회원**(`USER`)은 보내지 않고 권한 없음을 알린다. 로그인으로 보내면 이미 로그인한 사람이 왜 튕겼는지 모르고,
 *   홈으로 보내면 주소를 잘못 연 것인지 권한이 없는 것인지 모른다
 * - 역할은 실데이터면 세션 요약(로그인 · 재발급 응답의 `role`), 목데이터면 `?mock-role=`(기본 `USER`)이다(`useSessionRole`).
 *   실데이터에서는 주소로 역할을 바꿀 수 없다. 화면 가드는 길 안내일 뿐이고 운영자 API 는 서버가 다시 확인한다
 *
 * 정해지기 전(서버 · 하이드레이션 첫 그림, 실데이터 세션 복원 중)에는 아무것도 그리지 않는다 — 운영자 화면이 잠깐 비치지 않게 한다.
 */
export function AdminGate({ children }: { children: ReactNode }) {
  const pathname = usePathname()
  const auth = useMemberGate({ next: pathname })
  const role = useSessionRole()
  if (!auth) return null
  if (role === null || !OPERATOR_ROLES.includes(role)) return <AdminForbidden />
  return children
}

function AdminForbidden() {
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center gap-3 px-page-mobile text-center">
      <h1 className="text-screen-title font-bold text-fg">운영자만 볼 수 있는 화면이에요</h1>
      <p className="text-body text-fg-sub">운영자 권한이 있는 계정으로 로그인해 주세요.</p>
      <Link
        href={HOME_PATH}
        className="mt-3 inline-flex h-button-sm items-center rounded-button bg-section px-5 text-body font-semibold text-fg"
      >
        홈으로 가기
      </Link>
    </main>
  )
}
