// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { formatMinSec, secondsLeft, useSecondsLeft } from './countdown'

describe('formatMinSec', () => {
  it.each([
    [300, '5:00'],
    [299, '4:59'],
    [42, '0:42'],
    [0, '0:00'],
    [-3, '0:00'],
  ])('%i → %s', (value, expected) => {
    expect(formatMinSec(value)).toBe(expected)
  })
})

describe('secondsLeft', () => {
  it('남은 초를 올림하고 지나면 0 이다', () => {
    expect(secondsLeft(10_000, 0)).toBe(10)
    expect(secondsLeft(10_000, 9_001)).toBe(1)
    expect(secondsLeft(10_000, 12_000)).toBe(0)
    expect(secondsLeft(null, 0)).toBe(0)
  })
})

describe('useSecondsLeft', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('1초마다 줄고 0 에서 멈춘다', () => {
    const deadline = Date.now() + 3_000
    const { result } = renderHook(() => useSecondsLeft(deadline))
    expect(result.current).toBe(3)

    act(() => {
      vi.advanceTimersByTime(1_000)
    })
    expect(result.current).toBe(2)

    act(() => {
      vi.advanceTimersByTime(5_000)
    })
    expect(result.current).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('타이머가 늦게 돌아도 지금 시각으로 다시 센다', () => {
    const deadline = Date.now() + 60_000
    const { result } = renderHook(() => useSecondsLeft(deadline))
    // 백그라운드 탭처럼 타이머 없이 시각만 40초 흐른 뒤 한 번 돈다
    act(() => {
      vi.setSystemTime(Date.now() + 40_000)
      vi.advanceTimersByTime(1_000)
    })
    expect(result.current).toBe(19)
  })

  it('마감이 바뀐 첫 렌더부터 지금 시각으로 센다', () => {
    const first = Date.now() + 60_000
    const { result, rerender } = renderHook(({ deadline }) => useSecondsLeft(deadline), {
      initialProps: { deadline: first },
    })
    // 타이머가 돌지 않은 채 시각만 30초 흐른 뒤 마감이 바뀐다 (다시 받기)
    vi.setSystemTime(Date.now() + 30_000)
    const second = Date.now() + 300_000
    rerender({ deadline: second })
    expect(result.current).toBe(300)
  })

  it('화면을 떠나면 타이머를 정리한다', () => {
    const deadline = Date.now() + 60_000
    const { unmount } = renderHook(() => useSecondsLeft(deadline))
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
