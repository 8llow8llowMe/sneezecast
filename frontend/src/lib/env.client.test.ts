import { describe, expect, it } from 'vitest'

import { normalizeBaseUrl } from './env.client'

describe('normalizeBaseUrl', () => {
  it('끝의 / 를 지운다', () => {
    expect(normalizeBaseUrl('https://api-dev.sneezecast.com/', 'x')).toBe(
      'https://api-dev.sneezecast.com',
    )
    expect(normalizeBaseUrl('https://api.sneezecast.com///', 'x')).toBe(
      'https://api.sneezecast.com',
    )
  })

  it('값이 없거나 공백이면 기본값을 쓴다', () => {
    expect(normalizeBaseUrl(undefined, 'http://localhost:3000')).toBe('http://localhost:3000')
    expect(normalizeBaseUrl('   ', 'http://localhost:3000/')).toBe('http://localhost:3000')
  })

  it('앞뒤 공백을 지운다', () => {
    expect(normalizeBaseUrl(' https://www.sneezecast.com ', 'x')).toBe('https://www.sneezecast.com')
  })
})
