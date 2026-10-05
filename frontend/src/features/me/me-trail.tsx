'use client'

import { createContext, type ReactNode, useCallback, useContext, useMemo, useRef } from 'react'

import { useNavTrail } from '@/lib/use-nav-trail'

import { ME_PATH, type MeNotice } from './me-paths'

type MeTrail = {
  /**
   * 내 정보로 돌아간다. 앱 안에서 내 정보를 거쳐 왔으면 기록을 되돌리고(`router.back`), 주소로 바로 들어와
   * 앞에 내 정보 기록이 없으면 `fallback`(쿼리를 남긴 내 정보 주소)으로 기록을 바꿔 간다
   */
  goBack: (fallback: string) => void
  /** 계정 화면이 일을 마치고 내 정보로 돌아가기 전에 남기는 알림. 내 정보가 한 번 읽고 비운다(`takeNotice`) */
  leaveNotice: (notice: MeNotice) => void
  /** 남긴 알림을 꺼내고 비운다. 없으면 null 이다 */
  takeNotice: () => MeNotice | null
}

const MeTrailContext = createContext<MeTrail | null>(null)

/**
 * 내 정보(`/me`)와 그 아래 계정 화면(`/me/devices` · `/me/password` · `/me/region` · `/me/nickname`)이 같이 쓰는 이동 · 알림 (`app/me/layout.tsx`).
 *
 * "뒤로" 는 앞 화면을 내 정보(`/me`)로 정해 루트의 앱 안 이동 기록에 묻는다(`useNavTrail`, docs/conventions.md "화면의 뒤로").
 * 기록은 경로만 보고 쿼리는 보지 않는다(내 정보의 확인 대화상자 `?confirm=` 은 같은 경로라 기록에 들지 않는다).
 *
 * 비밀번호 · 내 동네 · 닉네임을 바꾼 뒤의 알림도 여기 둔다(`leaveNotice` → 내 정보의 `takeNotice`). 주소 쿼리로 넘기면 성공 뒤
 * `router.back()` 으로 돌아갈 수 없고(돌아갈 기록 항목의 주소를 바꿀 수 없다), replace 하면 기록에 `/me` 가 두 번 남아
 * 휴대폰 뒤로 가기가 한 번 헛돈다. 레이아웃 메모리라 새로고침 · 다른 곳으로 나가면 사라진다 — 알림은 그래도 된다.
 */
export function MeTrailProvider({ children }: { children: ReactNode }) {
  const { goBack: goBackInTrail } = useNavTrail()
  const notice = useRef<MeNotice | null>(null)

  const goBack = useCallback(
    (fallback: string) => goBackInTrail(fallback, [ME_PATH]),
    [goBackInTrail],
  )

  const leaveNotice = useCallback((next: MeNotice) => {
    notice.current = next
  }, [])

  const takeNotice = useCallback(() => {
    const taken = notice.current
    notice.current = null
    return taken
  }, [])

  const value = useMemo(
    () => ({ goBack, leaveNotice, takeNotice }),
    [goBack, leaveNotice, takeNotice],
  )
  return <MeTrailContext value={value}>{children}</MeTrailContext>
}

export function useMeTrail(): MeTrail {
  const trail = useContext(MeTrailContext)
  if (!trail) throw new Error('useMeTrail 은 MeTrailProvider(app/me/layout.tsx) 안에서만 쓴다')
  return trail
}
