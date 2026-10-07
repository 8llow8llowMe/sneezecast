import type { HistoryEntry, ReviewCandidate } from './types'

/**
 * 운영자 검토 목 데이터 (시안 Admin 의 값). 동 이름 · 수치 · 초안은 모두 예시다.
 *
 * - ○○1동은 시안의 상세 값(기준선 막대 · 이상 보고 확인 · 검토 시작)을 모두 채운다. 나머지 후보는 시안에 상세가 없어
 *   백엔드 1단계에 없는 선택 값(막대 · 이상 보고 확인 · 검토 시작)을 비워 둔다 — 값이 없을 때 그 칸 · 행이 숨는 모양을 본다
 * - ○○3동의 참여는 시안의 64명이 아니라 146명이다. 시안 표 아래 문구("표본이 100명 미만인 행정동은 후보에 올라오지 않아요")와
 *   도메인 규칙(표본이 적으면 `자료 부족`)이 맞지 않아 100명 이상으로 고쳤다
 * - 기준선(`baselineRate`)은 시안 표의 `기준선 대비` 값과 증상 보고 비율로 맞춘다(○○2동 7% − 1%p = 6%)
 */

/** 목 후보의 집계 주 (`11월 3주`). 동네 안내 목(`NOTICE_EXAMPLE_WEEK`)과 같은 주다 */
export const MOCK_REVIEW_WEEK = '2025-W47'

/** 시안의 `검토 시작 14:02 · 경과 6분` — 목을 처음 만들 때부터 6분 전에 시작한 것으로 둔다 */
const REVIEW_STARTED_MINUTES_AGO = 6

/** 목 후보 목록을 새로 만든다. `now` 는 검토 시작 시각의 기준이다 */
export function createMockCandidates(now: Date = new Date()): ReviewCandidate[] {
  const startedAt = new Date(now.getTime() - REVIEW_STARTED_MINUTES_AGO * 60_000)
  return [
    {
      id: 'candidate-1',
      kind: 'baseline',
      districtCode: '99990101',
      districtName: '○○1동',
      isoWeek: MOCK_REVIEW_WEEK,
      participants: 128,
      symptomRate: 18,
      leadingSymptom: 'respiratory',
      state: 'reviewing',
      draft:
        '○○동에서 이번 주 발열·기침 보고가 지난 4주보다 많이 늘었어요(참여 128명 중 18%). 기침할 때는 옷소매로 입과 코를 가려 주세요. 손 씻기와 실내 환기도 자주 해 주세요. 이 정보는 진단이 아닌 참고용이에요.',
      version: 3,
      guides: ['기침 예절', '손 씻기'],
      baselineRate: 9,
      baselineDeltaPp: 9,
      recentRates: [8.3, 8.6, 7.9, 9, 9.4, 10.9, 13.9, 18],
      burstCount: 0,
      sameDeviceRepeatCount: 2,
      newParticipantRate: 12,
      reviewStartedAt: startedAt.toISOString(),
    },
    {
      id: 'candidate-2',
      kind: 'surge',
      districtCode: '99990102',
      districtName: '○○2동',
      isoWeek: MOCK_REVIEW_WEEK,
      participants: 212,
      symptomRate: 7,
      leadingSymptom: 'respiratory',
      state: 'waiting',
      draft:
        '○○2동은 이번 주 참여가 지난주보다 많이 늘었어요(참여 212명 중 7%). 증상 보고 비율은 지난 4주와 비슷해요. 손 씻기와 실내 환기를 자주 해 주세요. 이 정보는 진단이 아닌 참고용이에요.',
      version: 1,
      guides: ['손 씻기'],
      baselineRate: 6,
      baselineDeltaPp: 1,
    },
    {
      id: 'candidate-3',
      kind: 'repeat',
      districtCode: '99990103',
      districtName: '○○3동',
      isoWeek: MOCK_REVIEW_WEEK,
      participants: 146,
      symptomRate: 22,
      leadingSymptom: 'gastrointestinal',
      state: 'waiting',
      draft:
        '○○3동에서 이번 주 구토·설사 보고가 지난 4주보다 많이 늘었어요(참여 146명 중 22%). 음식은 충분히 익혀 드세요. 화장실을 쓴 뒤에는 손을 꼭 씻어 주세요. 이 정보는 진단이 아닌 참고용이에요.',
      version: 1,
      guides: ['손 씻기', '음식 익혀 먹기'],
      baselineRate: 10,
      baselineDeltaPp: 12,
    },
    {
      id: 'candidate-4',
      kind: 'baseline',
      districtCode: '99990104',
      districtName: '○○4동',
      isoWeek: MOCK_REVIEW_WEEK,
      participants: 104,
      symptomRate: 14,
      leadingSymptom: 'respiratory',
      state: 'waiting',
      draft:
        '○○4동에서 이번 주 발열·기침 보고가 지난 4주보다 조금 늘었어요(참여 104명 중 14%). 기침할 때는 옷소매로 입과 코를 가려 주세요. 이 정보는 진단이 아닌 참고용이에요.',
      version: 1,
      guides: ['기침 예절'],
      baselineRate: 8,
      baselineDeltaPp: 6,
    },
  ]
}

/**
 * 발행 이력 목 데이터 (시안 History 의 지난 주 행, #220). 동 이름 · 수치 · 본문은 모두 예시다.
 *
 * - 이번 주(`MOCK_REVIEW_WEEK`) 행은 넣지 않는다. 시안의 이번 주 행(○○2동 수정 후 발행 · ○○3동 보류)은 검토 대기 목에서 아직 `대기` 인
 *   후보라 두 화면이 어긋난다. 이번 주 행은 검토 대기에서 보류 · 발행하면 이력에 더해진다(`recordReviewOutcome`)
 * - 시안 상세(타임라인 세부 문구 · 수정 후 발행 · 참여 128명 · 18% · 6분)는 첫 행 ○○1동(11월 2주)에 옮겼다. 나머지 행은 백엔드 이력에서
 *   나올 수 없는 세부 문구를 비워 둔다 — 값이 없을 때 숨는 모양을 본다
 * - 10월 4주는 ISO 2025-W43 이다(주를 그 주 목요일의 달로 센다 — `formatIsoWeekOfMonth`). 처리일은 시안대로 10월 28일이다
 * - 동은 동네 안내 목의 예시 동네(`features/notice/mock.ts` 의 `NOTICE_EXAMPLE_DISTRICTS`)라 `사용자 화면에서 보기` 가 열린다
 */
export function createMockHistory(): HistoryEntry[] {
  return [
    {
      id: 'advisory-1',
      kind: 'baseline',
      districtCode: '99990101',
      districtName: '○○1동',
      isoWeek: '2025-W46',
      participants: 128,
      symptomRate: 18,
      outcome: 'published',
      processedAt: '2025-11-11T05:08:00.000Z',
      edited: true,
      body: '○○동에서 이번 주 발열·기침 보고가 지난 4주보다 많이 늘었어요(참여 128명 중 18%). 기침할 때는 옷소매로 입과 코를 가려 주세요. 손 씻기와 실내 환기도 자주 해 주세요.',
      version: 4,
      timeline: [
        {
          step: 'registered',
          at: '2025-11-11T05:02:00.000Z',
          note: '기준선 9% 대비 +9%p · 표본 128명',
        },
        {
          step: 'ai-drafted',
          at: '2025-11-11T05:03:00.000Z',
          note: '출처 확인된 집계값 3개 · 예방수칙 2건 연결',
        },
        {
          step: 'edited',
          at: '2025-11-11T05:06:00.000Z',
          note: '"유행" 표현 삭제 · 행동 문장 1개 추가',
        },
        { step: 'published', at: '2025-11-11T05:08:00.000Z' },
      ],
      operatorName: '운영자 A',
      reviewMinutes: 6,
    },
    {
      id: 'candidate-w46-5',
      kind: 'surge',
      districtCode: '99990105',
      districtName: '○○5동',
      isoWeek: '2025-W46',
      participants: 186,
      symptomRate: 6,
      outcome: 'held',
      processedAt: '2025-11-11T04:20:00.000Z',
      edited: false,
      body: '○○5동은 이번 주 참여가 지난주보다 많이 늘었어요(참여 186명 중 6%). 증상 보고 비율은 지난 4주와 비슷해요. 손 씻기를 자주 해 주세요.',
      version: 2,
      timeline: [
        { step: 'registered', at: '2025-11-11T04:10:00.000Z' },
        { step: 'ai-drafted', at: '2025-11-11T04:11:00.000Z' },
        { step: 'held', at: '2025-11-11T04:20:00.000Z' },
      ],
      operatorName: '운영자 B',
      reviewMinutes: 9,
    },
    {
      id: 'advisory-2',
      kind: 'baseline',
      districtCode: '99990102',
      districtName: '○○2동',
      isoWeek: '2025-W45',
      participants: 142,
      symptomRate: 12,
      outcome: 'published',
      processedAt: '2025-11-04T05:30:00.000Z',
      edited: false,
      body: '○○2동에서 이번 주 발열·기침 보고가 지난 4주보다 조금 늘었어요(참여 142명 중 12%). 기침할 때는 옷소매로 입과 코를 가려 주세요.',
      version: 3,
      timeline: [
        { step: 'registered', at: '2025-11-04T05:21:00.000Z' },
        { step: 'ai-drafted', at: '2025-11-04T05:23:00.000Z' },
        { step: 'published', at: '2025-11-04T05:30:00.000Z' },
      ],
      operatorName: '운영자 A',
      reviewMinutes: 7,
    },
    {
      id: 'advisory-3',
      kind: 'baseline',
      districtCode: '99990104',
      districtName: '○○4동',
      isoWeek: '2025-W43',
      participants: 104,
      symptomRate: 13,
      outcome: 'published',
      processedAt: '2025-10-28T06:05:00.000Z',
      edited: false,
      body: '○○4동에서 이번 주 발열·기침 보고가 지난 4주보다 조금 늘었어요(참여 104명 중 13%). 손 씻기와 실내 환기를 자주 해 주세요.',
      version: 3,
      timeline: [
        { step: 'registered', at: '2025-10-28T05:58:00.000Z' },
        { step: 'ai-drafted', at: '2025-10-28T06:00:00.000Z' },
        { step: 'published', at: '2025-10-28T06:05:00.000Z' },
      ],
      operatorName: '운영자 B',
      reviewMinutes: 5,
    },
  ]
}
