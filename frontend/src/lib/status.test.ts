import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

import { isMeasured, REGION_STATUSES, STATUS_FILL_CLASS, STATUS_LABEL } from './status'

const tokens = JSON.parse(
  readFileSync(new URL('../../docs/design/tokens.json', import.meta.url), 'utf8'),
) as { status: Record<string, { label: string }> }

describe('상태 단계', () => {
  it('단계 목록이 tokens.json status 와 같다', () => {
    expect([...REGION_STATUSES].sort()).toEqual(Object.keys(tokens.status).sort())
  })

  it.each(REGION_STATUSES)('%s 라벨이 tokens.json 과 같다', (status) => {
    expect(STATUS_LABEL[status]).toBe(tokens.status[status]?.label)
  })

  it('자료 부족만 수치를 보이지 않는다', () => {
    expect(REGION_STATUSES.filter((status) => !isMeasured(status))).toEqual(['insufficient'])
  })

  it('지도 칠하기 색은 자료 부족만 상태색이 아닌 회색이다', () => {
    expect(
      REGION_STATUSES.filter((status) => STATUS_FILL_CLASS[status].startsWith('bg-status-')),
    ).toEqual(['normal', 'slight', 'high'])
    expect(STATUS_FILL_CLASS.insufficient).toBe('bg-muted-bar')
  })
})
