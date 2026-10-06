import { beforeEach, describe, expect, it } from 'vitest'

import { loginWithEmail, logout, resetMockSession } from '@/features/auth/auth-client'

import {
  addInterestRegion,
  INTEREST_REGION_LIMIT,
  listInterestRegions,
  parseMockInterestScenario,
  removeInterestRegion,
  resetMockInterestRegions,
} from './interest-region-client'

const SEOGYO = { code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' }
const names = (result: { status: string; regions?: readonly { name: string }[] }) =>
  result.regions?.map((region) => region.name)

beforeEach(() => {
  resetMockSession()
  resetMockInterestRegions()
})

describe('관심 동네 클라이언트 (실데이터)', () => {
  it('BE 미정이라 요청하지 않고 불러올 수 없음 · 저장할 수 없음이다 — 서버에 저장한 것처럼 보이지 않는다', async () => {
    expect(await listInterestRegions('api')).toEqual({ status: 'unavailable' })
    expect(await addInterestRegion(SEOGYO, 'api')).toEqual({ status: 'unavailable' })
    expect(await removeInterestRegion(SEOGYO.code, 'api')).toEqual({ status: 'unavailable' })
    // 목 목록도 건드리지 않는다
    expect(names(await listInterestRegions('mock'))).toEqual(['망원1동', '삼청동'])
  })
})

describe('관심 동네 클라이언트 (목)', () => {
  it('처음은 시안과 같은 예시 2곳이다. 더하면 끝에 붙고 빼면 사라진다', async () => {
    expect(names(await listInterestRegions('mock'))).toEqual(['망원1동', '삼청동'])

    const added = await addInterestRegion(SEOGYO, 'mock')
    expect(added.status).toBe('ok')
    expect(names(added)).toEqual(['망원1동', '삼청동', '서교동'])

    expect(names(await removeInterestRegion('11440690', 'mock'))).toEqual(['삼청동', '서교동'])
    // 이미 없는 동네를 빼도 끝난 것으로 본다
    expect(names(await removeInterestRegion('11440690', 'mock'))).toEqual(['삼청동', '서교동'])
  })

  it('이미 있는 동네 · 상한(3곳)이면 거절하고 목록은 그대로다', async () => {
    expect(INTEREST_REGION_LIMIT).toBe(3)
    const duplicate = await addInterestRegion(
      { code: '11440690', name: '망원1동', sigungu: '서울특별시 마포구' },
      'mock',
    )
    expect(duplicate).toMatchObject({ status: 'duplicate' })
    expect(names(duplicate)).toEqual(['망원1동', '삼청동'])

    await addInterestRegion(SEOGYO, 'mock')
    const limit = await addInterestRegion(
      { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' },
      'mock',
    )
    expect(limit).toMatchObject({ status: 'limit' })
    expect(names(limit)).toHaveLength(3)
  })

  it('받은 목록을 고쳐도 목 서버 목록은 바뀌지 않는다', async () => {
    const listed = await listInterestRegions('mock')
    const first = listed.status === 'ready' ? listed.regions[0] : undefined
    if (!first) throw new Error('a region expected')
    first.name = '바뀐동'
    expect(names(await listInterestRegions('mock'))).toEqual(['망원1동', '삼청동'])
  })

  it('목 재현은 목록을 그 상태로 다시 시작한다 — empty · full · fail(추가 · 삭제 실패)', async () => {
    await addInterestRegion(SEOGYO, 'mock')
    expect(names(await listInterestRegions('mock', { scenario: 'empty' }))).toEqual([])
    expect(names(await listInterestRegions('mock', { scenario: 'full' }))).toEqual([
      '망원1동',
      '삼청동',
      '우동',
    ])

    expect(names(await listInterestRegions('mock', { scenario: 'fail' }))).toEqual([
      '망원1동',
      '삼청동',
    ])
    await expect(addInterestRegion(SEOGYO, 'mock')).rejects.toThrow()
    await expect(removeInterestRegion('11440690', 'mock')).rejects.toThrow()

    // 재현 쿼리 없이 다시 읽으면 목록은 그대로 두고 실패만 푼다
    expect(names(await listInterestRegions('mock'))).toEqual(['망원1동', '삼청동'])
    expect((await addInterestRegion(SEOGYO, 'mock')).status).toBe('ok')
  })

  it('목 재현 쿼리는 아는 값만 읽는다', () => {
    expect(parseMockInterestScenario('full')).toBe('full')
    expect(parseMockInterestScenario('FULL')).toBeNull()
    expect(parseMockInterestScenario(null)).toBeNull()
  })

  it('목 세션이 비회원이 되면 지운다 — 다음 로그인은 예시 목록부터다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    await addInterestRegion(SEOGYO, 'mock')
    await removeInterestRegion('11440690', 'mock')
    expect(names(await listInterestRegions('mock'))).toEqual(['삼청동', '서교동'])

    await logout('mock')
    expect(names(await listInterestRegions('mock'))).toEqual(['망원1동', '삼청동'])
  })
})
