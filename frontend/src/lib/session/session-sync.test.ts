import { describe, expect, it, vi } from 'vitest'

import type { AuthToken } from './session-store'
import {
  openSessionChannel,
  parseSessionMessage,
  SESSION_CHANNEL_NAME,
  SESSION_LOCK_NAME,
  type SessionLocks,
  withSessionLock,
} from './session-sync'

const TOKEN: AuthToken = {
  memberId: '1843956734582784',
  role: 'USER',
  accessToken: 'access-1',
  accessTokenExpiresIn: 900,
  pendingConsents: [],
  reportWritable: true,
}

/** 이름이 같은 통로끼리 이어 주는 가짜 BroadcastChannel (자기가 보낸 것은 받지 않는다) */
function fakeChannels() {
  type Fake = {
    name: string
    onmessage: ((event: MessageEvent) => void) | null
    postMessage: (data: unknown) => void
    close: () => void
    closed: boolean
  }
  const open: Fake[] = []
  const create = (name: string) => {
    const channel: Fake = {
      name,
      onmessage: null,
      closed: false,
      postMessage(data) {
        for (const other of open) {
          if (other !== channel && other.name === name && !other.closed) {
            other.onmessage?.(new MessageEvent('message', { data }))
          }
        }
      },
      close() {
        channel.closed = true
      },
    }
    open.push(channel)
    return channel
  }
  return { create, open }
}

describe('parseSessionMessage', () => {
  it('모양이 맞는 signed-in · signed-out 만 받는다', () => {
    expect(parseSessionMessage({ type: 'signed-in', token: TOKEN })).toEqual({
      type: 'signed-in',
      token: TOKEN,
    })
    expect(parseSessionMessage({ type: 'signed-out', reason: 'logout' })).toEqual({
      type: 'signed-out',
      reason: 'logout',
    })
  })

  it.each([
    null,
    'signed-in',
    { type: 'signed-in' },
    { type: 'signed-in', token: { ...TOKEN, accessToken: '' } },
    { type: 'signed-in', token: { ...TOKEN, role: 'ROOT' } },
    { type: 'signed-in', token: { ...TOKEN, pendingConsents: [1] } },
    { type: 'signed-out', reason: 'remote' },
    { type: 'other' },
  ])('모양이 다르면 버린다 %#', (data) => {
    expect(parseSessionMessage(data)).toBeNull()
  })
})

describe('openSessionChannel', () => {
  it('BroadcastChannel 이 없으면 아무것도 하지 않는 통로다', () => {
    const channel = openSessionChannel(() => {}, null)
    expect(() => {
      channel.post({ type: 'signed-out', reason: 'logout' })
      channel.close()
    }).not.toThrow()
  })

  it('다른 탭이 보낸 소식을 받고, 모양이 다른 값은 버린다', () => {
    const { create, open } = fakeChannels()
    const received = vi.fn()
    const tabA = openSessionChannel(() => {}, create)
    openSessionChannel(received, create)
    expect(open.map((channel) => channel.name)).toEqual([
      SESSION_CHANNEL_NAME,
      SESSION_CHANNEL_NAME,
    ])

    tabA.post({ type: 'signed-in', token: TOKEN })
    open[0]?.postMessage({ type: 'signed-in', token: { accessToken: 'x' } })

    expect(received).toHaveBeenCalledExactlyOnceWith({ type: 'signed-in', token: TOKEN })
  })

  it('닫은 통로는 더 받지 않는다', () => {
    const { create } = fakeChannels()
    const received = vi.fn()
    const tabA = openSessionChannel(() => {}, create)
    const tabB = openSessionChannel(received, create)

    tabB.close()
    tabA.post({ type: 'signed-out', reason: 'expired' })

    expect(received).not.toHaveBeenCalled()
  })
})

describe('withSessionLock', () => {
  it('잠금이 없으면 바로 한다', async () => {
    await expect(withSessionLock(() => Promise.resolve('done'), null)).resolves.toBe('done')
  })

  it('같은 이름의 잠금으로 줄 세운다 — 앞 작업이 끝나야 다음 작업을 시작한다', async () => {
    let tail: Promise<unknown> = Promise.resolve()
    const names: string[] = []
    const locks: SessionLocks = {
      request: (name, callback) => {
        names.push(name)
        const run = tail.then(callback)
        tail = run.catch(() => {})
        return run
      },
    }
    const order: string[] = []
    let releaseFirst = () => {}
    const first = withSessionLock(
      () =>
        new Promise<void>((resolve) => {
          order.push('first:start')
          releaseFirst = () => {
            order.push('first:end')
            resolve()
          }
        }),
      locks,
    )
    const second = withSessionLock(() => {
      order.push('second:start')
      return Promise.resolve()
    }, locks)

    await Promise.resolve()
    await Promise.resolve()
    expect(order).toEqual(['first:start'])
    releaseFirst()
    await Promise.all([first, second])

    expect(order).toEqual(['first:start', 'first:end', 'second:start'])
    expect(names).toEqual([SESSION_LOCK_NAME, SESSION_LOCK_NAME])
  })
})
