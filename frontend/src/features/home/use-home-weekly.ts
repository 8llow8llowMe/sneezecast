'use client'

import { useCallback, useEffect, useState } from 'react'

import { retryMemberInfo } from '@/features/auth/member-info'
import { useAuthSettled } from '@/features/auth/use-auth'
import { useMemberRegion, useMemberRegionStatus } from '@/features/me/member-region'

import type { HomeWeekly } from './types'
import { fetchHomeWeekly } from './weekly-aggregate'

/**
 * 실데이터 홈 · 지도가 읽는 이번 주 동네 집계(`T` — 홈은 동네 하나 `HomeWeekly`, 지도는 시군구 하나 `MapWeek`).
 * - `loading`: 회원 상태 · 내 동네를 아직 모름(세션 복원 · 내 동네 읽는 중)이거나 집계를 읽는 중
 * - `failed`: 내 동네 또는 집계를 읽지 못함. `retry` 는 실패한 쪽을 다시 읽는다
 * - `no-region`: 볼 동네가 없음 — 둘러보기 동네도 내 동네도 없거나, 집계가 그 코드를 모름(404)
 * - `ready`: 집계를 받음. `receivedAt` 은 응답을 받은 시각(ISO, 오프라인 띠)
 */
export type WeeklyLoad<T> =
  | { status: 'loading' }
  | { status: 'failed'; retry: () => void }
  | { status: 'no-region' }
  | { status: 'ready'; week: T; receivedAt: string }

export type HomeWeeklyLoad = WeeklyLoad<HomeWeekly>

/** 집계를 아직 그릴 수 없는 상태(`WeeklyLoadState` 가 그린다) */
export type PendingWeeklyLoad = Exclude<WeeklyLoad<never>, { status: 'ready' }>

type Settled<T> =
  { status: 'no-region' } | { status: 'ready'; week: T; receivedAt: string } | 'failed'

const LOADING: PendingWeeklyLoad = { status: 'loading' }
const NO_REGION: PendingWeeklyLoad = { status: 'no-region' }

/** 실데이터 홈의 이번 주 동네 집계(`fetchHomeWeekly`). 규칙은 `useRegionWeekly` */
export function useHomeWeekly(enabled: boolean, regionCode: string | null): HomeWeeklyLoad | null {
  return useRegionWeekly(enabled, regionCode, fetchHomeWeekly)
}

/**
 * 보이는 동네(둘러보기 동네 `regionCode`, 없으면 회원의 내 동네)의 이번 주 집계를 `load(code)` 로 읽는다(홈 `fetchHomeWeekly` ·
 * 지도 `fetchMapWeek` — 모듈 함수처럼 바뀌지 않는 함수를 넘긴다). `load` 가 null 이면 그 코드를 집계가 모른다(동네 없음).
 * `enabled` 가 false(목데이터 — 서버 페이지가 목 값을 넘김)면 요청하지 않고 null 이다.
 *
 * 둘러보기 동네가 없으면 회원 상태가 정해진 뒤(`useAuthSettled`) 내 동네로 정한다 — 세션을 되살리는 동안은 비회원으로 보여
 * 그때 정하면 회원에게 동네를 고르라고 잘못 안내한다. 동네가 바뀌면(다른 탭에서 내 동네를 바꿈 등) 다시 읽고 늦은 응답은 버린다.
 */
export function useRegionWeekly<T>(
  enabled: boolean,
  regionCode: string | null,
  load: (code: string) => Promise<T | null>,
): WeeklyLoad<T> | null {
  const settled = useAuthSettled()
  const memberRegion = useMemberRegion()
  const memberRegionStatus = useMemberRegionStatus()
  const [result, setResult] = useState<{ key: string; load: Settled<T> } | null>(null)
  const [attempt, setAttempt] = useState(0)

  const waitingMember = regionCode === null && (!settled || memberRegionStatus === 'loading')
  const memberFailed = regionCode === null && !waitingMember && memberRegionStatus === 'failed'
  const code = regionCode ?? memberRegion?.code ?? null
  const target = enabled && !waitingMember && !memberFailed ? code : null
  const key = target === null ? null : `${target}#${attempt}`

  useEffect(() => {
    if (target === null || key === null) return
    let live = true
    load(target).then(
      (week) => {
        if (!live) return
        const next: Settled<T> = week
          ? { status: 'ready', week, receivedAt: new Date().toISOString() }
          : { status: 'no-region' }
        setResult({ key, load: next })
      },
      () => {
        if (live) setResult({ key, load: 'failed' })
      },
    )
    return () => {
      live = false
    }
  }, [target, key, load])

  const retry = useCallback(() => setAttempt((value) => value + 1), [])

  if (!enabled) return null
  if (waitingMember) return LOADING
  if (memberFailed) return { status: 'failed', retry: retryMemberInfo }
  if (target === null) return NO_REGION
  if (result?.key !== key) return LOADING
  return result.load === 'failed' ? { status: 'failed', retry } : result.load
}
