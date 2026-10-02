import { describe, expect, it } from 'vitest'

import { APP_NAME } from '@/lib/app-info'

import { buildShareLink, SHARE_TEXT, SHARE_TITLE } from './share-link'

describe('buildShareLink', () => {
  it('동네가 있으면 홈 주소에 동네 코드만 붙인다', () => {
    expect(buildShareLink('https://www.sneezecast.com', '11440660')).toBe(
      'https://www.sneezecast.com/?region=11440660',
    )
  })

  it('동네가 없으면 쿼리 없는 홈 주소다', () => {
    expect(buildShareLink('https://www.sneezecast.com', null)).toBe('https://www.sneezecast.com/')
    expect(buildShareLink('https://www.sneezecast.com', '')).toBe('https://www.sneezecast.com/')
  })

  it('사이트 주소 끝의 / 는 하나로 맞춘다', () => {
    expect(buildShareLink('https://dev.sneezecast.com/', '11440660')).toBe(
      'https://dev.sneezecast.com/?region=11440660',
    )
    expect(buildShareLink('https://dev.sneezecast.com//', null)).toBe('https://dev.sneezecast.com/')
  })

  it('동네 코드는 쿼리 값으로 감싼다 — 다른 쿼리를 끼워 넣을 수 없다', () => {
    expect(buildShareLink('http://localhost:3000', '1&utm_source=x')).toBe(
      'http://localhost:3000/?region=1%26utm_source%3Dx',
    )
  })
})

describe('공유 문구', () => {
  it('앱 이름과 건강 상태를 암시하지 않는 문구다', () => {
    expect(SHARE_TITLE).toBe(APP_NAME)
    expect(SHARE_TEXT).not.toMatch(/증상 있|위험|유행|비상|아파/)
  })
})
