import { describe, expect, it } from 'vitest'

import { summarizeAnswer, toggleSymptom } from './symptoms'

describe('toggleSymptom', () => {
  it('누르면 넣고, 다시 누르면 뺀다', () => {
    expect(toggleSymptom([], 'respiratory')).toEqual(['respiratory'])
    expect(toggleSymptom(['respiratory'], 'respiratory')).toEqual([])
  })

  it('두 증상군은 함께 고를 수 있고, 순서는 선택지 순서로 맞춘다', () => {
    expect(toggleSymptom(['gastrointestinal'], 'respiratory')).toEqual([
      'respiratory',
      'gastrointestinal',
    ])
  })

  it('하나를 빼도 나머지는 남는다', () => {
    expect(toggleSymptom(['respiratory', 'gastrointestinal'], 'respiratory')).toEqual([
      'gastrointestinal',
    ])
  })
})

describe('summarizeAnswer', () => {
  it('증상 없음', () => {
    expect(summarizeAnswer({ kind: 'none' })).toBe('증상 없음')
  })

  it('증상 있음 · 고른 증상군', () => {
    expect(
      summarizeAnswer({ kind: 'symptom', symptoms: ['respiratory', 'gastrointestinal'] }),
    ).toBe('증상 있음 · 발열·기침·인후통, 구토·설사')
  })
})
