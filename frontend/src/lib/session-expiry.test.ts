import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  clearSessionExpiring,
  isSessionExpiring,
  notifySessionExpired,
  onSessionExpired,
} from './session-expiry'

describe('session-expiry', () => {
  afterEach(() => {
    clearSessionExpiring()
  })

  it('알리면 구독한 쪽을 모두 부른다', () => {
    const first = vi.fn()
    const second = vi.fn()
    const offFirst = onSessionExpired(first)
    const offSecond = onSessionExpired(second)

    notifySessionExpired()
    expect(first).toHaveBeenCalledTimes(1)
    expect(second).toHaveBeenCalledTimes(1)

    offFirst()
    offSecond()
  })

  it('구독을 끊으면 더 부르지 않는다', () => {
    const listener = vi.fn()
    const off = onSessionExpired(listener)
    off()

    notifySessionExpired()
    expect(listener).not.toHaveBeenCalled()
  })

  it('구독한 쪽이 없으면 아무 일도 하지 않는다', () => {
    expect(() => notifySessionExpired()).not.toThrow()
  })

  it('알리면 받는 쪽을 부르기 전에 만료 진행 표시를 켜고, 지우면 끈다', () => {
    let seen: boolean | null = null
    const off = onSessionExpired(() => {
      seen = isSessionExpiring()
    })
    expect(isSessionExpiring()).toBe(false)

    notifySessionExpired()
    expect(seen).toBe(true)
    expect(isSessionExpiring()).toBe(true)

    clearSessionExpiring()
    expect(isSessionExpiring()).toBe(false)
    off()
  })
})
