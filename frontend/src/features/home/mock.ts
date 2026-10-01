import type { RegionStatus } from '@/lib/status'

import type { HomeWeekly, OfficialSummary } from './types'

/**
 * 홈 목 데이터 4상태. 값은 시안(Home.dc.html renderVals)의 예시 값이다.
 *
 * **API 연동 전까지만 쓴다.** 연동 이슈에서 이 파일을 지우고 API 매핑으로 바꾼다.
 */

const OFFICIAL: OfficialSummary = {
  headline: '전국 인플루엔자 유행주의보',
  sourceLine: '질병관리청 · 전국 · 주간 발표 기준',
  disease: '인플루엔자',
  stage: '유행주의보 · 전국',
  basis: '질병관리청 주간 표본감시',
  href: '/official',
}

/** 시범 운영 초기값 (tokens.json rules.slightDeltaPp · highDeltaPp) */
const THRESHOLDS = { slightDeltaPp: 3, highDeltaPp: 8 }

const COMMON = {
  regionName: '○○동',
  weekLabel: '11월 3주',
  weekRangeLabel: '11월 17일~23일',
  updatedLabel: '오늘 09:00 갱신',
  publicThreshold: 100,
  official: OFFICIAL,
} as const

export const HOME_MOCKS: Record<RegionStatus, HomeWeekly> = {
  normal: {
    ...COMMON,
    status: 'normal',
    summary: '지난 4주와 비슷한 수준이에요',
    participants: 142,
    symptomReports: 9,
    symptomRate: 6,
    baselineRate: 6,
    groups: [
      { key: 'respiratory', trend: 'flat', series: [10, 11, 10, 12, 11, 10, 11] },
      { key: 'gastrointestinal', trend: 'down', series: [12, 11, 10, 10, 9, 9, 8] },
    ],
    thresholds: THRESHOLDS,
    notice: null,
  },
  slight: {
    ...COMMON,
    status: 'slight',
    summary: '발열·기침 보고가 지난주보다 조금 늘었어요',
    participants: 136,
    symptomReports: 15,
    symptomRate: 11,
    baselineRate: 7,
    groups: [
      { key: 'respiratory', trend: 'slight', series: [9, 10, 11, 12, 14, 15, 17] },
      { key: 'gastrointestinal', trend: 'flat', series: [10, 10, 11, 10, 11, 10, 11] },
    ],
    thresholds: THRESHOLDS,
    notice: null,
  },
  high: {
    ...COMMON,
    status: 'high',
    summary: '발열·기침 보고가 지난 4주보다 많이 늘었어요',
    participants: 128,
    symptomReports: 23,
    symptomRate: 18,
    baselineRate: 9,
    groups: [
      { key: 'respiratory', trend: 'high', series: [9, 11, 12, 15, 18, 21, 24] },
      { key: 'gastrointestinal', trend: 'flat', series: [10, 11, 10, 11, 10, 11, 11] },
    ],
    thresholds: THRESHOLDS,
    notice: {
      publishedLabel: '11월 18일 발행',
      items: ['기침할 때 옷소매로 입과 코 가리기', '손 씻기와 실내 환기 자주 하기'],
      source: '질병관리청 예방수칙',
      href: '/notice',
    },
  },
  insufficient: {
    ...COMMON,
    status: 'insufficient',
    summary: '이번 주 보고가 아직 적어요',
    participants: 64,
    notice: null,
  },
}

/**
 * `?mock=` 값으로 목 데이터를 고른다. 모르는 값이면 `자료 부족` 이다 —
 * 실제 자료가 없는 지금 수치를 지어내 보이지 않는 쪽이 기본값이어야 한다.
 */
export function pickHomeMock(value: string | string[] | undefined): HomeWeekly {
  const key = Array.isArray(value) ? value[0] : value
  return key && key in HOME_MOCKS ? HOME_MOCKS[key as RegionStatus] : HOME_MOCKS.insufficient
}
