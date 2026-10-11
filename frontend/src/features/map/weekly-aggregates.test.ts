import { afterEach, describe, expect, it, vi } from 'vitest'

import type { DistrictAggregateResponse } from '@/features/home/weekly-aggregate'
import { ApiError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import { fetchMapWeek } from './weekly-aggregates'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

const request = vi.mocked(apiRequest)

const code = (value: string) => ({ code: value, name: value, description: value })

const SEOGYO = '11440660'
const HAPJEONG = '11440680'
const YEOKSAM1 = '11680640'

function district(
  districtCode: string,
  name: string,
  fields: Partial<DistrictAggregateResponse> = {},
): DistrictAggregateResponse {
  return {
    code: districtCode,
    name,
    sigungu: '서울특별시 마포구',
    active: true,
    level: code('INSUFFICIENT'),
    participantCount: 0,
    ...fields,
  }
}

const HIGH: Partial<DistrictAggregateResponse> = {
  level: code('HIGH'),
  participantCount: 128,
  calculatedAt: '2025-11-20T00:00:00Z',
  symptomaticCount: 23,
  symptomRate: 18,
  baselineRate: 9.4,
  groups: [{ group: code('RESPIRATORY'), rate: 14.1, trend: code('HIGH') }],
}

function response(districts: DistrictAggregateResponse[]) {
  return {
    isoWeek: '2025-W47',
    finalized: false,
    publicThreshold: 100,
    slightDeltaPp: 3,
    highDeltaPp: 8,
    districts,
  }
}

/** 2025-11-20 10:00 KST */
const NOW = () => new Date('2025-11-20T01:00:00Z')

afterEach(() => {
  vi.clearAllMocks()
})

describe('fetchMapWeek', () => {
  it('처음 고른 동네의 시군구(코드 앞 5자리)를 그 동네를 include 로 담아 Authorization 없이 읽는다', async () => {
    request.mockResolvedValueOnce(response([district(SEOGYO, '서교동', HIGH)]))

    await fetchMapWeek(SEOGYO, NOW)

    expect(request).toHaveBeenCalledWith('/api/v1/districts/weekly-aggregates', {
      auth: false,
      query: { sigunguCode: '11440', include: SEOGYO },
    })
  })

  it('동네마다 홈과 같은 모양으로 옮기고, 처음 고른 동네의 코드 · 이름 · 응답 주를 담는다', async () => {
    request.mockResolvedValueOnce(
      response([
        district(SEOGYO, '서교동', {
          ...HIGH,
          publishedAdvisory: {
            isoWeek: '2025-W46',
            title: '호흡기 증상 보고가 늘었어요',
            publishedAt: '2025-11-17T15:30:00Z',
          },
        }),
        district(HAPJEONG, '합정동', { participantCount: 41 }),
      ]),
    )

    const map = await fetchMapWeek(HAPJEONG, NOW)

    expect(map).toMatchObject({ weekLabel: '11월 3주', mineCode: HAPJEONG, mineName: '합정동' })
    // 응답 순서 그대로다(include 가 시군구 안이면 제자리)
    expect(map?.districts.map((item) => item.code)).toEqual([SEOGYO, HAPJEONG])
    const [seogyo, hapjeong] = map?.districts ?? []
    expect(seogyo?.week).toMatchObject({ regionName: '서교동', status: 'high', symptomRate: 18 })
    // 발행 안내는 그 동네 · 안내 근거 주(요청 주 W47 이 아니라 W46)의 동네 안내로 잇는다(#256)
    expect(seogyo?.week.notice).toEqual({
      publishedLabel: '11월 18일 발행',
      title: '호흡기 증상 보고가 늘었어요',
      href: `/notice/${SEOGYO}/2025-W46`,
    })
    // 자료 부족 동네는 수치 필드가 없다
    expect(hapjeong?.week).toMatchObject({ status: 'insufficient', participants: 41 })
    expect(hapjeong?.week).not.toHaveProperty('symptomRate')
  })

  it('모든 동네의 참여가 0 이면 이번 주 동네 자료가 없음(빈 목록)이고, 처음 고른 동네 이름은 남는다', async () => {
    request.mockResolvedValueOnce(
      response([district(YEOKSAM1, '역삼1동'), district(SEOGYO, '서교동')]),
    )

    await expect(fetchMapWeek(YEOKSAM1, NOW)).resolves.toEqual({
      weekLabel: '11월 3주',
      mineCode: YEOKSAM1,
      mineName: '역삼1동',
      districts: [],
    })
  })

  it('형식이 틀린 코드는 요청 없이 null 이다', async () => {
    await expect(fetchMapWeek('1144066', NOW)).resolves.toBeNull()
    expect(request).not.toHaveBeenCalled()
  })

  it('없는 include(AGGREGATE_001) · 폐지된 처음 고른 동네(active false)는 null 이다', async () => {
    request.mockRejectedValueOnce(
      new ApiError({ status: 404, code: 'AGGREGATE_001', message: '없는 행정동' }),
    )
    await expect(fetchMapWeek(SEOGYO, NOW)).resolves.toBeNull()

    request.mockResolvedValueOnce(
      response([district(SEOGYO, '서교동', { active: false }), district(HAPJEONG, '합정동')]),
    )
    await expect(fetchMapWeek(SEOGYO, NOW)).resolves.toBeNull()
  })

  it('그 밖의 실패 · 계약과 다른 응답(include 가 빠짐 · 모르는 단계 · 읽을 수 없는 주)은 거부한다', async () => {
    request.mockRejectedValueOnce(
      new ApiError({ status: 0, code: 'UNAVAILABLE', message: '일시 장애' }),
    )
    await expect(fetchMapWeek(SEOGYO, NOW)).rejects.toBeInstanceOf(ApiError)

    request.mockResolvedValueOnce(response([district(HAPJEONG, '합정동', HIGH)]))
    await expect(fetchMapWeek(SEOGYO, NOW)).rejects.toThrow(/include/)

    request.mockResolvedValueOnce(
      response([district(SEOGYO, '서교동', { participantCount: 3, level: code('UNKNOWN') })]),
    )
    await expect(fetchMapWeek(SEOGYO, NOW)).rejects.toThrow()

    request.mockResolvedValueOnce({ ...response([district(SEOGYO, '서교동')]), isoWeek: 'nope' })
    await expect(fetchMapWeek(SEOGYO, NOW)).rejects.toThrow()
  })
})
