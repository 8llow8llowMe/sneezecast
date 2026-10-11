'use client'

import { useCallback, useEffect, useState } from 'react'

import { retryMemberInfo } from '@/features/auth/member-info'
import { useAuthSettled } from '@/features/auth/use-auth'
import { useMemberRegion, useMemberRegionStatus } from '@/features/me/member-region'

import type { HomeWeekly } from './types'
import { fetchHomeWeekly } from './weekly-aggregate'

/**
 * 실데이터 홈이 읽는 이번 주 동네 집계.
 * - `loading`: 회원 상태 · 내 동네를 아직 모름(세션 복원 · 내 동네 읽는 중)이거나 집계를 읽는 중
 * - `failed`: 내 동네 또는 집계를 읽지 못함. `retry` 는 실패한 쪽을 다시 읽는다
 * - `no-region`: 볼 동네가 없음 — 둘러보기 동네도 내 동네도 없거나, 집계가 그 코드를 모름(404)
 * - `ready`: 집계를 받음. `receivedAt` 은 응답을 받은 시각(ISO, 오프라인 띠)
 */
export type HomeWeeklyLoad =
  | { status: 'loading' }
  | { status: 'failed'; retry: () => void }
  | { status: 'no-region' }
  | { status: 'ready'; week: HomeWeekly; receivedAt: string }

type Settled = Exclude<HomeWeeklyLoad, { status: 'loading' } | { status: 'failed' }> | 'failed'

const LOADING: HomeWeeklyLoad = { status: 'loading' }
const NO_REGION: HomeWeeklyLoad = { status: 'no-region' }

/**
 * 보이는 동네(둘러보기 동네 `regionCode`, 없으면 회원의 내 동네)의 이번 주 집계를 읽는다(`fetchHomeWeekly`).
 * `enabled` 가 false(목데이터 — 서버 페이지가 목 주를 넘김)면 요청하지 않고 null 이다.
 *
 * 둘러보기 동네가 없으면 회원 상태가 정해진 뒤(`useAuthSettled`) 내 동네로 정한다 — 세션을 되살리는 동안은 비회원으로 보여
 * 그때 정하면 회원에게 동네를 고르라고 잘못 안내한다. 동네가 바뀌면(다른 탭에서 내 동네를 바꿈 등) 다시 읽고 늦은 응답은 버린다.
 */
export function useHomeWeekly(enabled: boolean, regionCode: string | null): HomeWeeklyLoad | null {
  const settled = useAuthSettled()
  const memberRegion = useMemberRegion()
  const memberRegionStatus = useMemberRegionStatus()
  const [result, setResult] = useState<{ key: string; load: Settled } | null>(null)
  const [attempt, setAttempt] = useState(0)

  const waitingMember = regionCode === null && (!settled || memberRegionStatus === 'loading')
  const memberFailed = regionCode === null && !waitingMember && memberRegionStatus === 'failed'
  const code = regionCode ?? memberRegion?.code ?? null
  const target = enabled && !waitingMember && !memberFailed ? code : null
  const key = target === null ? null : `${target}#${attempt}`

  useEffect(() => {
    if (target === null || key === null) return
    let live = true
    fetchHomeWeekly(target).then(
      (week) => {
        if (!live) return
        const load: Settled = week
          ? { status: 'ready', week, receivedAt: new Date().toISOString() }
          : { status: 'no-region' }
        setResult({ key, load })
      },
      () => {
        if (live) setResult({ key, load: 'failed' })
      },
    )
    return () => {
      live = false
    }
  }, [target, key])

  const retry = useCallback(() => setAttempt((value) => value + 1), [])

  if (!enabled) return null
  if (waitingMember) return LOADING
  if (memberFailed) return { status: 'failed', retry: retryMemberInfo }
  if (target === null) return NO_REGION
  if (result?.key !== key) return LOADING
  return result.load === 'failed' ? { status: 'failed', retry } : result.load
}
