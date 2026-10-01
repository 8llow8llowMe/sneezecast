'use client'

import { useCallback, useRef } from 'react'
import { useSearchParams } from 'next/navigation'

/** 판단 기준 시트를 여는 주소 (docs/design/SCREENS.md S11 `/?explain=1`) */
export const EXPLAIN_PARAM = 'explain'

/**
 * 판단 기준 시트의 열림 상태를 주소(`?explain=1`)에 둔다. 주소를 공유하거나 새로고침해도 같은 화면이 열린다.
 *
 * - **앱 안에서 열면 기록을 하나 쌓고, 닫을 때 뒤로 간다.** 휴대폰 뒤로 가기가 시트를 닫고,
 *   닫은 뒤 뒤로 가기가 시트를 다시 열지 않는다.
 * - 주소로 바로 들어와 열린 경우에는 쌓은 기록이 없으므로 주소만 바꾼다 — 뒤로 가면 사이트를 떠나게 된다.
 *
 * `window.history` 를 직접 쓴다. Next 는 pushState · replaceState 를 useSearchParams 와 맞춰 주고,
 * router.push 와 달리 서버에 화면을 다시 요청하지 않는다. 다른 쿼리(`?mock=` 등)는 그대로 둔다.
 */
export function useExplainParam() {
  const searchParams = useSearchParams()
  const pushed = useRef(false)

  const open = searchParams.get(EXPLAIN_PARAM) === '1'

  const openExplain = useCallback(() => {
    const params = new URLSearchParams(searchParams)
    params.set(EXPLAIN_PARAM, '1')
    window.history.pushState(null, '', `?${params.toString()}`)
    pushed.current = true
  }, [searchParams])

  const closeExplain = useCallback(() => {
    if (pushed.current) {
      pushed.current = false
      window.history.back()
      return
    }
    const params = new URLSearchParams(searchParams)
    params.delete(EXPLAIN_PARAM)
    const query = params.toString()
    window.history.replaceState(null, '', query ? `?${query}` : window.location.pathname)
  }, [searchParams])

  return { open, openExplain, closeExplain }
}
