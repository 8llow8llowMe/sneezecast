// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useModalParam } from './use-modal-param'

// 테스트에는 Next 라우터가 없다. useSearchParams 는 지금 주소를 그대로 읽게 흉내 낸다
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
}))

function depth() {
  return (window.history.state as { sneezecastModalDepth?: number } | null)?.sneezecastModalDepth
}

describe('useModalParam', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/?mock=high')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('push 는 다른 쿼리를 남긴 채 값을 넣고 깊이를 하나 늘린다', () => {
    const { result, rerender } = renderHook(() => useModalParam('report'))

    act(() => result.current.push('start'))
    expect(window.location.search).toBe('?mock=high&report=start')
    expect(depth()).toBe(1)

    rerender()
    act(() => result.current.push('symptom'))
    expect(window.location.search).toBe('?mock=high&report=symptom')
    expect(depth()).toBe(2)
  })

  it('replace 는 기록을 쌓지 않고 깊이를 유지한다 — 보낸 뒤 완료 화면처럼 되돌아오면 안 되는 단계', () => {
    const { result, rerender } = renderHook(() => useModalParam('report'))
    act(() => result.current.push('start'))
    rerender()
    const pushState = vi.spyOn(window.history, 'pushState')

    act(() => result.current.replace('done'))
    expect(window.location.search).toBe('?mock=high&report=done')
    expect(depth()).toBe(1)
    expect(pushState).not.toHaveBeenCalled()
  })

  it('replace 의 remove 는 적은 쿼리만 같은 기록 항목에서 함께 지운다', () => {
    window.history.replaceState(null, '', '/?mock=high&region=1111051500&mock-auth=member')
    const { result, rerender } = renderHook(() => useModalParam('report'))
    act(() => result.current.push('health-consent'))
    rerender()

    act(() => result.current.replace('start', { remove: ['mock-auth'] }))
    expect(window.location.search).toBe('?mock=high&region=1111051500&report=start')
    expect(depth()).toBe(1)
  })

  it('close 는 쌓은 깊이만큼만 되돌린다', () => {
    const go = vi.spyOn(window.history, 'go').mockImplementation(() => {})
    const { result, rerender } = renderHook(() => useModalParam('report'))
    act(() => result.current.push('start'))
    rerender()
    act(() => result.current.push('symptom'))
    rerender()

    act(() => result.current.close())
    expect(go).toHaveBeenCalledWith(-2)
  })

  it('뒤로 가기로 한 단계 돌아온 뒤 닫으면 그 자리의 깊이만큼만 되돌린다 (홈보다 더 뒤로 가지 않는다)', () => {
    const go = vi.spyOn(window.history, 'go').mockImplementation(() => {})
    // 시작(깊이 1) → 증상(깊이 2) 에서 뒤로 가기를 눌러 시작 항목에 돌아온 상태
    window.history.replaceState({ sneezecastModalDepth: 1 }, '', '/?mock=high&report=start')
    const { result } = renderHook(() => useModalParam('report'))

    act(() => result.current.close())
    expect(go).toHaveBeenCalledWith(-1)
  })

  it('주소로 바로 들어와 쌓은 기록이 없으면 쿼리만 지운다', () => {
    window.history.replaceState(null, '', '/?mock=high&report=start')
    const go = vi.spyOn(window.history, 'go')
    const { result } = renderHook(() => useModalParam('report'))

    expect(result.current.value).toBe('start')
    act(() => result.current.close())
    expect(go).not.toHaveBeenCalled()
    expect(window.location.search).toBe('?mock=high')
  })

  it('back 은 쌓은 기록이 있으면 뒤로 가고, 없으면 대체 단계로 바꾼다', () => {
    const historyBack = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    window.history.replaceState(null, '', '/?report=symptom')
    const { result, rerender } = renderHook(() => useModalParam('report'))

    act(() => result.current.back('start'))
    expect(historyBack).not.toHaveBeenCalled()
    expect(window.location.search).toBe('?report=start')

    rerender()
    act(() => result.current.push('symptom'))
    rerender()
    act(() => result.current.back('start'))
    expect(historyBack).toHaveBeenCalledTimes(1)
  })

  it('다른 쿼리가 없으면 경로만 남긴다', () => {
    window.history.replaceState(null, '', '/?report=start')
    const { result } = renderHook(() => useModalParam('report'))

    act(() => result.current.close())
    expect(window.location.pathname + window.location.search).toBe('/')
  })
})
