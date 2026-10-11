import { isValidElement } from 'react'

import { afterEach, describe, expect, it, vi } from 'vitest'

import HomePage from './page'

// 테스트에는 요청이 없어 `cookies()` 를 부를 수 없다 — 출처는 테스트가 정한다(기본 목)
const dataSource = vi.hoisted((): { value: 'mock' | 'api' } => ({ value: 'mock' }))
vi.mock('@/lib/data-source.server', () => ({
  readServerDataSource: () => Promise.resolve(dataSource.value),
}))

type Props = {
  week: unknown
  regionName?: string
  regionCode?: string | null
  receivedAt?: unknown
}

async function renderPage(params: Record<string, string>): Promise<Props> {
  const element = await HomePage({ searchParams: Promise.resolve(params) })
  expect(isValidElement(element)).toBe(true)
  return element.props as Props
}

describe('HomePage', () => {
  afterEach(() => {
    vi.useRealTimers()
    dataSource.value = 'mock'
  })

  it('홈 자료를 받은 시각을 받은 시각(receivedAt)으로 넘긴다 — 오프라인 띠가 쓴다', async () => {
    vi.useFakeTimers({ now: new Date('2026-11-19T00:00:00Z'), toFake: ['Date'] })
    const props = await renderPage({ mock: 'normal' })

    expect(props.receivedAt).toBe('2026-11-19T00:00:00.000Z')
    expect(props.week).toMatchObject({ status: 'normal' })
  })

  it('실데이터면 목 주를 넘기지 않는다(화면이 집계를 읽는다) — `?mock=` 도 듣지 않는다', async () => {
    dataSource.value = 'api'
    const props = await renderPage({ mock: 'high' })

    expect(props.week).toBeNull()
    expect(props.receivedAt).toBeUndefined()
    expect(props.regionName).toBe('○○동')
    expect(props.regionCode).toBeNull()
  })

  it('실데이터의 ?region= 은 모양(숫자 8자리)만 보고 넘긴다 — 이름 · 있는 동네인지는 집계 응답이 정한다', async () => {
    dataSource.value = 'api'

    // 행정동 목에도 없는 코드지만 넘긴다(행정동 API 를 부르지 않는다 — 그 일시 장애를 모르는 동네로 삼키지 않게)
    expect((await renderPage({ region: '99999999' })).regionCode).toBe('99999999')
    expect((await renderPage({ region: '1168064' })).regionCode).toBeNull()
  })
})
