import type { District } from '@/features/region/types'

import type { MeasuredNoticeStats, PublishedRegionNotice, RegionNoticeBody } from './types'

/**
 * 동네 안내 목 데이터. 값은 시안(Guide-published · Guide-none · Guide-corrected)의 예시 값이다.
 *
 * **API 연동 전까지만 쓴다.** 연동 이슈(`GET /api/notices/{admCd}?week=`)에서 이 파일을 지우고 API 매핑으로 바꾼다.
 */

/**
 * 안내 예시 동네. 홈 목의 동네 이름(`○○동`)과 맞춘다 — 홈의 `안내 전체 보기` 가 이 동네로 이어진다.
 * **예시 값이다.** 코드는 실제 행정동과 겹치지 않게 지어낸 8자리이고, 검색 목(`DISTRICT_MOCKS`)에는 넣지 않는다.
 */
export const NOTICE_EXAMPLE_DISTRICT: District = {
  code: '99990100',
  name: '○○동',
  sigungu: '○○시 ○○구',
}

/** 시안의 기준 주 (11월 17일~23일 = 2025년 ISO 47주) */
export const NOTICE_EXAMPLE_WEEK = '2025-W47'

export const NOTICE_MOCK_KEYS = ['published', 'corrected', 'none', 'insufficient'] as const
export type NoticeMockKey = (typeof NOTICE_MOCK_KEYS)[number]

/** 동네 · 주를 뺀 목 데이터. 주소의 동네 · 주에 붙여 쓴다 */
export type NoticeMock = RegionNoticeBody

const PUBLISHED_NOTICE: PublishedRegionNotice = {
  title: '발열·기침 보고가 지난 4주보다 많이 늘었어요',
  publishedOn: '2025-11-18',
  items: [
    '기침할 때 옷소매로 입과 코 가리기',
    '손 씻기와 실내 환기 자주 하기',
    '열이 나면 사람 많은 곳 피하기',
  ],
  source: '질병관리청 예방수칙',
  corrections: [],
}

/** 발행 · 정정 시안의 수치 (참여 128명 · 증상 보고 18% · 지난 4주 평균 9%). 증상군 비율은 전체 비율을 넘지 않게 맞췄다 */
const HIGH_STATS: MeasuredNoticeStats = {
  status: 'high',
  participants: 128,
  symptomRate: 18,
  baselineRate: 9,
  groups: [
    { key: 'respiratory', trend: 'high', series: [10, 12, 14, 17] },
    { key: 'gastrointestinal', trend: 'flat', series: [5, 6, 5, 6] },
  ],
}

export const NOTICE_MOCKS: Record<NoticeMockKey, NoticeMock> = {
  published: { notice: PUBLISHED_NOTICE, stats: HIGH_STATS },
  corrected: {
    notice: {
      ...PUBLISHED_NOTICE,
      corrections: [
        {
          correctedOn: '2025-11-19',
          reason:
            '중복 보고를 제외해 참여자 수를 131명에서 128명으로 바로잡았어요. 안내 내용은 같아요.',
        },
      ],
    },
    stats: HIGH_STATS,
  },
  // Guide-none 의 수치 (참여 136명 · 증상 보고 11% · 지난 4주 평균 8%)
  none: {
    notice: null,
    stats: {
      status: 'slight',
      participants: 136,
      symptomRate: 11,
      baselineRate: 8,
      groups: [
        { key: 'respiratory', trend: 'slight', series: [6, 7, 7, 9] },
        { key: 'gastrointestinal', trend: 'flat', series: [4, 4, 5, 4] },
      ],
    },
  },
  // 시안에 없는 상태. 홈 목의 자료 부족(참여 64명)과 같은 값이다
  insufficient: {
    notice: null,
    stats: { status: 'insufficient', participants: 64, publicThreshold: 100 },
  },
}

/**
 * `?mock=` 값으로 목 데이터를 고른다. 모르는 값이면 `자료 부족` 의 안내 없음이다 —
 * 실제 자료가 없는 지금 수치 · 안내를 지어내 보이지 않는 쪽이 기본값이어야 한다(홈과 같은 규칙).
 */
export function pickNoticeMock(value: string | string[] | undefined): NoticeMock {
  const key = Array.isArray(value) ? value[0] : value
  const known = NOTICE_MOCK_KEYS.find((item) => item === key)
  return NOTICE_MOCKS[known ?? 'insufficient']
}
