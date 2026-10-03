'use client'

import { useEffect, useRef } from 'react'
import { flushSync } from 'react-dom'

/**
 * 화면이 뒤로 가기 캐시(bfcache)에 들어가기 직전(`pagehide` 의 `persisted`)과 거기서 되살아날 때(`pageshow` 의 `persisted`)
 * `clear` 를 부른다(#186). 공용 기기에서 다음 사람이 뒤로 가기로 앞 사람이 입력만 하고 보내지 않은 이메일 · 비밀번호 · 인증 코드나
 * 메모리의 가입 초안 · 재설정 토큰을 보지 못하게 한다.
 *
 * - `clear` 안의 상태 바꾸기는 `flushSync` 로 그 자리에서 커밋한다 — 얼기 전 DOM(입력칸 값)에 남지 않게 한다
 * - 되살아날 때 한 번 더 부르는 것은 방어다(얼기 전에 커밋하지 못한 경우)
 * - 캐시에 들지 않는 떠나기 · 열기(`persisted` false)는 아무 일도 없다
 *
 * `clear` 는 마지막으로 그린 것을 부른다(리스너를 다시 달지 않는다). 새 인증 폼(비밀번호 · 인증 코드 · 이메일 칸)을 만들면 이 훅을 건다
 * — docs/conventions.md "뒤로 가기 캐시(bfcache)"
 */
export function useClearOnPageFreeze(clear: () => void): void {
  const latest = useRef(clear)
  useEffect(() => {
    latest.current = clear
  })

  useEffect(() => {
    const onPageTransition = (event: PageTransitionEvent) => {
      if (event.persisted) flushSync(() => latest.current())
    }
    window.addEventListener('pagehide', onPageTransition)
    window.addEventListener('pageshow', onPageTransition)
    return () => {
      window.removeEventListener('pagehide', onPageTransition)
      window.removeEventListener('pageshow', onPageTransition)
    }
  }, [])
}
