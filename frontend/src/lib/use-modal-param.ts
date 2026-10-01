'use client'

import { useCallback } from 'react'
import { useSearchParams } from 'next/navigation'

/**
 * 이 훅이 쌓은 기록의 깊이를 담는 `history.state` 키. Next 는 사용자가 넣은 state 에 자기 값을 덧붙여
 * 보존하므로 우리 키를 같이 둘 수 있다.
 */
const DEPTH_KEY = 'sneezecastModalDepth'

function currentDepth(): number {
  const state: unknown = window.history.state
  if (state && typeof state === 'object' && DEPTH_KEY in state) {
    const depth = (state as Record<string, unknown>)[DEPTH_KEY]
    return typeof depth === 'number' ? depth : 0
  }
  return 0
}

/**
 * 화면 위에 뜨는 시트 · 대화상자의 상태를 주소 쿼리 하나(`?name=value`)에 둔다.
 * 새로고침 · 공유해도 같은 화면이 열린다 (docs/conventions.md "데이터와 환경변수").
 *
 * - `open` · `push`: 기록을 하나 쌓는다. 휴대폰 뒤로 가기가 이전 단계로, 첫 단계에서는 닫기로 이어진다
 * - `replace`: 기록을 쌓지 않고 값만 바꾼다. 보낸 뒤 완료 화면처럼 **뒤로 가서 되돌아오면 안 되는 단계**에 쓴다.
 *   `remove` 에 적은 다른 쿼리를 같은 기록 항목에서 함께 지울 수 있다(나머지 쿼리는 그대로 둔다)
 * - `back`: 이전 단계로. 이 훅이 쌓은 기록이 있으면 뒤로 가고(휴대폰 뒤로 가기와 같다), 주소로 바로 들어와
 *   쌓은 기록이 없으면 사이트를 떠나지 않게 `fallback` 단계로 바꾼다
 * - `close`: 이 훅이 쌓은 만큼만 되돌린다. 주소로 바로 들어와 쌓은 기록이 없으면 쿼리만 지운다
 *
 * **쌓은 깊이를 컴포넌트 변수가 아니라 `history.state` 에 둔다.** 사용자가 뒤로 가기로 한 단계 돌아온 뒤
 * 닫기를 누르면, 변수로 센 깊이는 실제보다 커서 홈보다 더 뒤로 가 버린다. 각 기록 항목이 자기 깊이를
 * 들고 있으면 지금 서 있는 항목의 값이 늘 맞다.
 *
 * router.push 대신 history API 를 쓴다. Next 가 useSearchParams 와 맞춰 주고 서버에 화면을 다시 요청하지 않는다.
 */
export function useModalParam(name: string) {
  const searchParams = useSearchParams()
  const value = searchParams.get(name)

  const urlWith = useCallback(
    (next: string | null, remove: readonly string[] = []) => {
      const params = new URLSearchParams(searchParams)
      remove.forEach((key) => params.delete(key))
      if (next === null) params.delete(name)
      else params.set(name, next)
      const query = params.toString()
      return query ? `?${query}` : window.location.pathname
    },
    [name, searchParams],
  )

  const push = useCallback(
    (next: string) => {
      window.history.pushState({ [DEPTH_KEY]: currentDepth() + 1 }, '', urlWith(next))
    },
    [urlWith],
  )

  const replace = useCallback(
    (next: string, { remove }: { remove?: readonly string[] } = {}) => {
      window.history.replaceState({ [DEPTH_KEY]: currentDepth() }, '', urlWith(next, remove))
    },
    [urlWith],
  )

  const back = useCallback(
    (fallback: string) => {
      if (currentDepth() > 0) window.history.back()
      else replace(fallback)
    },
    [replace],
  )

  const close = useCallback(() => {
    const depth = currentDepth()
    if (depth > 0) {
      window.history.go(-depth)
      return
    }
    window.history.replaceState({ [DEPTH_KEY]: 0 }, '', urlWith(null))
  }, [urlWith])

  return { value, open: push, push, replace, back, close }
}
