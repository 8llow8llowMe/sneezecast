import { isValidElement } from 'react'

import { describe, expect, it, vi } from 'vitest'

import type { MapWeek } from '@/features/map/types'

import MapPage from './page'

// 테스트에는 요청이 없어 `cookies()` 를 부를 수 없다 — 출처는 목으로 둔다
vi.mock('@/lib/data-source.server', () => ({ readServerDataSource: () => Promise.resolve('mock') }))

type Props = { map: MapWeek; regionCode: string | null }

async function renderPage(params: Record<string, string>): Promise<Props> {
  const element = await MapPage({ searchParams: Promise.resolve(params) })
  expect(isValidElement(element)).toBe(true)
  return element.props as Props
}

describe('MapPage', () => {
  it('찾아서 고른 동네(?region=)가 지도 목 자료에 없어도 처음 고른 동네로 두고 자료 부족으로 그린다', async () => {
    // 역삼1동은 행정동 목에는 있고 지도 목 자료(○○동 · 마포구 네 동)에는 없다
    const { map, regionCode } = await renderPage({ region: '11680640' })

    expect(regionCode).toBe('11680640')
    expect(map.mineCode).toBe('11680640')
    expect(map.mineName).toBe('역삼1동')
    const mine = map.districts.find((district) => district.code === '11680640')
    expect(mine?.week.regionName).toBe('역삼1동')
    expect(mine?.week.status).toBe('insufficient')
    expect(mine?.week).not.toHaveProperty('symptomRate')
  })

  it('모르는 코드는 버리고 목 예시 동네로 그린다 — 메뉴 링크에 남기지 않는다', async () => {
    const { map, regionCode } = await renderPage({ region: '99999999' })

    expect(regionCode).toBeNull()
    expect(map.mineName).toBe('○○동')
    expect(map.districts.some((district) => district.code === '99999999')).toBe(false)
  })
})
