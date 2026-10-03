// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  IDLE_FALLBACK_DELAY_MS,
  lazyComponent,
  preloadWhenIdle,
  useLazyComponent,
} from './lazy-component'

function Sheet({ label }: { label: string }) {
  return <p>{label}</p>
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function Host({
  lazy,
  open,
  onError = () => {},
}: {
  lazy: ReturnType<typeof lazyComponent<{ label: string }>>
  open: boolean
  onError?: () => void
}) {
  const Loaded = useLazyComponent(lazy, open, onError)
  return Loaded ? <Loaded label={open ? '열림' : '닫힘'} /> : <span>없음</span>
}

describe('lazyComponent', () => {
  it('받는 중에는 같은 약속을 돌려주고, 받은 뒤에는 다시 부르지 않는다', async () => {
    const loader = vi.fn(() => Promise.resolve(Sheet))
    const lazy = lazyComponent(loader)

    expect(lazy.peek()).toBeNull()
    const [a, b] = [lazy.load(), lazy.load()]
    expect(a).toBe(b)
    await a
    expect(lazy.peek()).toBe(Sheet)
    await lazy.load()
    expect(loader).toHaveBeenCalledTimes(1)
  })

  it('실패하면 다음 호출이 다시 받는다', async () => {
    const loader = vi
      .fn<() => Promise<typeof Sheet>>()
      .mockRejectedValueOnce(new Error('ChunkLoadError'))
      .mockResolvedValueOnce(Sheet)
    const lazy = lazyComponent(loader)

    await expect(lazy.load()).rejects.toThrow('ChunkLoadError')
    expect(lazy.peek()).toBeNull()
    await expect(lazy.load()).resolves.toBe(Sheet)
    expect(loader).toHaveBeenCalledTimes(2)
  })
})

describe('useLazyComponent', () => {
  it('열기 전에는 받지 않고 아무것도 그리지 않는다', () => {
    const loader = vi.fn(() => Promise.resolve(Sheet))
    render(<Host lazy={lazyComponent(loader)} open={false} />)

    expect(screen.getByText('없음')).toBeDefined()
    expect(loader).not.toHaveBeenCalled()
  })

  it('열면 받기 시작하고, 받은 뒤 그린다. 닫혀도 계속 그린다', async () => {
    const pending = deferred<typeof Sheet>()
    const lazy = lazyComponent(() => pending.promise)
    const { rerender } = render(<Host lazy={lazy} open />)

    expect(screen.getByText('없음')).toBeDefined()
    await act(async () => {
      pending.resolve(Sheet)
      await pending.promise
    })
    expect(screen.getByText('열림')).toBeDefined()

    rerender(<Host lazy={lazy} open={false} />)
    expect(screen.getByText('닫힘')).toBeDefined()
  })

  it('이미 받은 모듈은 연 그림에서 바로 그린다 — 두 시트가 바뀌는 사이에 빈 그림이 없다', async () => {
    const lazy = lazyComponent(() => Promise.resolve(Sheet))
    await lazy.load()

    render(<Host lazy={lazy} open />)
    expect(screen.getByText('열림')).toBeDefined()
  })

  it('서버 그림에는 받은 모듈도 그리지 않는다 — 하이드레이션 첫 그림과 같다', async () => {
    const lazy = lazyComponent(() => Promise.resolve(Sheet))
    await lazy.load()

    expect(renderToString(<Host lazy={lazy} open />)).toContain('없음')
  })

  it('받지 못하면 열려 있는 동안 한 번 알리고, 닫았다 다시 열면 다시 받아 본다', async () => {
    const loader = vi.fn(() => Promise.reject<typeof Sheet>(new Error('ChunkLoadError')))
    const lazy = lazyComponent(loader)
    const onError = vi.fn()
    const { rerender } = renderHook(({ open }) => useLazyComponent(lazy, open, onError), {
      initialProps: { open: true },
    })

    await waitFor(() => expect(onError).toHaveBeenCalledTimes(1))
    rerender({ open: false })
    rerender({ open: true })
    await waitFor(() => expect(onError).toHaveBeenCalledTimes(2))
    expect(loader).toHaveBeenCalledTimes(2)
  })

  it('받는 동안 닫으면 늦게 온 실패를 알리지 않는다', async () => {
    const pending = deferred<typeof Sheet>()
    const lazy = lazyComponent(() => pending.promise)
    const onError = vi.fn()
    const { rerender } = renderHook(({ open }) => useLazyComponent(lazy, open, onError), {
      initialProps: { open: true },
    })

    rerender({ open: false })
    await act(async () => {
      pending.reject(new Error('ChunkLoadError'))
      await pending.promise.catch(() => undefined)
    })
    expect(onError).not.toHaveBeenCalled()
  })
})

describe('preloadWhenIdle', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('유휴 시간에 모두 받고, 실패는 조용히 버린다', async () => {
    let idle: IdleRequestCallback | null = null
    vi.stubGlobal(
      'requestIdleCallback',
      vi.fn((callback: IdleRequestCallback) => {
        idle = callback
        return 1
      }),
    )
    const ok = { load: vi.fn(() => Promise.resolve(Sheet)) }
    const failing = { load: vi.fn(() => Promise.reject(new Error('ChunkLoadError'))) }

    preloadWhenIdle([ok, failing])
    expect(ok.load).not.toHaveBeenCalled()
    ;(idle as IdleRequestCallback | null)?.({ didTimeout: false, timeRemaining: () => 50 })
    expect(ok.load).toHaveBeenCalledTimes(1)
    expect(failing.load).toHaveBeenCalledTimes(1)
    // 처리되지 않은 거부가 남지 않는다
    await Promise.resolve()
  })

  it('유휴 시간이 없는 환경(Safari 등)은 잠시 뒤 받는다', () => {
    vi.useFakeTimers()
    vi.stubGlobal('requestIdleCallback', undefined)
    const lazy = { load: vi.fn(() => Promise.resolve(Sheet)) }

    preloadWhenIdle([lazy])
    vi.advanceTimersByTime(IDLE_FALLBACK_DELAY_MS - 1)
    expect(lazy.load).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(lazy.load).toHaveBeenCalledTimes(1)
  })

  it('받기 전에 취소하면 받지 않는다(화면을 떠남)', () => {
    vi.useFakeTimers()
    vi.stubGlobal('requestIdleCallback', undefined)
    const lazy = { load: vi.fn(() => Promise.resolve(Sheet)) }

    const cancel = preloadWhenIdle([lazy])
    cancel()
    vi.advanceTimersByTime(IDLE_FALLBACK_DELAY_MS)
    expect(lazy.load).not.toHaveBeenCalled()
  })
})
