// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type * as regionClient from './region-client'
import { searchDistricts } from './region-client'
import { SEARCH_DEBOUNCE_MS, useDistrictSearch } from './use-district-search'

vi.mock('./region-client', async (importOriginal) => {
  const actual = await importOriginal<typeof regionClient>()
  return { ...actual, searchDistricts: vi.fn(actual.searchDistricts) }
})

describe('useDistrictSearch', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(searchDistricts).mockClear()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('빠르게 이어 친 검색어는 입력이 멈춘 뒤 마지막 것으로 한 번만 찾는다', async () => {
    const { result, rerender } = renderHook(({ query }) => useDistrictSearch(query), {
      initialProps: { query: '역' },
    })
    rerender({ query: '역삼' })
    rerender({ query: '역삼1' })

    act(() => {
      vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 1)
    })
    expect(searchDistricts).not.toHaveBeenCalled()
    expect(result.current.status).toBe('loading')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(searchDistricts).toHaveBeenCalledTimes(1)
    expect(searchDistricts).toHaveBeenCalledWith('역삼1')
    expect(result.current).toMatchObject({
      status: 'done',
      results: [{ code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }],
    })
  })

  it('검색어가 비면 찾지 않는다', () => {
    const { result } = renderHook(() => useDistrictSearch('  '))
    act(() => {
      vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS * 2)
    })
    expect(searchDistricts).not.toHaveBeenCalled()
    expect(result.current).toEqual({ status: 'idle' })
  })
})
