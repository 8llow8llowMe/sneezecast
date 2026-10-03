'use client'

import { type ComponentType, useEffect, useRef, useSyncExternalStore } from 'react'

/**
 * 처음 쓸 때 받는 컴포넌트(지연 로드, #184). 화면 첫 그림에 보이지 않는 시트 · 대화상자에 쓴다(docs/conventions.md "공통 컴포넌트").
 *
 * `next/dynamic`(React.lazy + Suspense)을 쓰지 않는다. 처음 그릴 때 한 번은 반드시 멈추고(Suspense), 멈췄던 자리를 다시 보일 때
 * React 가 최대 약 300ms 를 미룬다 — 프로덕션 빌드에서 보고 버튼 → 시트가 로컬에서도 약 330ms, 동의 시트 → 보고 흐름 사이에
 * 홈이 약 310ms 비쳤다(헤드리스 Chrome). 여기서는 받은 뒤에야 그리고, 이미 받은 모듈은 같은 그림에서 바로 그린다.
 */
export type LazyComponent<P> = {
  /** 받는다. 받는 중이면 같은 약속을 돌려주고, 실패하면 다음 호출이 다시 받는다(번들러가 실패한 청크를 기억하면 같은 오류다) */
  load: () => Promise<ComponentType<P>>
  /** 받은 컴포넌트. 아직이면 null */
  peek: () => ComponentType<P> | null
  subscribe: (listener: () => void) => () => void
}

/** 유휴 시간이 오지 않는 환경(`requestIdleCallback` 이 없는 Safari 등)에서 미리 받기까지 기다리는 시간 */
export const IDLE_FALLBACK_DELAY_MS = 2000
/** 유휴 시간이 끝내 오지 않아도(계속 바쁜 화면) 이 시간 안에는 미리 받는다 */
const IDLE_TIMEOUT_MS = 5000

/**
 * 하이드레이션 뒤 유휴 시간에 지연 로드할 컴포넌트를 미리 받는다(#184). 첫 로드 HTML · 초기 스크립트에는 들지 않으면서,
 * 사람이 시트를 열 때는 대개 이미 받아 둬 바로 그린다 — 받는 동안 화면이 눌려 같은 시트가 두 번 쌓이거나 다른 시트가 겹치는 틈을 줄인다.
 * 실패는 조용히 버린다(열 때 다시 받아 보고 그때 알린다). 돌려준 함수로 취소한다 — effect 의 정리에 쓴다.
 */
export function preloadWhenIdle(
  lazies: ReadonlyArray<{ load: () => Promise<unknown> }>,
): () => void {
  const preload = () => {
    for (const lazy of lazies) lazy.load().catch(() => undefined)
  }
  if (typeof window.requestIdleCallback === 'function') {
    const id = window.requestIdleCallback(preload, { timeout: IDLE_TIMEOUT_MS })
    return () => window.cancelIdleCallback(id)
  }
  const timer = window.setTimeout(preload, IDLE_FALLBACK_DELAY_MS)
  return () => window.clearTimeout(timer)
}

/** 모듈 맨 위에서 만든다. `loader` 안의 `import()` 가 번들러에게 따로 청크를 만들게 한다 */
export function lazyComponent<P>(loader: () => Promise<ComponentType<P>>): LazyComponent<P> {
  let component: ComponentType<P> | null = null
  let pending: Promise<ComponentType<P>> | null = null
  const listeners = new Set<() => void>()

  return {
    load: () => {
      if (component) return Promise.resolve(component)
      pending ??= loader().then(
        (loaded) => {
          component = loaded
          pending = null
          listeners.forEach((listener) => listener())
          return loaded
        },
        (error: unknown) => {
          pending = null
          throw error
        },
      )
      return pending
    },
    peek: () => component,
    subscribe: (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
  }
}

/**
 * 열 때(`open` 이 true) 받기 시작하고, 받은 컴포넌트를 돌려준다. 아직이면 null 이라 부르는 쪽은 그리지 않는다.
 *
 * - **한 번 받으면 닫혀도 계속 돌려준다**(모듈에 남는다). 닫힘은 `Modal` 이 effect 의 `dialog.close()` 로 처리하고(포커스가 연 버튼으로
 *   돌아간다), 보고 흐름처럼 단계 사이 상태를 가진 시트는 열려 있던 동안의 상태를 이어 써야 해서 닫자마자 내리면 안 된다.
 * - 서버 그림 · 하이드레이션 첫 그림은 늘 null 이다(서버는 받지 않는다). 받는 동안은 아무것도 그리지 않는다 — 닫힌 시트는 원래
 *   보이지 않고 열린 시트는 최상위 층(`showModal`)이라 화면이 밀리지 않는다.
 * - 받지 못하면(화면을 연 뒤 연결이 끊김, 배포 뒤 오래 열어 둔 탭이라 청크 이름(해시)이 바뀜) `onError` 를 부른다. 열려 있는 동안 한 번이고, 닫았다 다시 열면 다시 받아 본다.
 *   부르는 쪽은 알림을 띄우고 시트 쿼리를 닫는다 — 화면 전체가 오류 화면으로 바뀌지 않는다.
 */
export function useLazyComponent<P>(
  lazy: LazyComponent<P>,
  open: boolean,
  onError: () => void,
): ComponentType<P> | null {
  const component = useSyncExternalStore(lazy.subscribe, lazy.peek, () => null)
  const onErrorRef = useRef(onError)
  useEffect(() => {
    onErrorRef.current = onError
  })

  useEffect(() => {
    if (!open || lazy.peek()) return
    let active = true
    lazy.load().catch(() => {
      if (active) onErrorRef.current()
    })
    return () => {
      active = false
    }
  }, [open, lazy])

  return component
}
