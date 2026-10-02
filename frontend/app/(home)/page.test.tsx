import { isValidElement } from 'react'

import { afterEach, describe, expect, it, vi } from 'vitest'

import HomePage from './page'

// 테스트에는 요청이 없어 `cookies()` 를 부를 수 없다 — 출처는 목으로 둔다
vi.mock('@/lib/data-source.server', () => ({ readServerDataSource: () => Promise.resolve('mock') }))

describe('HomePage', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('홈 자료를 받은 시각을 받은 시각(receivedAt)으로 넘긴다 — 오프라인 띠가 쓴다', async () => {
    vi.useFakeTimers({ now: new Date('2026-11-19T00:00:00Z'), toFake: ['Date'] })
    const element = await HomePage({ searchParams: Promise.resolve({ mock: 'normal' }) })

    expect(isValidElement(element)).toBe(true)
    expect((element.props as { receivedAt?: unknown }).receivedAt).toBe('2026-11-19T00:00:00.000Z')
  })
})
