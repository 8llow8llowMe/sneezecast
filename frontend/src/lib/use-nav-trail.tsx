'use client'

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useInsertionEffect,
  useMemo,
  useRef,
} from 'react'
import { usePathname, useRouter } from 'next/navigation'

import {
  canGoBackTo,
  nextTrail,
  type PendingReplace,
  type PreviousPaths,
  settleReplace,
} from './nav-trail'

type NavTrail = {
  /**
   * 뒤로. 지금 화면 바로 앞이 앱 안 기록이면 기록을 되돌리고(`router.back`, 휴대폰 뒤로 가기와 같다),
   * 주소로 바로 들어와 앞 기록이 없으면 `fallback` 으로 기록을 바꿔(`replace`) 간다 — 뒤로가 사이트 밖으로 나가지 않는다.
   *
   * `previousPaths` 를 주면 바로 앞이 그 후보 중 하나일 때만 되돌린다. 앞 단계가 정해진 화면(첫 진입 단계 · 내 정보 아래 계정 화면)이 쓴다.
   * 생략하면 앱 안 어느 화면에서 왔든 되돌린다. 여러 화면에서 들어오는 화면(공식 정보 · 동네 안내 · 설치 안내)이 쓴다.
   * 함수를 주면 바로 앞 경로로 판단한다 — 앞 화면이 정해지지 않았지만 몇몇 화면으로는 되돌리지 않을 때(로그인 방법 고르기) 쓴다.
   */
  goBack: (fallback: string, previousPaths?: PreviousPaths) => void
  /**
   * 기록을 쌓지 않고 간다. 경로 기록의 맨 끝도 바꾼다 — 기록을 바꾸는 앱 안 이동은 **모두** 이것으로 한다
   * (`router.replace` 를 바로 부르면 기록이 실제보다 길어져 뒤로가 사이트 밖으로 나갈 수 있다. ESLint 가 막는다)
   */
  replace: (path: string) => void
}

const NavTrailContext = createContext<NavTrail | null>(null)

/**
 * 앱 안 이동 경로 기록 (`app/layout.tsx`). 모든 화면의 "뒤로" 가 같은 기록으로 판단한다(docs/conventions.md "화면의 뒤로").
 *
 * 루트 레이아웃에 두는 이유: 공식 정보 · 동네 안내 · 설치 안내는 홈 · 내 정보와 묶는 레이아웃이 없어, 이동 기록을 셀 자리가 루트뿐이다.
 * 첫 진입 · 내 정보 레이아웃에 따로 두면 레이아웃을 나갔다 브라우저 뒤로로 돌아올 때 기록이 비어 기록에 앞 화면이 두 번 남는다.
 * 비용: 주소가 바뀔 때마다 이 Provider 가 다시 그려지고 effect 하나가 경로 배열을 고친다. 값(`goBack` · `replace`)은 바뀌지 않아
 * 아래 화면은 다시 그리지 않는다. 기록은 렌더에 쓰지 않아 ref 에 두고, 새로고침 · 새 탭이면 비어 시작한다(브라우저 저장소에 남기지 않는다).
 */
export function NavTrailProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  // 렌더에 쓰지 않는 이동 기록이라 ref 에 둔다. 주소가 바뀐 뒤(effect)에 갱신한다
  const trail = useRef<string[]>([])
  // 걸어 둔 replace (nav-trail.ts `settleReplace`)
  const pending = useRef<PendingReplace | null>(null)
  // 지금 그린 경로. 아래 화면의 effect 가 replace 를 걸 때 "건 화면" 으로 쓴다. 기록 effect 는 자식 effect 보다 늦게 돌아,
  // 도착한 커밋에서 걸면 기록의 맨 끝은 아직 앞 화면이다. insertion effect 는 그 커밋의 어떤 layout · 일반 effect 보다 먼저 돈다
  const current = useRef(pathname)
  useInsertionEffect(() => {
    current.current = pathname
  }, [pathname])

  useEffect(() => {
    const settled = settleReplace(pending.current, pathname)
    pending.current = settled.pending
    trail.current = nextTrail(trail.current, pathname, settled.replaced)
  }, [pathname])

  const replace = useCallback(
    (path: string) => {
      const to = new URL(path, 'http://localhost').pathname
      // 같은 경로로의 replace(쿼리만 바뀜)는 경로가 바뀌지 않아 기록 effect 가 돌지 않는다 — 걸어 두면 나중 이동에 잘못 쓰인다
      if (to !== current.current) pending.current = { from: current.current, to }
      router.replace(path)
    },
    [router],
  )

  const goBack = useCallback(
    (fallback: string, previousPaths?: PreviousPaths) => {
      if (canGoBackTo(trail.current, previousPaths)) router.back()
      else replace(fallback)
    },
    [router, replace],
  )

  const value = useMemo(() => ({ goBack, replace }), [goBack, replace])
  return <NavTrailContext value={value}>{children}</NavTrailContext>
}

/**
 * 화면의 "뒤로" 판단(`NavTrailProvider`).
 *
 * Provider 밖이면 앞 기록을 모르는 것으로 본다 — `goBack` 은 늘 `fallback` 으로 replace 한다(사이트 밖으로 나가지 않는 쪽).
 * 앱은 루트 레이아웃이 늘 Provider 를 두므로 이 경우는 화면을 단독으로 그리는 테스트뿐이다. 앱 안에서 왔을 때의 뒤로를
 * 확인하는 테스트는 Provider 로 감싸고 앞 주소부터 그린다.
 */
export function useNavTrail(): NavTrail {
  const router = useRouter()
  const trail = useContext(NavTrailContext)
  const untracked = useMemo<NavTrail>(
    () => ({
      goBack: (fallback) => router.replace(fallback),
      replace: (path) => router.replace(path),
    }),
    [router],
  )
  return trail ?? untracked
}
