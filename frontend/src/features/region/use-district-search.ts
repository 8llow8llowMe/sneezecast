'use client'

import { useEffect, useState } from 'react'

import { searchDistricts } from './region-client'
import type { District } from './types'

/** 입력이 멈춘 뒤 검색하기까지 기다리는 시간 (ms). 글자마다 요청하지 않는다 */
export const SEARCH_DEBOUNCE_MS = 200

type Settled =
  { query: string; status: 'done'; results: District[] } | { query: string; status: 'error' }

export type DistrictSearch =
  /** 검색어가 비었다 */
  | { status: 'idle' }
  /** 지금 검색어의 결과를 기다린다. 앞 검색어의 결과가 있으면 깜빡이지 않게 그대로 보인다 */
  | { status: 'loading'; results: District[] }
  | { status: 'done'; results: District[] }
  | { status: 'error' }

/**
 * 행정동 검색. 입력이 `SEARCH_DEBOUNCE_MS` 동안 멈추면 찾는다.
 *
 * 검색어가 바뀌면 앞 요청의 응답은 버린다 — 늦게 온 이전 응답이 새 결과를 덮지 않게 한다.
 * 검색어는 찾는 데만 쓰고 어디에도 남기지 않는다.
 */
export function useDistrictSearch(query: string): DistrictSearch {
  const keyword = query.trim()
  const [settled, setSettled] = useState<Settled | null>(null)

  useEffect(() => {
    if (!keyword) return
    let stale = false
    const timer = setTimeout(() => {
      searchDistricts(keyword).then(
        (results) => {
          if (!stale) setSettled({ query: keyword, status: 'done', results })
        },
        () => {
          if (!stale) setSettled({ query: keyword, status: 'error' })
        },
      )
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      stale = true
      clearTimeout(timer)
    }
  }, [keyword])

  if (!keyword) return { status: 'idle' }
  if (settled?.query === keyword) return settled
  return { status: 'loading', results: settled?.status === 'done' ? settled.results : [] }
}
