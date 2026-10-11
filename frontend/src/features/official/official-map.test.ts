import { describe, expect, it } from 'vitest'

import {
  formatPeriod,
  formatValue,
  type OfficialLatestResponse,
  OfficialResponseError,
  toOfficialReport,
  TOP_PATHOGEN_COUNT,
} from './official-map'
import type { OfficialProgramView, OfficialReport } from './types'

type Program = OfficialLatestResponse['programs'][number]
type Disease = Program['diseases'][number]

const SENTINEL = { code: 'KDCA_SENTINEL', name: '질병관리청 표본감시' }
const NATION = { code: 'NATION', name: '전국' }
const CASE_COUNT = { code: 'CASE_COUNT', name: '발생 수' }
const ILI_PER_1000 = { code: 'ILI_PER_1000', name: '외래 1,000명당 의사환자 분율' }
const ALL = { code: 'ALL', name: '전체' }

const WEEK_38 = {
  regionLevel: NATION,
  regionCode: '00',
  regionName: '전국',
  periodYear: 2026,
  periodWeek: 38,
  periodStart: '2026-09-13',
  periodEnd: '2026-09-19',
  syncedAt: '2026-09-24T21:00:04Z',
}

const WEEK_37 = {
  ...WEEK_38,
  periodWeek: 37,
  periodStart: '2026-09-06',
  periodEnd: '2026-09-12',
  syncedAt: '2026-09-17T21:00:00Z',
}

function pathogen(
  diseaseKey: string,
  diseaseName: string,
  value: number | null,
  overrides: Partial<Disease> = {},
): Disease {
  return {
    diseaseKey,
    diseaseName,
    diseaseGroup: '바이러스',
    metric: CASE_COUNT,
    ...WEEK_38,
    values: [{ ageGroup: ALL, value }],
    ...overrides,
  }
}

function program(code: string, diseases: Disease[], sourceUrl: string | null = null): Program {
  return {
    program: { code, name: `${code} 이름` },
    source: SENTINEL,
    sourceUrl,
    diseases,
  }
}

function programs(report: OfficialReport): readonly OfficialProgramView[] {
  if (report.status !== 'published') throw new Error('published 가 아니다')
  return report.programs
}

describe('toOfficialReport 응답 모양', () => {
  it('자료가 없으면(programs: []) 받은 자료 없음이다', () => {
    expect(toOfficialReport({ programs: [] })).toEqual({ status: 'empty', reason: 'none' })
  })

  it('programs 가 배열이 아니면 계약이 어긋난 것이라 거부한다 — 빈 화면으로 숨기지 않는다', () => {
    expect(() => toOfficialReport({} as OfficialLatestResponse)).toThrow(OfficialResponseError)
    expect(() => toOfficialReport(null as unknown as OfficialLatestResponse)).toThrow(
      OfficialResponseError,
    )
  })

  it('전수신고 · 모르는 프로그램은 보이지 않는다. 그것뿐이면 받은 자료 없음이다', () => {
    const notifiable = program('NOTIFIABLE', [pathogen('콜레라', '콜레라', 1)])
    expect(toOfficialReport({ programs: [notifiable, program('NEW', [])] })).toEqual({
      status: 'empty',
      reason: 'unshown',
    })

    const ari = program('ARI', [pathogen('TOTAL', '계', 10)])
    expect(programs(toOfficialReport({ programs: [notifiable, ari] })).map((p) => p.code)).toEqual([
      'ARI',
    ])
  })

  it('전국이 아닌 계열은 빼고, 전국 계열이 없는 프로그램은 보이지 않는다', () => {
    const sido = {
      regionLevel: { code: 'SIDO', name: '시도' },
      regionCode: '11',
      regionName: '서울',
    }
    const report = toOfficialReport({
      programs: [
        program('ARI', [pathogen('TOTAL', '계', 10), pathogen('TOTAL', '계', 3, sido)]),
        program('ENTERIC', [pathogen('TOTAL', '계', 5, sido)]),
      ],
    })
    const [ari, ...rest] = programs(report)
    expect(rest).toEqual([])
    expect(ari?.rows).toEqual([{ label: '합계', value: '10건' }])
    expect(ari?.regionLabel).toBe('전국')
  })
})

describe('toOfficialReport 인플루엔자 의사환자 분율', () => {
  const ili = program(
    'INFLUENZA_ILI',
    [
      {
        diseaseKey: 'ILI',
        diseaseName: '인플루엔자 의사환자 분율',
        diseaseGroup: null,
        metric: ILI_PER_1000,
        ...WEEK_38,
        values: [
          { ageGroup: { code: 'AGE_0', name: '0세' }, value: 3.1 },
          { ageGroup: { code: 'AGE_7_12', name: '7~12세' }, value: 12.345 },
          { ageGroup: { code: 'AGE_65_PLUS', name: '65세 이상' }, value: null },
        ],
      },
    ],
    'https://dportal.kdca.go.kr/pot/is/st/influ.do',
  )

  it('연령 전체 값이 없어 연령대별 값을 응답 순서대로 보이고, 빈 칸은 0 이 아니라 값 없음이다', () => {
    const [view] = programs(toOfficialReport({ programs: [ili] }))
    expect(view).toEqual({
      code: 'INFLUENZA_ILI',
      name: 'INFLUENZA_ILI 이름',
      sourceName: '질병관리청 표본감시',
      regionLabel: '전국',
      weekLabel: '2026년 38주',
      periodLabel: '9월 13일~19일',
      // 21:00:04Z 는 한국 시각 다음 날 06:00 — 발표일이 아니라 우리가 받은 시각이다
      syncedLabel: '9월 25일 06:00',
      metricLabel: '외래 1,000명당 의사환자 분율',
      rows: [
        { label: '0세', value: '3.1' },
        { label: '7~12세', value: '12.35' },
        { label: '65세 이상', value: '값 없음' },
      ],
      note: null,
      sourceUrl: 'https://dportal.kdca.go.kr/pot/is/st/influ.do',
    })
  })
})

describe('toOfficialReport 병원체별 신고 수 (급성호흡기 · 장관)', () => {
  it(`합계와 값이 큰 병원체 ${TOP_PATHOGEN_COUNT}개를 보인다 — 빈 칸은 뒤, 같은 값은 응답 순서`, () => {
    const ari = program(
      'ARI',
      [
        pathogen('ND0001', '인플루엔자 바이러스', null),
        pathogen('ND0701', '아데노바이러스', 48),
        pathogen('ND0702', '사람 보카바이러스', 101),
        pathogen('ND0704', '호흡기세포융합바이러스', 1520),
        pathogen('ND0705', '리노바이러스', 101),
        pathogen('TOTAL', '계', 1770),
      ],
      'https://dportal.kdca.go.kr/pot/is/st/ari.do',
    )
    const [view] = programs(toOfficialReport({ programs: [ari] }))

    expect(view?.rows).toEqual([
      { label: '합계', value: '1,770건' },
      { label: '호흡기세포융합바이러스', value: '1,520건' },
      { label: '사람 보카바이러스', value: '101건' },
      { label: '리노바이러스', value: '101건' },
    ])
    expect(view?.metricLabel).toBe('발생 수')
    expect(view?.note).toBe(
      '합계와 신고 수가 많은 병원체 3개만 보여요. 나머지는 원문에서 볼 수 있어요.',
    )
  })

  it('원문 주소가 없으면 원문을 가리키지 않고, 합계가 없으면 병원체만 고른 것을 알린다', () => {
    const enteric = program('ENTERIC', [
      pathogen('ND0601', '살모넬라균', 1),
      pathogen('ND0602', '장염비브리오균', 2),
      pathogen('ND0603', '장독소성대장균(ETEC)', 3),
      pathogen('ND0604', '장침습성대장균(EIEC)', 4),
    ])
    const [view] = programs(toOfficialReport({ programs: [enteric] }))

    expect(view?.rows.map((row) => row.label)).toEqual([
      '장침습성대장균(EIEC)',
      '장독소성대장균(ETEC)',
      '장염비브리오균',
    ])
    expect(view?.note).toBe('신고 수가 많은 병원체 3개만 보여요.')
    expect(view?.sourceUrl).toBeNull()
  })

  it('병원체가 고를 개수 이하면 모두 보이고 고른 안내가 없다. 빈 칸만 있어도 값 없음으로 보인다', () => {
    const ari = program('ARI', [
      pathogen('ND0701', '아데노바이러스', null),
      pathogen('TOTAL', '계', 0),
    ])
    const [view] = programs(toOfficialReport({ programs: [ari] }))

    expect(view?.rows).toEqual([
      { label: '합계', value: '0건' },
      { label: '아데노바이러스', value: '값 없음' },
    ])
    expect(view?.note).toBeNull()
  })

  it('합계가 있는 가장 최근 주를 기준 주로 고르고, 그 주가 일부만 집계됐으면 알린다 — 다른 주의 값을 나란히 두지 않는다', () => {
    const ari = program('ARI', [
      pathogen('ND0701', '아데노바이러스', 999, WEEK_37),
      pathogen('ND0704', '호흡기세포융합바이러스', 152),
      pathogen('TOTAL', '계', 900, WEEK_37),
    ])
    const [view] = programs(toOfficialReport({ programs: [ari] }))

    expect(view?.weekLabel).toBe('2026년 37주')
    expect(view?.periodLabel).toBe('9월 6일~12일')
    expect(view?.rows).toEqual([
      { label: '합계', value: '900건' },
      { label: '아데노바이러스', value: '999건' },
    ])
    expect(view?.syncedLabel).toBe('9월 18일 06:00')
    expect(view?.note).toBe('이 주는 일부만 집계됐어요.')
  })

  it('합계가 없으면 가장 최근 주이고, 연령대 하나도 한 계열로 센다', () => {
    const ili = (week: typeof WEEK_38, ages: string[]): Disease => ({
      ...pathogen('ILI', '인플루엔자 의사환자 분율', null, week),
      metric: ILI_PER_1000,
      values: ages.map((name) => ({ ageGroup: { code: name, name }, value: 1 })),
    })
    const report = toOfficialReport({
      programs: [
        program(
          'INFLUENZA_ILI',
          [ili(WEEK_38, ['0세', '1~6세']), ili(WEEK_37, ['65세 이상'])],
          'https://dportal.kdca.go.kr/pot/is/st/influ.do',
        ),
      ],
    })
    const [view] = programs(report)

    expect(view?.weekLabel).toBe('2026년 38주')
    expect(view?.rows.map((row) => row.label)).toEqual(['0세', '1~6세'])
    expect(view?.note).toBe('이 주는 일부만 집계됐어요. 나머지는 원문에서 볼 수 있어요.')
  })

  it('받은 시각은 고른 줄 중 가장 늦은 것이고, 읽을 수 없으면 비운다', () => {
    const late = pathogen('ND0704', '호흡기세포융합바이러스', 152, {
      syncedAt: '2026-09-25T03:30:00Z',
    })
    const [view] = programs(
      toOfficialReport({ programs: [program('ARI', [pathogen('TOTAL', '계', 200), late])] }),
    )
    expect(view?.syncedLabel).toBe('9월 25일 12:30')

    const unreadable = pathogen('TOTAL', '계', 200, { syncedAt: 'not-a-date' })
    const [blank] = programs(toOfficialReport({ programs: [program('ARI', [unreadable])] }))
    expect(blank?.syncedLabel).toBeNull()
  })

  it('연령 전체 값이 없는 계열은 줄로 고르지 않고, 남는 줄이 없으면 묶음을 보이지 않는다', () => {
    const noAll = pathogen('ND0701', '아데노바이러스', 1, { values: [] })
    expect(toOfficialReport({ programs: [program('ARI', [noAll])] })).toEqual({
      status: 'empty',
      reason: 'unshown',
    })
  })
})

describe('formatValue · formatPeriod', () => {
  it('발생 수는 건을 붙이고 천 단위 쉼표를 넣는다. 그 밖 지표는 숫자만이다', () => {
    expect(formatValue(1234, 'CASE_COUNT')).toBe('1,234건')
    expect(formatValue(12.3, 'ILI_PER_1000')).toBe('12.3')
    expect(formatValue(null, 'CASE_COUNT')).toBe('값 없음')
    expect(formatValue(Number.NaN, 'CASE_COUNT')).toBe('값 없음')
  })

  it('기간은 같은 달이면 끝 날만, 달이 바뀌면 끝 달까지 적고, 읽을 수 없으면 null 이다', () => {
    expect(formatPeriod('2026-09-13', '2026-09-19')).toBe('9월 13일~19일')
    expect(formatPeriod('2026-09-27', '2026-10-03')).toBe('9월 27일~10월 3일')
    expect(formatPeriod('2026-12-27', '2027-01-02')).toBe('12월 27일~1월 2일')
    expect(formatPeriod('2026-09-13', '')).toBeNull()
  })
})
