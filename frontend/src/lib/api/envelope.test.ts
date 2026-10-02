import { describe, expect, it } from 'vitest'

import { readEnvelope } from './envelope'

describe('readEnvelope', () => {
  it('성공 헤더면 dataBody 를 그대로 두고, 없으면 null 로 둔다', () => {
    expect(readEnvelope({ dataHeader: { success: true }, dataBody: [1] })).toEqual({
      dataHeader: { success: true },
      dataBody: [1],
    })
    expect(readEnvelope({ dataHeader: { success: true } })).toEqual({
      dataHeader: { success: true },
      dataBody: null,
    })
  })

  it('실패 헤더의 fieldErrors 가 null 이면 빈 목록이고, resultMessage 가 문자열이 아니면 빈 문자열이다', () => {
    expect(
      readEnvelope({
        dataHeader: {
          success: false,
          resultCode: 'AUTH_011',
          resultMessage: null,
          fieldErrors: null,
        },
        dataBody: null,
      }),
    ).toEqual({
      dataHeader: { success: false, resultCode: 'AUTH_011', resultMessage: '', fieldErrors: [] },
      dataBody: null,
    })
  })

  it.each([
    ['undefined', undefined],
    ['배열', [{ dataHeader: { success: true } }]],
    ['헤더 없음', { dataBody: {} }],
    ['success 가 불리언이 아님', { dataHeader: { success: 'true' } }],
    ['실패인데 코드 없음', { dataHeader: { success: false, resultMessage: 'x' } }],
  ])('봉투가 아니면(%s) null 이다', (_label, json) => {
    expect(readEnvelope(json)).toBeNull()
  })
})
