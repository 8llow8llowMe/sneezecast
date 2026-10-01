'use client'

import { useEffect, useRef } from 'react'

/**
 * 화면이 아직 떠 있는지. 응답을 기다리는 동안 사용자가 다른 화면으로 가면 `current` 가 false 가 된다.
 *
 * 늦게 온 응답으로 상태를 바꾸거나 이동하지 않게, `await` 뒤에 `if (!active.current) return` 으로 버린다.
 * 요청 자체를 끊는 일(AbortController)은 API 계층(`src/lib/api/`)이 생길 때 붙인다.
 */
export function useActiveRef() {
  const active = useRef(true)
  useEffect(() => {
    // 개발 모드 StrictMode 는 effect 를 정리 → 다시 실행하므로 여기서 다시 켠다
    active.current = true
    return () => {
      active.current = false
    }
  }, [])
  return active
}
