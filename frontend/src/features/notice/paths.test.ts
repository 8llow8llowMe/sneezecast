import { describe, expect, it } from 'vitest'

import { noticePath, parseNoticeRoute } from './paths'

describe('noticePath', () => {
  it('행정동 코드 · 기준 주로 주소를 만든다', () => {
    expect(noticePath('11680640', '2025-W47')).toBe('/notice/11680640/2025-W47')
    expect(noticePath('11680640', '2025-W47', 'mock=published')).toBe(
      '/notice/11680640/2025-W47?mock=published',
    )
  })
})

describe('parseNoticeRoute', () => {
  it('8자리 행정동 코드와 ISO 주를 받는다', () => {
    expect(parseNoticeRoute('11680640', '2025-W47')).toEqual({
      regionCode: '11680640',
      isoWeek: '2025-W47',
    })
  })

  it('행정동 코드가 8자리 숫자가 아니면 null 이다', () => {
    for (const region of ['', '1168064', '116806400', '1168064a', '../11680', '역삼1동']) {
      expect(parseNoticeRoute(region, '2025-W47'), region).toBeNull()
    }
  })

  it('주가 ISO 주 모양이 아니거나 그 해에 없는 주면 null 이다', () => {
    for (const week of ['', '2025-47', '2025-W00', '2025-W53', 'latest', '11월 3주']) {
      expect(parseNoticeRoute('11680640', week), week).toBeNull()
    }
  })
})
