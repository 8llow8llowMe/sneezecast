import { afterEach, describe, expect, it, vi } from 'vitest'

import { unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'

import { OFFICIAL_MOCKS } from './mock'
import { getLatestOfficial, OFFICIAL_LATEST_PATH } from './official-client'
import { OfficialResponseError } from './official-map'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

const request = vi.mocked(apiRequest)

const ARI_RESPONSE = {
  programs: [
    {
      program: { code: 'ARI', name: '급성호흡기감염증' },
      source: { code: 'KDCA_SENTINEL', name: '질병관리청 표본감시' },
      sourceUrl: 'https://dportal.kdca.go.kr/pot/is/st/ari.do',
      diseases: [
        {
          diseaseKey: 'ND0704',
          diseaseName: '호흡기세포융합바이러스',
          diseaseGroup: '바이러스',
          metric: { code: 'CASE_COUNT', name: '발생 수' },
          regionLevel: { code: 'NATION', name: '전국' },
          regionCode: '00',
          regionName: '전국',
          periodYear: 2026,
          periodWeek: 38,
          periodStart: '2026-09-13',
          periodEnd: '2026-09-19',
          syncedAt: '2026-09-24T21:00:04Z',
          values: [{ ageGroup: { code: 'ALL', name: '전체' }, value: 152 }],
        },
      ],
    },
  ],
}

describe('getLatestOfficial', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('실데이터는 공식 정보 API 를 토큰 없이 부르고 화면 모델로 옮긴다 — 주소의 ?mock= 은 듣지 않는다', async () => {
    request.mockResolvedValueOnce(ARI_RESPONSE)

    const report = await getLatestOfficial('api', 'empty')

    expect(request).toHaveBeenCalledExactlyOnceWith(OFFICIAL_LATEST_PATH, { auth: false })
    expect(report.status).toBe('published')
    expect(report.status === 'published' && report.programs[0]?.rows).toEqual([
      { label: '호흡기세포융합바이러스', value: '152건' },
    ])
  })

  it('실데이터에서 자료가 없으면(programs: []) 받은 자료 없음이다 — 목 예시를 대신 보이지 않는다', async () => {
    request.mockResolvedValueOnce({ programs: [] })
    await expect(getLatestOfficial('api', 'published')).resolves.toEqual({
      status: 'empty',
      reason: 'none',
    })
  })

  it('실데이터 실패 · 어긋난 응답은 거부한다 — 페이지의 오류 경계가 받는다', async () => {
    const failure = unavailableError('timeout', 0)
    request.mockRejectedValueOnce(failure)
    await expect(getLatestOfficial('api')).rejects.toBe(failure)

    request.mockResolvedValueOnce({})
    await expect(getLatestOfficial('api')).rejects.toBeInstanceOf(OfficialResponseError)
  })

  it('목데이터는 요청 없이 ?mock= 으로 고르고, 기본은 받은 자료 없음이다', async () => {
    await expect(getLatestOfficial('mock', 'published')).resolves.toBe(OFFICIAL_MOCKS.published)
    await expect(getLatestOfficial('mock')).resolves.toBe(OFFICIAL_MOCKS.empty)
    expect(request).not.toHaveBeenCalled()
  })
})
