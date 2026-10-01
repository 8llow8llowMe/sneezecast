import { afterEach, describe, expect, it, vi } from 'vitest'

import { cameInApp, documentEntryPath } from './back'

describe('cameInApp', () => {
  it('처음 연 주소가 다른 화면이면 앱 안에서 거쳐 왔다', () => {
    expect(cameInApp('/', '/official')).toBe(true)
    expect(cameInApp('/me', '/official')).toBe(true)
  })

  it('처음 연 주소가 이 화면이거나 모르면 바로 들어왔다', () => {
    expect(cameInApp('/official', '/official')).toBe(false)
    expect(cameInApp(null, '/official')).toBe(false)
  })
})

describe('documentEntryPath', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('처음 연 문서 주소의 경로만 돌려준다(쿼리 제외)', () => {
    vi.spyOn(performance, 'getEntriesByType').mockReturnValue([
      { name: 'https://dev.sneezecast.com/official?mock=published' } as PerformanceEntry,
    ])
    expect(documentEntryPath()).toBe('/official')
  })

  it('기록이 없거나 주소를 읽지 못하면 null 이다', () => {
    const spy = vi.spyOn(performance, 'getEntriesByType').mockReturnValue([])
    expect(documentEntryPath()).toBeNull()

    spy.mockReturnValue([{ name: 'not a url' } as PerformanceEntry])
    expect(documentEntryPath()).toBeNull()
  })
})
