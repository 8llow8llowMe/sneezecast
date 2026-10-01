'use client'

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
} from 'react'
import { usePathname, useRouter } from 'next/navigation'

import { canGoBackTo, nextTrail } from '@/features/onboarding/onboarding-trail'

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
 * 내 정보(`/me`)와 그 아래 계정 화면(`/me/devices` · `/me/password`) 사이의 앱 안 이동 기록 (`app/me/layout.tsx`).
 *
 * 첫 진입의 "뒤로" 와 같은 판단이다(docs/conventions.md "단계 화면의 뒤로", `features/onboarding/onboarding-trail.ts`).
 * 내 정보는 첫 진입 Provider 밖이라 같은 기록을 이 레이아웃이 따로 센다 — 레이아웃은 세 화면 사이를 오가도 다시 그려지지 않고,
 * 다른 곳(홈 · 지도)으로 나가면 기록이 사라진다. 주소의 경로만 보고 쿼리는 보지 않는다
 * (내 정보의 확인 대화상자 `?confirm=` 은 같은 경로라 기록에 들지 않는다).
 *
 * 비밀번호를 바꾸거나 정한 뒤의 알림도 여기 둔다(`leaveNotice` → 내 정보의 `takeNotice`). 주소 쿼리로 넘기면 성공 뒤
 * `router.back()` 으로 돌아갈 수 없고(돌아갈 기록 항목의 주소를 바꿀 수 없다), replace 하면 기록에 `/me` 가 두 번 남아
 * 휴대폰 뒤로 가기가 한 번 헛돈다. 레이아웃 메모리라 새로고침 · 다른 곳으로 나가면 사라진다 — 알림은 그래도 된다.
 * 레이아웃이 다시 마운트되면(홈에 나갔다 브라우저 뒤로로 돌아옴) 이동 기록도 비어, 그때의 뒤로는 내 정보로 replace 해
 * 기록에 `/me` 가 두 번 남는다. 첫 진입 Provider 와 같은 성질이라 그대로 둔다.
 */
export function MeTrailProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  // 렌더에 쓰지 않는 이동 기록이라 ref 에 둔다. 주소가 바뀐 뒤(effect)에 갱신한다
  const trail = useRef<string[]>([])
  const replacing = useRef(false)
  const notice = useRef<MeNotice | null>(null)

  useEffect(() => {
    trail.current = nextTrail(trail.current, pathname, replacing.current)
    replacing.current = false
  }, [pathname])

  const replace = useCallback(
    (path: string) => {
      replacing.current = true
      router.replace(path)
    },
    [router],
  )

  const goBack = useCallback(
    (fallback: string) => {
      if (canGoBackTo(trail.current, [ME_PATH])) router.back()
      else replace(fallback)
    },
    [router, replace],
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
