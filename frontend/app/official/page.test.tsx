import { isValidElement } from 'react'

import { afterEach, describe, expect, it, vi } from 'vitest'

import { OFFICIAL_MOCKS } from '@/features/official/mock'
import { OFFICIAL_LATEST_PATH } from '@/features/official/official-client'
import type { OfficialReport } from '@/features/official/types'
import { apiRequest } from '@/lib/api/client'
import type { DataSource } from '@/lib/data-source'

import OfficialPage from './page'

// 테스트에는 요청이 없어 `cookies()` 를 부를 수 없다 — 출처는 테스트마다 정한다
const dataSource = vi.hoisted((): { value: DataSource } => ({ value: 'mock' }))
vi.mock('@/lib/data-source.server', () => ({
  readServerDataSource: () => Promise.resolve(dataSource.value),
}))
vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

const request = vi.mocked(apiRequest)

type Props = { official: OfficialReport; regionName: string; regionCode: string | null }

async function renderPage(params: Record<string, string>): Promise<Props> {
  const element = await OfficialPage({ searchParams: Promise.resolve(params) })
  expect(isValidElement(element)).toBe(true)
  return element.props as Props
}

describe('OfficialPage', () => {
  afterEach(() => {
    dataSource.value = 'mock'
    vi.clearAllMocks()
  })

  it('목데이터는 ?mock= 으로 고르고 요청하지 않는다', async () => {
    expect((await renderPage({ mock: 'published' })).official).toBe(OFFICIAL_MOCKS.published)
    expect((await renderPage({})).official).toBe(OFFICIAL_MOCKS.empty)
    expect(request).not.toHaveBeenCalled()
  })

  it('실데이터는 공식 정보 API 로 그리고 ?mock= 을 듣지 않는다', async () => {
    dataSource.value = 'api'
    request.mockResolvedValueOnce({ programs: [] })

    const { official, regionCode } = await renderPage({ mock: 'published' })

    expect(official).toEqual({ status: 'empty', reason: 'none' })
    expect(regionCode).toBeNull()
    expect(request).toHaveBeenCalledExactlyOnceWith(OFFICIAL_LATEST_PATH, { auth: false })
  })

  it('실데이터를 받지 못하면 거부해 오류 경계로 보낸다 — 예시 값을 대신 그리지 않는다', async () => {
    dataSource.value = 'api'
    const failure = new Error('gateway down')
    request.mockRejectedValueOnce(failure)

    await expect(OfficialPage({ searchParams: Promise.resolve({}) })).rejects.toBe(failure)
  })
})
