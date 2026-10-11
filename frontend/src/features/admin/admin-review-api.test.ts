import { describe, expect, it } from 'vitest'

import { operatorAdvisory } from '@/test/admin-history'

import {
  type ReviewCandidateItemResponse,
  type ReviewCandidateListResponse,
  toReviewCandidate,
  toReviewCandidateList,
  withAdvisoryDetail,
} from './admin-review-api'

/* 후보 API(#266) 응답 예시. 값은 모두 예시다 */

function code(value: string) {
  return { code: value, name: value, description: '' }
}

const WEEK = '2026-W40'

/** 수치가 있는 후보. 기본은 가락1동 — 기준선 변화(HIGH) · 안내 없음 */
function item(overrides: Partial<ReviewCandidateItemResponse> = {}): ReviewCandidateItemResponse {
  return {
    districtCode: '11240660',
    districtName: '가락1동',
    sigungu: '서울특별시 송파구',
    active: true,
    signals: [code('BASELINE')],
    level: code('HIGH'),
    insufficientReason: null,
    draftable: true,
    participantCount: 150,
    previousParticipantCount: 110,
    revisedReportCount: 12,
    symptomaticCount: 30,
    symptomRate: 20,
    baselineRate: 10,
    baselineDeltaPp: 10,
    groups: [
      { group: code('RESPIRATORY'), count: 20, rate: 13.3 },
      { group: code('ENTERIC'), count: 12, rate: 8 },
    ],
    advisory: null,
    ...overrides,
  }
}

/** 자료 부족 후보 — 수치 키가 없다(`@JsonInclude(NON_NULL)`) */
function insufficientItem(
  overrides: Partial<ReviewCandidateItemResponse> = {},
): ReviewCandidateItemResponse {
  return {
    districtCode: '11240670',
    districtName: '가락2동',
    sigungu: '서울특별시 송파구',
    active: true,
    signals: [code('SURGE')],
    level: code('INSUFFICIENT'),
    insufficientReason: code('UNSTABLE'),
    draftable: false,
    participantCount: 240,
    previousParticipantCount: 120,
    revisedReportCount: 3,
    advisory: null,
    ...overrides,
  }
}

function advisory(status: string, advisoryId = '7250', version = 2) {
  return { advisoryId, status: code(status), version }
}

function list(candidates: ReviewCandidateItemResponse[]): ReviewCandidateListResponse {
  return {
    isoWeek: WEEK,
    finalized: true,
    surgeChangePercent: 30,
    repeatRevisedPercent: 20,
    candidates,
  }
}

describe('toReviewCandidate', () => {
  it('수치가 있는 후보: 응답 값을 그대로 옮기고, 동네 · 주로 후보 키를 만든다', () => {
    expect(toReviewCandidate(item(), WEEK)).toEqual({
      id: `11240660:${WEEK}`,
      kind: 'baseline',
      signals: ['baseline'],
      districtCode: '11240660',
      districtName: '가락1동',
      sigungu: '서울특별시 송파구',
      active: true,
      isoWeek: WEEK,
      level: 'high',
      draftable: true,
      participants: 150,
      previousParticipants: 110,
      revisedReports: 12,
      symptomReports: 30,
      symptomRate: 20,
      baselineRate: 10,
      baselineDeltaPp: 10,
      leadingSymptom: 'respiratory',
      state: 'waiting',
    })
  })

  it('대표 유형은 기준선 변화 → 참여 급증 → 반복 보고 의심 순이고, 신호 전체를 그 순서로 남긴다', () => {
    const all = toReviewCandidate(
      item({ signals: [code('REPEAT'), code('SURGE'), code('BASELINE')] }),
      WEEK,
    )
    expect(all?.kind).toBe('baseline')
    expect(all?.signals).toEqual(['baseline', 'surge', 'repeat'])

    const surgeRepeat = toReviewCandidate(item({ signals: [code('REPEAT'), code('SURGE')] }), WEEK)
    expect(surgeRepeat?.kind).toBe('surge')
    expect(surgeRepeat?.signals).toEqual(['surge', 'repeat'])

    expect(toReviewCandidate(item({ signals: [code('REPEAT')] }), WEEK)?.kind).toBe('repeat')
  })

  it('신호 없이 안내만 있는 후보는 유형이 없고(지어내지 않는다) 신호는 빈 배열이다. 모르는 신호는 뺀다', () => {
    const quiet = toReviewCandidate(item({ signals: [], advisory: advisory('EDITED') }), WEEK)
    expect(quiet).not.toHaveProperty('kind')
    expect(quiet?.signals).toEqual([])
    expect(quiet?.state).toBe('reviewing')

    const unknown = toReviewCandidate(item({ signals: [code('NEW_SIGNAL')] }), WEEK)
    expect(unknown).not.toHaveProperty('kind')
    expect(unknown?.signals).toEqual([])
  })

  it('자료 부족 후보: 수치 · 증상군 키가 없고 초안을 만들 수 없으며, 자료 부족 이유를 옮긴다', () => {
    const candidate = toReviewCandidate(insufficientItem(), WEEK)
    expect(candidate).toMatchObject({
      kind: 'surge',
      level: 'insufficient',
      insufficientReason: 'unstable',
      draftable: false,
      participants: 240,
      previousParticipants: 120,
      revisedReports: 3,
    })
    for (const key of [
      'symptomRate',
      'baselineRate',
      'baselineDeltaPp',
      'symptomReports',
      'leadingSymptom',
    ]) {
      expect(candidate).not.toHaveProperty(key)
    }
    expect(
      toReviewCandidate(insufficientItem({ insufficientReason: code('LOW_SAMPLE') }), WEEK),
    ).toMatchObject({ insufficientReason: 'low-sample' })
    expect(
      toReviewCandidate(insufficientItem({ insufficientReason: code('NO_BASELINE') }), WEEK),
    ).toMatchObject({ insufficientReason: 'no-baseline' })
  })

  it('응답에 없는 값(예방수칙 · 최근 비율 · 몰림 · 같은 기기 · 신규 참여 · 검토 시작 · 초안 · 제목)은 키가 없다', () => {
    const candidate = toReviewCandidate(item({ advisory: advisory('DRAFT') }), WEEK)
    for (const key of [
      'guides',
      'recentRates',
      'burstCount',
      'sameDeviceRepeatCount',
      'newParticipantRate',
      'reviewStartedAt',
      'draft',
      'title',
    ]) {
      expect(candidate).not.toHaveProperty(key)
    }
  })

  it('이름 · 시군구 · 전주 참여가 null 이면: 이름은 코드로 보이고 나머지는 키가 없다', () => {
    const candidate = toReviewCandidate(
      item({ districtName: null, sigungu: null, previousParticipantCount: null, active: false }),
      WEEK,
    )
    expect(candidate).toMatchObject({ districtName: '11240660', active: false })
    expect(candidate).not.toHaveProperty('sigungu')
    expect(candidate).not.toHaveProperty('previousParticipants')
  })

  describe('상태 — 안내 상태로 정한다', () => {
    it('안내가 없으면 대기이고 처리할 안내 ID · 버전이 없다', () => {
      const candidate = toReviewCandidate(item({ advisory: null }), WEEK)
      expect(candidate?.state).toBe('waiting')
      expect(candidate).not.toHaveProperty('advisoryId')
      expect(candidate).not.toHaveProperty('version')
    })

    it('철회된 안내뿐이면 대기다 — 철회된 안내는 고칠 수 없어 ID · 버전을 싣지 않는다(초안 만들기부터)', () => {
      const candidate = toReviewCandidate(item({ advisory: advisory('RETRACTED') }), WEEK)
      expect(candidate?.state).toBe('waiting')
      expect(candidate).not.toHaveProperty('advisoryId')
      expect(candidate).not.toHaveProperty('version')
    })

    it.each(['DRAFT', 'AI_DRAFTED', 'EDITED', 'APPROVED'])(
      '%s 는 검토 중이고 처리할 안내 ID · 버전을 싣는다',
      (status) => {
        expect(
          toReviewCandidate(item({ advisory: advisory(status, '7251', 4) }), WEEK),
        ).toMatchObject({ state: 'reviewing', advisoryId: '7251', version: 4 })
      },
    )

    it('발행 중(PUBLISHED)이거나 모르는 상태 · 모르는 단계면 null 이다(부르는 쪽이 뺀다)', () => {
      expect(toReviewCandidate(item({ advisory: advisory('PUBLISHED') }), WEEK)).toBeNull()
      expect(toReviewCandidate(item({ advisory: advisory('ARCHIVED') }), WEEK)).toBeNull()
      expect(toReviewCandidate(item({ level: code('EXTREME') }), WEEK)).toBeNull()
    })
  })

  describe('leadingSymptom — 증상군 중 보고 수가 가장 많은 것', () => {
    it('수가 큰 증상군을 고른다', () => {
      expect(
        toReviewCandidate(
          item({
            groups: [
              { group: code('RESPIRATORY'), count: 4, rate: 2.7 },
              { group: code('ENTERIC'), count: 9, rate: 6 },
            ],
          }),
          WEEK,
        )?.leadingSymptom,
      ).toBe('gastrointestinal')
    })

    it('가장 큰 수가 같거나(하나를 고르지 않는다) 0 이거나 증상군이 없으면 없다. 모르는 증상군은 세지 않는다', () => {
      const tie = toReviewCandidate(
        item({
          groups: [
            { group: code('RESPIRATORY'), count: 7, rate: 4.7 },
            { group: code('ENTERIC'), count: 7, rate: 4.7 },
          ],
        }),
        WEEK,
      )
      expect(tie).not.toHaveProperty('leadingSymptom')

      const none = toReviewCandidate(
        item({
          groups: [
            { group: code('RESPIRATORY'), count: 0, rate: 0 },
            { group: code('ENTERIC'), count: 0, rate: 0 },
          ],
        }),
        WEEK,
      )
      expect(none).not.toHaveProperty('leadingSymptom')

      expect(toReviewCandidate(item({ groups: [] }), WEEK)).not.toHaveProperty('leadingSymptom')

      const unknown = toReviewCandidate(
        item({
          groups: [
            { group: code('OTHER'), count: 50, rate: 33.3 },
            { group: code('ENTERIC'), count: 3, rate: 2 },
          ],
        }),
        WEEK,
      )
      expect(unknown?.leadingSymptom).toBe('gastrointestinal')
    })
  })
})

describe('toReviewCandidateList', () => {
  it('주 · 마감 · 후보 기준을 옮기고, 후보는 서버 순서대로 두되 발행 중인 동네는 뺀다', () => {
    const result = toReviewCandidateList(
      list([
        item(),
        item({ districtCode: '11240680', advisory: advisory('PUBLISHED') }),
        insufficientItem(),
        item({ districtCode: '11240690', signals: [], advisory: advisory('APPROVED') }),
      ]),
    )
    expect(result).toMatchObject({
      isoWeek: WEEK,
      finalized: true,
      criteria: { surgeChangePercent: 30, repeatRevisedPercent: 20 },
    })
    expect(result.candidates.map((candidate) => candidate.id)).toEqual([
      `11240660:${WEEK}`,
      `11240670:${WEEK}`,
      `11240690:${WEEK}`,
    ])
  })

  it('후보가 없으면 빈 배열이다', () => {
    expect(toReviewCandidateList({ ...list([]), finalized: false })).toEqual({
      isoWeek: WEEK,
      finalized: false,
      criteria: { surgeChangePercent: 30, repeatRevisedPercent: 20 },
      candidates: [],
    })
  })
})

describe('withAdvisoryDetail — 안내 상세의 제목 · 초안 · 버전 · 상태를 후보에 덮는다', () => {
  const reviewing = () => {
    const candidate = toReviewCandidate(
      item({ districtCode: '11240660', advisory: advisory('DRAFT', '701', 2) }),
      '2025-W46',
    )
    if (!candidate) throw new Error('no candidate')
    return candidate
  }

  it('검토 중인 안내: 상세의 제목 · 본문 · 버전 · ID 를 싣고 나머지 후보 값은 그대로다', () => {
    const detail = {
      advisory: operatorAdvisory({ statusCode: 'EDITED', version: 5, body: '고친 본문' }),
      histories: [],
    }
    expect(withAdvisoryDetail(reviewing(), detail)).toEqual({
      ...reviewing(),
      state: 'reviewing',
      advisoryId: '701',
      version: 5,
      title: '발열·기침 보고가 지난 4주보다 많이 늘었어요',
      draft: '고친 본문',
    })
  })

  it('그사이 발행됐으면 null(목록에서 뺀다), 철회됐으면 대기로 돌리고 안내 값을 지운다', () => {
    expect(
      withAdvisoryDetail(reviewing(), {
        advisory: operatorAdvisory({ statusCode: 'PUBLISHED' }),
        histories: [],
      }),
    ).toBeNull()

    const retracted = withAdvisoryDetail(
      { ...reviewing(), title: '이전 제목', draft: '이전 본문' },
      { advisory: operatorAdvisory({ statusCode: 'RETRACTED' }), histories: [] },
    )
    expect(retracted?.state).toBe('waiting')
    for (const key of ['advisoryId', 'version', 'title', 'draft']) {
      expect(retracted).not.toHaveProperty(key)
    }
  })

  it('후보가 처리할 안내 ID 를 갖고 있는데 상세가 다른 안내면 거부한다. ID 가 없는 후보(대기)는 동네 · 주만 맞으면 된다', () => {
    expect(() =>
      withAdvisoryDetail(reviewing(), {
        advisory: operatorAdvisory({ statusCode: 'EDITED', advisoryId: '702' }),
        histories: [],
      }),
    ).toThrow()

    const waiting = toReviewCandidate(item({ advisory: null }), '2025-W46')
    if (!waiting) throw new Error('no candidate')
    expect(
      withAdvisoryDetail(waiting, {
        advisory: operatorAdvisory({ statusCode: 'DRAFT', advisoryId: '702', version: 0 }),
        histories: [],
      }),
    ).toMatchObject({ state: 'reviewing', advisoryId: '702', version: 0 })
  })

  it('다른 동네 · 주의 안내이거나 모르는 상태면 계약과 달라 거부한다', () => {
    expect(() =>
      withAdvisoryDetail(reviewing(), {
        advisory: operatorAdvisory({ statusCode: 'EDITED', isoWeek: '2025-W45' }),
        histories: [],
      }),
    ).toThrow()
    expect(() =>
      withAdvisoryDetail(reviewing(), {
        advisory: operatorAdvisory({ statusCode: 'EDITED', districtCode: '11240670' }),
        histories: [],
      }),
    ).toThrow()
    expect(() =>
      withAdvisoryDetail(reviewing(), {
        advisory: operatorAdvisory({ statusCode: 'ARCHIVED' }),
        histories: [],
      }),
    ).toThrow()
  })
})
