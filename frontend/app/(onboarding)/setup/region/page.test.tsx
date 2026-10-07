import { isValidElement, type ReactElement } from 'react'

import { beforeEach, describe, expect, it, vi } from 'vitest'

import { RegionReselectScreen } from '@/features/onboarding/region-reselect-screen'
import { RegionScreen, type RegionScreenProps } from '@/features/onboarding/region-screen'
import type * as regionClient from '@/features/region/region-client'
import { findDistrict } from '@/features/region/region-client'
import { ApiError } from '@/lib/api/api-error'

import SetupRegionPage from './page'

// 테스트에는 요청이 없어 `cookies()` 를 부를 수 없다 — 출처는 목으로 둔다
vi.mock('@/lib/data-source.server', () => ({ readServerDataSource: () => Promise.resolve('mock') }))

// 실제 목 조회를 쓰되 폐지 코드 · 조회 실패를 흉내 낼 수 있게 감싼다
vi.mock('@/features/region/region-client', async (importOriginal) => {
  const actual = await importOriginal<typeof regionClient>()
  return { ...actual, findDistrict: vi.fn(actual.findDistrict) }
})

async function renderPage(params: Record<string, string | string[]>): Promise<ReactElement> {
  const element = await SetupRegionPage({ searchParams: Promise.resolve(params) })
  if (!isValidElement(element)) throw new Error('화면 요소가 아니다')
  return element
}

const propsOf = (element: ReactElement) => element.props as RegionScreenProps

describe('SetupRegionPage — 둘러보던 동네를 처음 선택으로 (#227)', () => {
  beforeEach(() => {
    // vi.fn(구현) 의 mockReset 은 처음 준 구현(실제 목 조회)으로 되돌린다
    vi.mocked(findDistrict).mockReset()
  })

  it('아는 동네면 확인한 행정동을 처음 선택으로 넘기고 카카오 표시도 함께 받는다', async () => {
    const element = await renderPage({ from: 'kakao', region: '11680640' })
    expect(element.type).toBe(RegionScreen)
    expect(propsOf(element)).toEqual({
      fromKakao: true,
      preset: { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' },
    })
  })

  it.each([
    ['없음', {}],
    ['형식이 틀림', { region: '../11680640' }],
    ['모르는 코드', { region: '99999999' }],
  ])('%s이면 넘기지 않아 빈 선택으로 시작한다', async (_, params) => {
    expect(propsOf(await renderPage(params)).preset).toBeNull()
  })

  it('폐지된 코드면 넘기지 않는다', async () => {
    vi.mocked(findDistrict).mockResolvedValueOnce({
      code: '11680640',
      name: '역삼1동',
      sigungu: '서울특별시 강남구',
      active: false,
    })
    expect(propsOf(await renderPage({ region: '11680640' })).preset).toBeNull()
  })

  it('조회가 실패하면(일시 장애) 화면을 오류로 바꾸지 않고 넘기지 않는다', async () => {
    vi.mocked(findDistrict).mockRejectedValueOnce(
      new ApiError({ status: 503, code: 'COMMON_503', message: '잠시 뒤' }),
    )
    const element = await renderPage({ region: '11680640' })
    expect(element.type).toBe(RegionScreen)
    expect(propsOf(element).preset).toBeNull()
  })

  it('동네 다시 고르기(?reselect=1)는 함께 실린 둘러보기 동네를 조회하지 않는다', async () => {
    const element = await renderPage({ reselect: '1', region: '11680640' })
    expect(element.type).toBe(RegionReselectScreen)
    expect(findDistrict).not.toHaveBeenCalled()
  })
})
