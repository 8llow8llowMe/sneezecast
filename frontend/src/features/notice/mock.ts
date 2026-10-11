import type { District } from '@/features/region/types'

import type { MeasuredNoticeStats, PublishedRegionNotice, RegionNoticeBody } from './types'

/**
 * 동네 안내 목 데이터. 값은 시안(Guide-published · Guide-none · Guide-corrected)의 예시 값이다. 철회(`retracted`)는 시안이 없다(#225).
 *
 * 데이터 출처가 목일 때만 쓴다(docs/conventions.md "데이터 출처"). 실데이터는 `advisory-api.ts` 가 동네 안내 API 와 행정동 주간 집계를
 * 옮긴다(#256). 목도 실데이터와 같은 모양이다 — 할 일 목록 · 근거 대신 본문, 정정 · 철회 사유 없음(백엔드가 내리지 않는다).
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

/**
 * 안내 예시 동네 모두. `○○동` 에 더해 운영자 화면 목(`features/admin/mock.ts`)의 예시 동네 ○○1동~○○5동을 안다 — 발행 이력(A03, #220)의
 * `사용자 화면에서 보기` 가 이 동네들의 안내를 연다. 안내 내용은 동네와 무관하게 같은 목(`?mock=`)이다. 지어낸 코드라 실데이터에서는 없는 동네다
 */
export const NOTICE_EXAMPLE_DISTRICTS: readonly District[] = [
  NOTICE_EXAMPLE_DISTRICT,
  ...[1, 2, 3, 4, 5].map((n) => ({ code: `9999010${n}`, name: `○○${n}동`, sigungu: '○○시 ○○구' })),
]

/** 시안의 기준 주 (11월 17일~23일 = 2025년 ISO 47주) */
export const NOTICE_EXAMPLE_WEEK = '2025-W47'

export const NOTICE_MOCK_KEYS = [
  'published',
  'corrected',
  'retracted',
  'none',
  'insufficient',
] as const
export type NoticeMockKey = (typeof NOTICE_MOCK_KEYS)[number]

/** 동네 · 주를 뺀 목 데이터. 주소의 동네 · 주에 붙여 쓴다 */
export type NoticeMock = RegionNoticeBody

/** 본문은 시안의 할 일 세 줄을 줄바꿈으로 이은 문장이다(백엔드 안내는 제목 · 본문 한 덩어리) */
const PUBLISHED_NOTICE: PublishedRegionNotice = {
  title: '발열·기침 보고가 지난 4주보다 많이 늘었어요',
  publishedOn: '2025-11-18',
  body: [
    '기침할 때 옷소매로 입과 코를 가려 주세요.',
    '손을 자주 씻고 실내를 자주 환기해 주세요.',
    '열이 나면 사람 많은 곳을 피해 주세요.',
  ].join('\n'),
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
  published: { notice: PUBLISHED_NOTICE, retraction: null, stats: HIGH_STATS },
  // 사유는 없다 — 백엔드 #215 는 정정 사유(운영자 메모)를 공개 응답에 내리지 않는다(철회와 같다). 사유가 있는 모양은 테스트가 그린다
  corrected: {
    retraction: null,
    notice: { ...PUBLISHED_NOTICE, corrections: [{ correctedOn: '2025-11-19' }] },
    stats: HIGH_STATS,
  },
  // 발행 시안의 안내를 철회한 주. 집계는 그 주 집계라 발행 · 정정 시안과 같다(마감 집계는 다시 계산되지 않는다).
  // 사유는 없다 — 백엔드 #215 는 철회 사유(운영자 메모)를 공개 응답에 내리지 않는다. 사유가 있는 모양은 테스트가 그린다
  retracted: {
    notice: null,
    retraction: { retractedOn: '2025-11-20' },
    stats: HIGH_STATS,
  },
  // Guide-none 의 수치 (참여 136명 · 증상 보고 11% · 지난 4주 평균 8%)
  none: {
    notice: null,
    retraction: null,
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
    retraction: null,
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
