import type { OfficialReport } from './types'

/**
 * 공식 정보 목 데이터. `published` 의 값은 시안(Official · Official-T · Official-D)의 예시 값이다 —
 * 실제 발표가 아니고 날짜 · 주차도 지금과 무관하다.
 *
 * **API 연동 전까지만 쓴다.** 연동 이슈(백엔드 #83~#85 적재 뒤)에서 이 파일을 지우고 API 매핑으로 바꾼다.
 */
export const OFFICIAL_MOCKS = {
  published: {
    status: 'published',
    regionLabel: '전국',
    weekLabel: '47주',
    periodLabel: '11월 17일~23일',
    announcedLabel: '11월 21일 발표',
    disease: '인플루엔자',
    surveyLabel: '의료기관 표본감시',
    stage: '유행주의보 발령 중',
    summary: [
      '의원급 의료기관을 찾은 독감 의심 환자 비율이 유행 기준을 넘었어요.',
      '아동·청소년 연령대에서 특히 높게 나타났어요.',
      '예방접종과 손 씻기, 기침 예절을 권고하고 있어요.',
    ],
    // 예시 주소 — 질병관리청 감염병포털 첫 화면. 연동 때 발표 원문 주소로 바꾼다
    sourceUrl: 'https://dportal.kdca.go.kr/',
  },
  empty: {
    status: 'empty',
    regionLabel: '전국',
  },
} as const satisfies Record<string, OfficialReport>

export type OfficialMockKey = keyof typeof OFFICIAL_MOCKS

function isMockKey(value: string): value is OfficialMockKey {
  return Object.hasOwn(OFFICIAL_MOCKS, value)
}

/**
 * `?mock=` 값으로 목 데이터를 고른다(`published` · `empty`). 없거나 모르는 값이면 `empty`(받은 발표 없음)다 —
 * 실제 자료가 없는 지금 발표를 지어내 보이지 않는 쪽이 기본값이어야 한다 (홈 `pickHomeMock` 과 같다).
 */
export function pickOfficialMock(value: string | string[] | undefined): OfficialReport {
  const key = Array.isArray(value) ? value[0] : value
  return key !== undefined && isMockKey(key) ? OFFICIAL_MOCKS[key] : OFFICIAL_MOCKS.empty
}
