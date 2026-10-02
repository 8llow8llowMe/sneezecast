'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'

import { assignLocation } from '@/lib/location'
import { useActiveRef } from '@/lib/use-active-ref'
import { useDataSource } from '@/lib/use-data-source'

import { startKakaoLogin } from './kakao-client'

/** 카카오 로그인을 시작하지 못한 까닭. 화면이 알림 모양(토스트 · 상자)을 고른다 */
export type KakaoStartFailure = 'limited' | 'failed'

export const KAKAO_START_FAILURE_TEXT: Readonly<Record<KakaoStartFailure, string>> = {
  failed: '카카오 로그인을 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
  // AUTH_028 — 이 기기(IP)에서 너무 많이 시작했다. 남은 시간은 서버가 주지 않는다
  limited: '요청이 많아 잠시 막혔어요. 잠시 뒤 다시 시도해 주세요.',
}

/**
 * `카카오로 계속하기` · `다른 카카오 계정으로 계속하기`(로그인 화면 · 로그인 안내 시트 · 계정 연결 확인)가 같이 쓴다.
 *
 * - 출처는 누를 때의 `useDataSource()` 다(하이드레이션 뒤라 쿠키 값)
 * - 시작하면 이동하는 동안에도 `pending` 을 켜 둔다 — 다시 눌러 두 번 시작하지 않게 한다(누름은 ref 로도 막는다)
 * - 실데이터는 카카오 인가 화면으로 문서를 옮기고(`assignLocation`), 목은 앱 안 주소로 `router.push` 한다
 * - 카카오 화면에서 브라우저 뒤로로 돌아와 bfcache 로 다시 보이면(`pageshow` 의 `persisted`) `pending` 을 풀어 다시 누를 수 있게 한다
 * - 실패하면 `pending` 을 풀고 까닭을 돌려준다. 응답 전에 화면을 떠났으면(`useActiveRef`) 아무것도 하지 않고 null 이다
 */
export function useKakaoStart(): {
  pending: boolean
  start: (options?: { switchAccount?: boolean }) => Promise<KakaoStartFailure | null>
} {
  const router = useRouter()
  const source = useDataSource()
  const active = useActiveRef()
  const [pending, setPending] = useState(false)
  const starting = useRef(false)

  useEffect(() => {
    function restored(event: PageTransitionEvent) {
      if (!event.persisted) return
      starting.current = false
      setPending(false)
    }
    window.addEventListener('pageshow', restored)
    return () => window.removeEventListener('pageshow', restored)
  }, [])

  const start = useCallback(
    async (options: { switchAccount?: boolean } = {}): Promise<KakaoStartFailure | null> => {
      if (starting.current) return null
      starting.current = true
      setPending(true)
      let failure: KakaoStartFailure
      try {
        const result = await startKakaoLogin(source, options)
        if (!active.current) return null
        if (result.status === 'redirect') {
          if (result.external) assignLocation(result.href)
          else router.push(result.href)
          return null
        }
        failure = 'limited'
      } catch {
        if (!active.current) return null
        failure = 'failed'
      }
      starting.current = false
      setPending(false)
      return failure
    },
    [source, active, router],
  )

  return { pending, start }
}
