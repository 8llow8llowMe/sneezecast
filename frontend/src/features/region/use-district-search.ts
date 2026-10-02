'use client'

import { useEffect, useState } from 'react'

import type { DataSource } from '@/lib/data-source'
import { useDataSource } from '@/lib/use-data-source'

import { searchDistricts } from './region-client'
import type { District } from './types'

/** 입력이 멈춘 뒤 검색하기까지 기다리는 시간 (ms). 글자마다 요청하지 않는다 */
export const SEARCH_DEBOUNCE_MS = 200

type Settled = { query: string; source: DataSource } & (
  { status: 'done'; results: District[] } | { status: 'error' }
)

export type DistrictSearch =
  /** 검색어가 비었다 */
  | { status: 'idle' }
  /** 지금 검색어의 결과를 기다린다. 앞 검색어의 결과가 있으면 깜빡이지 않게 그대로 보인다 */
  | { status: 'loading'; results: District[] }
  | { status: 'done'; results: District[] }
  | { status: 'error' }

/**
 * 행정동 검색. 입력이 `SEARCH_DEBOUNCE_MS` 동안 멈추면 지금 데이터 출처(`useDataSource`)로 찾는다.
 *
 * 검색어 · 출처가 바뀌면 앞 요청을 취소하고(`AbortController`) 그 응답은 버린다 — 늦게 온 이전 응답이 새 결과를 덮지 않게 한다.
 * 취소는 오류로 보이지 않는다. 검색어는 찾는 데만 쓰고 어디에도 남기지 않는다.
 */
export function useDistrictSearch(query: string): DistrictSearch {
  const keyword = query.trim()
  const source = useDataSource()
  const [settled, setSettled] = useState<Settled | null>(null)

  useEffect(() => {
    if (!keyword) return
    const controller = new AbortController()
    const timer = setTimeout(() => {
      searchDistricts(keyword, source, controller.signal).then(
        (results) => {
          if (!controller.signal.aborted) {
            setSettled({ query: keyword, source, status: 'done', results })
          }
        },
        () => {
          if (!controller.signal.aborted) setSettled({ query: keyword, source, status: 'error' })
        },
      )
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [keyword, source])

  if (!keyword) return { status: 'idle' }
  if (settled?.query === keyword && settled.source === source) {
    return settled.status === 'done'
      ? { status: 'done', results: settled.results }
      : { status: 'error' }
  }
  return { status: 'loading', results: settled?.status === 'done' ? settled.results : [] }
}
