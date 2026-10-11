import { describe, expect, it } from 'vitest'

import {
  correctedHistory,
  historyItem,
  historyResponse,
  historyRow,
  operatorAdvisory,
  PUBLISHED_ROWS,
} from '@/test/admin-history'

import { citedRate, toHistoryEntries, toHistoryEntry } from './admin-history-api'

describe('citedRate — 공개 집계와 같은 반올림(퍼센트 소수 첫째 자리, HALF_UP)', () => {
  it('센 수로 계산한다', () => {
    expect(citedRate(20, 120)).toBe(16.7)
    expect(citedRate(1, 8)).toBe(12.5)
    expect(citedRate(1, 16)).toBe(6.3)
    expect(citedRate(23, 128)).toBe(18)
    expect(citedRate(0, 100)).toBe(0)
  })

  it('분모가 0 이하이거나 분자가 범위 밖이면 null 이다', () => {
    expect(citedRate(0, 0)).toBeNull()
    expect(citedRate(-1, 100)).toBeNull()
    expect(citedRate(101, 100)).toBeNull()
    expect(citedRate(1.5, 100)).toBeNull()
  })
})

describe('toHistoryEntry', () => {
  it('발행 중인 안내: 인용 수 · 제목 · 본문 · version 을 옮기고, 승인 행은 발행과 묶으며 수정 메모는 싣지 않는다', () => {
    expect(toHistoryEntry(historyItem())).toEqual({
      id: '701',
      districtCode: '11240660',
      districtName: '가락1동',
      isoWeek: '2025-W46',
      participants: 128,
      symptomRate: 18,
      outcome: 'published',
      processedAt: '2025-11-11T05:08:00Z',
      edited: true,
      title: '발열·기침 보고가 지난 4주보다 많이 늘었어요',
      body: '손을 자주 씻어 주세요.',
      version: 4,
      timeline: [
        { step: 'registered', at: '2025-11-11T05:02:00Z' },
        { step: 'edited', at: '2025-11-11T05:06:00Z' },
        { step: 'published', at: '2025-11-11T05:08:00Z' },
      ],
    })
  })

  it('응답에 없는 값(후보 유형 · 담당 · 검토 시간)은 키가 없다', () => {
    const entry = toHistoryEntry(historyItem())
    expect(entry).not.toHaveProperty('kind')
    expect(entry).not.toHaveProperty('operatorName')
    expect(entry).not.toHaveProperty('reviewMinutes')
  })

  it('수정을 거치지 않았거나 발행 뒤에만 수정 이력이 있으면 edited 가 아니다. AI 초안은 그 단계로 옮긴다', () => {
    const entry = toHistoryEntry(
      historyItem({
        histories: [
          historyRow(null, 'DRAFT', '2025-11-11T05:02:00Z'),
          historyRow('DRAFT', 'AI_DRAFTED', '2025-11-11T05:03:00Z'),
          historyRow('AI_DRAFTED', 'APPROVED', '2025-11-11T05:07:00Z'),
          historyRow('APPROVED', 'PUBLISHED', '2025-11-11T05:08:00Z'),
          // 계약에는 없는 전이지만 발행 뒤 행은 수정 여부에 세지 않는다
          historyRow('PUBLISHED', 'EDITED', '2025-11-12T05:00:00Z'),
        ],
      }),
    )
    expect(entry?.edited).toBe(false)
    expect(entry?.timeline.map((event) => event.step)).toEqual([
      'registered',
      'ai-drafted',
      'published',
      'edited',
    ])
  })

  it('정정됨 · 정정 발행: 생성 행은 새 안내의 발행과 묶고, 사유는 운영자 이력의 memo 에서 읽는다', () => {
    const [fresh, previous] = toHistoryEntries(correctedHistory())
    expect(fresh).toMatchObject({
      id: '702',
      outcome: 'published',
      edited: false,
      title: '고친 제목',
      body: '고친 본문',
      version: 1,
      processedAt: '2025-11-20T05:08:00Z',
      correction: {
        previousId: '701',
        correctedOn: '2025-11-20',
        reason: '참여자 수를 바로잡았어요.',
      },
      timeline: [
        {
          step: 'correction-published',
          at: '2025-11-20T05:08:00Z',
          note: '참여자 수를 바로잡았어요.',
        },
      ],
    })
    expect(fresh).not.toHaveProperty('retraction')
    expect(previous).toMatchObject({ id: '701', outcome: 'corrected', version: 5 })
    expect(previous).not.toHaveProperty('retraction')
    expect(previous).not.toHaveProperty('correction')
    expect(previous?.timeline.at(-1)).toEqual({
      step: 'corrected',
      at: '2025-11-20T05:08:00Z',
      note: '참여자 수를 바로잡았어요.',
    })
  })

  it('철회: 철회일(한국 날짜) · 사유는 RETRACTED 행에서 읽는다', () => {
    const entry = toHistoryEntry(
      historyItem({
        advisory: operatorAdvisory({
          statusCode: 'RETRACTED',
          // 한국 날짜로는 다음 날이다
          retractedAt: '2025-11-19T15:30:00Z',
        }),
        histories: [
          ...PUBLISHED_ROWS,
          historyRow('PUBLISHED', 'RETRACTED', '2025-11-19T15:30:00Z', '공식 자료와 달랐어요.'),
        ],
      }),
    )
    expect(entry).toMatchObject({
      outcome: 'retracted',
      retraction: { retractedOn: '2025-11-20', reason: '공식 자료와 달랐어요.' },
    })
    expect(entry?.timeline.at(-1)).toEqual({
      step: 'retracted',
      at: '2025-11-19T15:30:00Z',
      note: '공식 자료와 달랐어요.',
    })
  })

  it('행정동 이름이 없으면 코드로 보이고, 모르는 상태로 가는 행은 타임라인에서 뺀다', () => {
    const entry = toHistoryEntry(
      historyItem({
        districtName: null,
        histories: [...PUBLISHED_ROWS, historyRow('PUBLISHED', 'ARCHIVED', '2025-11-12T00:00:00Z')],
      }),
    )
    expect(entry?.districtName).toBe('11240660')
    expect(entry?.timeline).toHaveLength(3)
  })

  it('발행됨 · 철회됨이 아니거나 인용 수로 비율을 낼 수 없는 항목은 뺀다(계약과 다름)', () => {
    const draft = historyItem({
      advisory: operatorAdvisory({ advisoryId: '1', statusCode: 'DRAFT' }),
    })
    const broken = historyItem({
      advisory: {
        ...operatorAdvisory({ advisoryId: '2' }),
        citation: { ...operatorAdvisory().citation, participantCount: 0, symptomaticCount: 0 },
      },
    })
    const ok = historyItem({ advisory: operatorAdvisory({ advisoryId: '3' }) })
    expect(toHistoryEntries(historyResponse([draft, broken, ok])).map((item) => item.id)).toEqual([
      '3',
    ])
  })
})
