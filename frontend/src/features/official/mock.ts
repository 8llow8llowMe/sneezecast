import { type OfficialLatestResponse, toOfficialReport } from './official-map'
import type { OfficialReport } from './types'

const SENTINEL = {
  code: 'KDCA_SENTINEL',
  name: '질병관리청 표본감시',
  description: '감염병포털 표본감시 통계에서 받은 자료',
}
const NATION = { code: 'NATION', name: '전국', description: '전국 단위 값. 지역 코드는 00' }
const CASE_COUNT = { code: 'CASE_COUNT', name: '발생 수', description: '신고된 발생 건수' }
const ILI_PER_1000 = {
  code: 'ILI_PER_1000',
  name: '외래 1,000명당 의사환자 분율',
  description: '외래 환자 1,000명당 인플루엔자 의사환자 수',
}
const ALL = { code: 'ALL', name: '전체', description: '연령 구분 없는 전체 값. 기본값' }

/** 계약 예시와 같은 기준 주(2026년 38주) · 적재 시각 */
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

function caseCount(diseaseKey: string, diseaseName: string, diseaseGroup: string, value: number) {
  return {
    diseaseKey,
    diseaseName,
    diseaseGroup,
    metric: CASE_COUNT,
    ...WEEK_38,
    values: [{ ageGroup: ALL, value }],
  }
}

function age(code: string, name: string, value: number | null) {
  return { ageGroup: { code, name, description: name }, value }
}

/**
 * `?mock=published` 의 응답. 계약 응답 예시(docs/api-contract-draft.md "공식 정보" — 급성호흡기 호흡기세포융합바이러스 152건,
 * 2026년 38주)를 표본감시 세 프로그램으로 늘린 **예시 값**이다 — 실제 발표가 아니다. 65세 이상 분율은 원천 빈 칸(`null`) 예시다.
 */
const PUBLISHED_RESPONSE: OfficialLatestResponse = {
  programs: [
    {
      program: {
        code: 'INFLUENZA_ILI',
        name: '인플루엔자 의사환자',
        description: '인플루엔자 의사환자 분율. 연령대별 행으로 온다',
      },
      source: SENTINEL,
      sourceUrl: 'https://dportal.kdca.go.kr/pot/is/st/influ.do',
      diseases: [
        {
          diseaseKey: 'ILI',
          diseaseName: '인플루엔자 의사환자 분율',
          diseaseGroup: null,
          metric: ILI_PER_1000,
          ...WEEK_38,
          values: [
            age('AGE_0', '0세', 3.1),
            age('AGE_1_6', '1~6세', 8.4),
            age('AGE_7_12', '7~12세', 12.3),
            age('AGE_13_18', '13~18세', 10.2),
            age('AGE_19_49', '19~49세', 6.5),
            age('AGE_50_64', '50~64세', 4),
            age('AGE_65_PLUS', '65세 이상', null),
          ],
        },
      ],
    },
    {
      program: {
        code: 'ARI',
        name: '급성호흡기감염증',
        description: '급성호흡기감염증 병원체별 신고 수',
      },
      source: SENTINEL,
      sourceUrl: 'https://dportal.kdca.go.kr/pot/is/st/ari.do',
      diseases: [
        caseCount('ND0001', '인플루엔자 바이러스', '인를루엔자', 64),
        caseCount('ND0701', '아데노바이러스', '바이러스', 48),
        caseCount('ND0704', '호흡기세포융합바이러스', '바이러스', 152),
        caseCount('ND0705', '리노바이러스', '바이러스', 101),
        caseCount('ND0708', '마이코플라즈마균', '세균', 35),
        caseCount('TOTAL', '계', '계', 400),
      ],
    },
    {
      program: { code: 'ENTERIC', name: '장관감염증', description: '장관감염증 병원체별 신고 수' },
      source: SENTINEL,
      sourceUrl: 'https://dportal.kdca.go.kr/pot/is/st/gstrnftn.do',
      diseases: [
        caseCount('ND0601', '살모넬라균', '세균', 35),
        caseCount('ND0606', '캄필로박터균', '세균', 40),
        caseCount('ND0612', '그룹 A형 로타바이러스', '바이러스', 20),
        caseCount('ND0615', '노로바이러스', '바이러스', 120),
        caseCount('TOTAL', '계', '계', 215),
      ],
    },
  ],
}

/**
 * 공식 정보 목 데이터(목데이터 출처, docs/conventions.md "데이터 출처"). `published` 는 위 예시 응답을 실데이터와 **같은 매핑**
 * (`toOfficialReport`)으로 옮긴 것이고, `empty` 는 자료가 없는 응답(`{ programs: [] }`)과 같다.
 * 원문 주소는 백엔드가 싣는 표본감시 화면 주소(backend `OfficialSourceLink`)와 같다.
 */
export const OFFICIAL_MOCKS = {
  published: toOfficialReport(PUBLISHED_RESPONSE),
  empty: { status: 'empty', reason: 'none' },
} as const satisfies Record<string, OfficialReport>

export type OfficialMockKey = keyof typeof OFFICIAL_MOCKS

function isMockKey(value: string): value is OfficialMockKey {
  return Object.hasOwn(OFFICIAL_MOCKS, value)
}

/**
 * `?mock=` 값으로 목 데이터를 고른다(`published` · `empty`). 없거나 모르는 값이면 `empty`(받은 자료 없음)다 —
 * 수치를 지어내 보이지 않는 쪽이 기본값이어야 한다 (홈 `pickHomeMock` 과 같다). 목데이터 출처에서만 부른다.
 */
export function pickOfficialMock(value: string | string[] | undefined): OfficialReport {
  const key = Array.isArray(value) ? value[0] : value
  return key !== undefined && isMockKey(key) ? OFFICIAL_MOCKS[key] : OFFICIAL_MOCKS.empty
}
