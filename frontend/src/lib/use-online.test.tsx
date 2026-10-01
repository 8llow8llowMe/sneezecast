// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { getOnlineSnapshot, subscribeOnline, useOnline } from './use-online'

function setNavigatorOnline(value: boolean) {
  vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(value)
}

function OnlineProbe() {
  return <span>{useOnline() ? 'online' : 'offline'}</span>
}

describe('useOnline', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('브라우저가 온라인이면 true, 오프라인이면 false 를 읽는다', () => {
    setNavigatorOnline(true)
    expect(getOnlineSnapshot()).toBe(true)
    setNavigatorOnline(false)
    expect(getOnlineSnapshot()).toBe(false)
  })

  it('offline · online 이벤트를 받으면 값이 바뀐다', () => {
    setNavigatorOnline(true)
    const { result } = renderHook(() => useOnline())
    expect(result.current).toBe(true)

    setNavigatorOnline(false)
    act(() => {
      window.dispatchEvent(new Event('offline'))
    })
    expect(result.current).toBe(false)

    setNavigatorOnline(true)
    act(() => {
      window.dispatchEvent(new Event('online'))
    })
    expect(result.current).toBe(true)
  })

  it('구독을 끊으면 이벤트가 와도 부르지 않는다', () => {
    const listener = vi.fn()
    const unsubscribe = subscribeOnline(listener)
    window.dispatchEvent(new Event('offline'))
    expect(listener).toHaveBeenCalledTimes(1)

    unsubscribe()
    window.dispatchEvent(new Event('online'))
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('서버 그림은 오프라인이어도 온라인으로 그린다 — 하이드레이션 첫 그림과 맞춘다', () => {
    setNavigatorOnline(false)
    const html = renderToString(<OnlineProbe />)
    expect(html).toContain('online')
    expect(html).not.toContain('offline')
  })
})
