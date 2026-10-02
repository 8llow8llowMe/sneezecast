// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  DATA_SOURCE_COOKIE,
  isDataSourceSwitchable,
  parseDataSource,
  readBrowserDataSource,
  readCookieValue,
  resolveDataSource,
  subscribeDataSource,
  writeBrowserDataSource,
} from './data-source'

const DEV = { siteUrl: 'https://dev.sneezecast.com', defaultValue: '' }

function clearCookie() {
  document.cookie = `${DATA_SOURCE_COOKIE}=; Path=/; Max-Age=0`
}

describe('parseDataSource', () => {
  it('api · mock 만 받는다', () => {
    expect(parseDataSource('api')).toBe('api')
    expect(parseDataSource('mock')).toBe('mock')
  })

  it.each([undefined, null, '', 'API', 'real', ' api'])('모르는 값(%s)이면 null 이다', (value) => {
    expect(parseDataSource(value)).toBeNull()
  })
})

const PRODUCTION = 'https://www.sneezecast.com'

describe('resolveDataSource', () => {
  it('쿠키 값을 따른다', () => {
    expect(resolveDataSource('api', DEV)).toBe('api')
    expect(resolveDataSource('mock', { ...DEV, defaultValue: 'api' })).toBe('mock')
  })

  it('쿠키가 없거나 모르는 값이면 환경변수 기본값이다', () => {
    expect(resolveDataSource(undefined, { ...DEV, defaultValue: 'api' })).toBe('api')
    expect(resolveDataSource('real', { ...DEV, defaultValue: 'api' })).toBe('api')
  })

  it('환경변수도 없거나 모르는 값이면 mock 이다', () => {
    expect(resolveDataSource(undefined, DEV)).toBe('mock')
    expect(resolveDataSource(undefined, { ...DEV, defaultValue: 'real' })).toBe('mock')
  })

  it('운영 · 모르는 사이트는 쿠키 · 환경변수와 무관하게 늘 api 다', () => {
    const production = { siteUrl: PRODUCTION, defaultValue: 'mock' }
    expect(resolveDataSource('mock', production)).toBe('api')
    expect(resolveDataSource(undefined, production)).toBe('api')
    expect(resolveDataSource('mock', { siteUrl: '', defaultValue: 'mock' })).toBe('api')
  })
})

describe('isDataSourceSwitchable', () => {
  it.each([
    'https://dev.sneezecast.com',
    'http://localhost:3000',
    'http://localhost',
    'http://127.0.0.1:3000',
    'http://[::1]:3000',
    'http://app.localhost:3000',
  ])('허용 목록(%s)이면 바꿀 수 있다', (siteUrl) => {
    expect(isDataSourceSwitchable(siteUrl)).toBe(true)
  })

  it.each([
    ['운영', PRODUCTION],
    ['모르는 https 주소', 'https://staging.sneezecast.com'],
    ['dev 를 http 로', 'http://dev.sneezecast.com'],
    ['https 로컬', 'https://localhost'],
    ['localhost 를 흉내 낸 호스트', 'http://localhost.example.com'],
    ['빈 값', ''],
    ['주소가 아님', 'dev.sneezecast.com'],
    ['파싱 실패', 'http://'],
  ])('그 밖(%s)이면 바꿀 수 없다', (_, siteUrl) => {
    expect(isDataSourceSwitchable(siteUrl)).toBe(false)
  })
})

describe('readCookieValue', () => {
  it('이름이 정확히 같은 값을 읽는다', () => {
    expect(readCookieValue('a=1; sc_data_source=api; b=2', DATA_SOURCE_COOKIE)).toBe('api')
    expect(readCookieValue('xsc_data_source=api', DATA_SOURCE_COOKIE)).toBeUndefined()
    expect(readCookieValue('', DATA_SOURCE_COOKIE)).toBeUndefined()
  })
})

describe('브라우저 쿠키', () => {
  afterEach(clearCookie)

  it('쓴 출처를 다시 읽고 구독자에게 알린다', () => {
    const listener = vi.fn()
    const unsubscribe = subscribeDataSource(listener)

    writeBrowserDataSource('api')
    expect(readBrowserDataSource()).toBe('api')
    writeBrowserDataSource('mock')
    expect(readBrowserDataSource()).toBe('mock')
    expect(listener).toHaveBeenCalledTimes(2)

    unsubscribe()
    writeBrowserDataSource('api')
    expect(listener).toHaveBeenCalledTimes(2)
  })

  it('쿠키가 없으면 기본값(환경변수가 없는 테스트에서는 mock)이다', () => {
    expect(readBrowserDataSource()).toBe('mock')
  })
})
