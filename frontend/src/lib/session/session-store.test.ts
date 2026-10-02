// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { refreshRejectedAccessToken, resolveAccessToken } from '@/lib/api/access-token'
import { ApiError, UNAVAILABLE_CODE, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import { onSessionExpired } from '@/lib/session-expiry'

import { hasSessionHint, writeSessionHint } from './session-hint'
import {
  type AuthToken,
  clearSession,
  getSessionSnapshot,
  refreshSession,
  REISSUE_CONFLICT_RETRY_DELAY_MS,
  REISSUE_PATH,
  resetSessionForTests,
  restoreSession,
  setSession,
  startSession,
} from './session-store'
import type { SessionMessage } from './session-sync'

vi.mock('@/lib/api/client', () => ({ apiRequest: vi.fn() }))

// 탭 사이 통로 · 잠금을 흉내 낸다. gate 가 있으면 잠금을 그때까지 잡지 못한다(다른 탭이 재발급 중)
const sync = vi.hoisted(() => ({
  receive: null as ((message: SessionMessage) => void) | null,
  posted: [] as SessionMessage[],
  gate: null as Promise<void> | null,
}))
vi.mock('./session-sync', () => ({
  openSessionChannel: (onMessage: (message: SessionMessage) => void) => {
    sync.receive = onMessage
    return { post: (message: SessionMessage) => sync.posted.push(message), close: () => {} }
  },
  withSessionLock: async <T>(task: () => Promise<T>): Promise<T> => {
    if (sync.gate) await sync.gate
    return task()
  },
}))

const reissueMock = vi.mocked(apiRequest)

function token(accessToken: string, overrides: Partial<AuthToken> = {}): AuthToken {
  return {
    memberId: '1843956734582784',
    role: 'USER',
    accessToken,
    accessTokenExpiresIn: 900,
    pendingConsents: [],
    reportWritable: true,
    ...overrides,
  }
}

function apiError(code: string, status: number): ApiError {
  return new ApiError({ status, code, message: '거절' })
}

/** 직접 끝낼 수 있는 Promise */
function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

let stop: () => void = () => {}
let expired = vi.fn()
let stopExpired: () => void = () => {}

beforeEach(() => {
  writeSessionHint(false)
  resetSessionForTests()
  reissueMock.mockReset()
  sync.posted = []
  sync.gate = null
  stop = startSession()
  expired = vi.fn()
  stopExpired = onSessionExpired(expired)
})

afterEach(() => {
  stop()
  stopExpired()
  vi.useRealTimers()
})

describe('restoreSession', () => {
  it('힌트 쿠키가 없으면 요청 없이 비회원이다', async () => {
    await restoreSession()

    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(reissueMock).not.toHaveBeenCalled()
  })

  it('힌트가 있으면 복원 중으로 두고 재발급(POST · Authorization 없이)으로 회원이 된다', async () => {
    writeSessionHint(true)
    const reply = deferred<AuthToken>()
    reissueMock.mockReturnValueOnce(reply.promise)

    const restoring = restoreSession()
    expect(getSessionSnapshot()).toEqual({ status: 'restoring' })
    expect(reissueMock).toHaveBeenCalledExactlyOnceWith(REISSUE_PATH, {
      method: 'POST',
      auth: false,
    })

    reply.resolve(token('access-1', { reportWritable: false, pendingConsents: ['PRIVACY_POLICY'] }))
    await restoring

    expect(getSessionSnapshot()).toEqual({
      status: 'member',
      summary: {
        memberId: '1843956734582784',
        role: 'USER',
        pendingConsents: ['PRIVACY_POLICY'],
        reportWritable: false,
      },
    })
    await expect(resolveAccessToken()).resolves.toBe('access-1')
  })

  it('복원 중 요청은 복원을 기다려 그 토큰을 싣는다', async () => {
    writeSessionHint(true)
    const reply = deferred<AuthToken>()
    reissueMock.mockReturnValueOnce(reply.promise)

    const restoring = restoreSession()
    const provided = resolveAccessToken()
    reply.resolve(token('access-1'))

    await expect(provided).resolves.toBe('access-1')
    await restoring
  })

  it('복원이 재로그인(AUTH_014)이면 조용히 비회원이고 힌트를 지운다 — 만료를 알리지 않는다', async () => {
    writeSessionHint(true)
    reissueMock.mockRejectedValueOnce(apiError('AUTH_014', 401))

    await restoreSession()

    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(hasSessionHint()).toBe(false)
    expect(expired).not.toHaveBeenCalled()
    expect(sync.posted).toEqual([])
  })

  it.each([
    ['MEMBER_002', 403],
    ['MEMBER_003', 403],
  ])('복원이 %s 면 조용히 비회원 · 힌트 삭제 · 방송 없음', async (code, status) => {
    writeSessionHint(true)
    reissueMock.mockRejectedValueOnce(apiError(code, status))

    await restoreSession()

    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(hasSessionHint()).toBe(false)
    expect(expired).not.toHaveBeenCalled()
    expect(sync.posted).toEqual([])
  })

  it('복원이 두 번째 경합으로 끝나면 조용히 비회원 · 힌트 삭제 · 방송 없음', async () => {
    vi.useFakeTimers()
    writeSessionHint(true)
    reissueMock.mockRejectedValue(apiError('AUTH_016', 409))

    const restoring = restoreSession()
    await vi.advanceTimersByTimeAsync(REISSUE_CONFLICT_RETRY_DELAY_MS)
    await restoring

    expect(reissueMock).toHaveBeenCalledTimes(2)
    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(hasSessionHint()).toBe(false)
    expect(expired).not.toHaveBeenCalled()
    expect(sync.posted).toEqual([])
  })

  it('복원이 일시 장애면 비회원으로 보이되 힌트를 남긴다 (다음에 열 때 다시 해 본다)', async () => {
    writeSessionHint(true)
    reissueMock.mockRejectedValueOnce(unavailableError('network', 0))

    await restoreSession()

    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(hasSessionHint()).toBe(true)
    expect(expired).not.toHaveBeenCalled()
  })

  it('여러 번 불러도 재발급은 한 번이고, 정해진 뒤에는 다시 하지 않는다', async () => {
    writeSessionHint(true)
    reissueMock.mockResolvedValue(token('access-1'))

    await Promise.all([restoreSession(), restoreSession()])
    await restoreSession()

    expect(reissueMock).toHaveBeenCalledTimes(1)
    expect(getSessionSnapshot().status).toBe('member')
  })
})

describe('access token 공급자 · 갈아 끼우기', () => {
  it('세션이 없으면 토큰이 없다', async () => {
    await expect(resolveAccessToken()).resolves.toBeNull()
    await expect(refreshRejectedAccessToken('any')).resolves.toBeNull()
    expect(reissueMock).not.toHaveBeenCalled()
  })

  it('만료 30초 전부터는 요청 전에 먼저 재발급한다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-02T09:00:00Z'))
    setSession(token('access-1', { accessTokenExpiresIn: 900 }))

    vi.setSystemTime(new Date('2026-10-02T09:14:29Z')) // 31초 남음
    await expect(resolveAccessToken()).resolves.toBe('access-1')
    expect(reissueMock).not.toHaveBeenCalled()

    vi.setSystemTime(new Date('2026-10-02T09:14:31Z')) // 29초 남음
    reissueMock.mockResolvedValueOnce(token('access-2'))
    await expect(resolveAccessToken()).resolves.toBe('access-2')
    expect(reissueMock).toHaveBeenCalledTimes(1)
  })

  it('동시에 401 세 건이 와도 재발급은 한 번이고 모두 새 토큰을 받는다', async () => {
    setSession(token('access-1'))
    const reply = deferred<AuthToken>()
    reissueMock.mockReturnValueOnce(reply.promise)

    const results = Promise.all([
      refreshRejectedAccessToken('access-1'),
      refreshRejectedAccessToken('access-1'),
      refreshRejectedAccessToken('access-1'),
    ])
    reply.resolve(token('access-2'))

    await expect(results).resolves.toEqual(['access-2', 'access-2', 'access-2'])
    expect(reissueMock).toHaveBeenCalledTimes(1)
  })

  it('거절된 토큰을 이미 갈아 끼웠으면 재발급 없이 지금 토큰을 준다', async () => {
    setSession(token('access-1'))
    setSession(token('access-2'))

    await expect(refreshRejectedAccessToken('access-1')).resolves.toBe('access-2')
    expect(reissueMock).not.toHaveBeenCalled()
  })

  it('경합(AUTH_016)이면 이긴 탭의 쿠키가 반영될 틈을 기다렸다가 한 번 더 재발급한다', async () => {
    vi.useFakeTimers()
    setSession(token('access-1'))
    reissueMock
      .mockRejectedValueOnce(apiError('AUTH_016', 409))
      .mockResolvedValueOnce(token('access-2'))

    const refreshing = refreshRejectedAccessToken('access-1')
    await vi.advanceTimersByTimeAsync(REISSUE_CONFLICT_RETRY_DELAY_MS - 1)
    expect(reissueMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)

    await expect(refreshing).resolves.toBe('access-2')
    expect(reissueMock).toHaveBeenCalledTimes(2)
    expect(getSessionSnapshot().status).toBe('member')
  })

  it('두 번째도 경합이면 이 탭만 비우고 만료를 알린다 — 다른 탭에는 알리지 않는다', async () => {
    vi.useFakeTimers()
    setSession(token('access-1'))
    sync.posted = []
    reissueMock.mockRejectedValue(apiError('AUTH_016', 409))

    const refreshing = refreshRejectedAccessToken('access-1')
    await vi.advanceTimersByTimeAsync(REISSUE_CONFLICT_RETRY_DELAY_MS)

    await expect(refreshing).resolves.toBeNull()
    expect(reissueMock).toHaveBeenCalledTimes(2)
    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(sync.posted).toEqual([])
    expect(expired).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['AUTH_015', 401],
    ['MEMBER_002', 403],
  ])(
    '세션 중 재발급이 %s 면 비회원 · 힌트 삭제 · 다른 탭에 알림 · 만료 알림',
    async (code, status) => {
      setSession(token('access-1'))
      sync.posted = []
      reissueMock.mockRejectedValueOnce(apiError(code, status))

      await expect(refreshRejectedAccessToken('access-1')).resolves.toBeNull()

      expect(getSessionSnapshot()).toEqual({ status: 'guest' })
      expect(hasSessionHint()).toBe(false)
      expect(sync.posted).toEqual([{ type: 'signed-out', reason: 'expired' }])
      expect(expired).toHaveBeenCalledTimes(1)
      await expect(resolveAccessToken()).resolves.toBeNull()
    },
  )

  it('세션 중 재발급이 일시 장애면 공급자가 일시 장애를 던진다 — 세션은 두고 다음 요청이 다시 재발급한다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-02T09:00:00Z'))
    setSession(token('access-1'))
    vi.setSystemTime(new Date('2026-10-02T09:14:50Z')) // 만료 10초 전 → 먼저 재발급
    const failure = unavailableError('timeout', 0)
    reissueMock.mockRejectedValueOnce(failure)

    await expect(resolveAccessToken()).rejects.toBe(failure)
    expect(getSessionSnapshot().status).toBe('member')
    expect(hasSessionHint()).toBe(true)
    expect(expired).not.toHaveBeenCalled()

    reissueMock.mockResolvedValueOnce(token('access-2'))
    await expect(resolveAccessToken()).resolves.toBe('access-2')
    expect(reissueMock).toHaveBeenCalledTimes(2)
  })

  it('갈아 끼우기도 재발급이 일시 장애면 일시 장애를 던진다', async () => {
    setSession(token('access-1'))
    reissueMock.mockRejectedValueOnce(apiError('GATEWAY_003', 503))

    await expect(refreshRejectedAccessToken('access-1')).rejects.toMatchObject({
      code: 'GATEWAY_003',
    })
    expect(getSessionSnapshot().status).toBe('member')
  })

  it('분류에 없는 재발급 오류는 일시 장애로 본다 — 세션을 끝내지 않는다', async () => {
    setSession(token('access-1'))
    const odd = apiError('GATEWAY_002', 404)
    reissueMock.mockRejectedValueOnce(odd)

    const error = await refreshRejectedAccessToken('access-1').catch((caught: unknown) => caught)

    expect(error).toMatchObject({ code: UNAVAILABLE_CODE, cause: odd })
    expect(getSessionSnapshot().status).toBe('member')
    expect(hasSessionHint()).toBe(true)
    expect(expired).not.toHaveBeenCalled()
  })

  it('만료로 비운 뒤 늦게 온 재발급 실패(AUTH_014)는 버린다 — 만료를 두 번 알리지 않는다', async () => {
    setSession(token('access-1'))
    const reply = deferred<AuthToken>()
    reissueMock.mockReturnValueOnce(reply.promise)

    const refreshing = refreshRejectedAccessToken('access-1')
    clearSession('expired')
    reply.reject(apiError('AUTH_014', 401))

    await expect(refreshing).resolves.toBeNull()
    expect(expired).toHaveBeenCalledTimes(1)
    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
  })

  it('재발급을 기다리는 동안 로그아웃했으면 늦은 응답으로 세션을 되살리지 않는다', async () => {
    setSession(token('access-1'))
    const reply = deferred<AuthToken>()
    reissueMock.mockReturnValueOnce(reply.promise)

    const refreshing = refreshRejectedAccessToken('access-1')
    clearSession('logout')
    reply.resolve(token('access-2'))

    await expect(refreshing).resolves.toBeNull()
    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
  })

  it('잠금을 기다리는 동안 다른 탭이 재발급 결과를 알렸으면 재발급하지 않고 그 토큰을 쓴다', async () => {
    setSession(token('access-1'))
    const otherTab = deferred<void>()
    sync.gate = otherTab.promise

    const refreshing = refreshRejectedAccessToken('access-1')
    sync.receive?.({ type: 'signed-in', token: token('access-2') })
    otherTab.resolve()

    await expect(refreshing).resolves.toBe('access-2')
    expect(reissueMock).not.toHaveBeenCalled()
  })
})

describe('refreshSession — 회원 요약 다시 맞추기', () => {
  it('비회원이면 아무 요청도 하지 않는다', async () => {
    await refreshSession()
    expect(reissueMock).not.toHaveBeenCalled()
  })

  it('재발급 한 번으로 요약을 서버 값으로 바꾼다 (보고 권한이 빠졌으면 reportWritable false)', async () => {
    setSession(token('access-1'))
    reissueMock.mockResolvedValueOnce(token('access-2', { reportWritable: false }))

    await refreshSession()

    expect(reissueMock).toHaveBeenCalledWith(REISSUE_PATH, { method: 'POST', auth: false })
    expect(getSessionSnapshot()).toMatchObject({
      status: 'member',
      summary: { reportWritable: false },
    })
    await expect(resolveAccessToken()).resolves.toBe('access-2')
  })

  it('일시 장애면 던지지 않고 세션 · 요약을 그대로 둔다', async () => {
    setSession(token('access-1'))
    const before = getSessionSnapshot()
    reissueMock.mockRejectedValueOnce(unavailableError('timeout', 0))

    await expect(refreshSession()).resolves.toBeUndefined()
    expect(getSessionSnapshot()).toBe(before)
    expect(expired).not.toHaveBeenCalled()
  })

  it('재로그인(AUTH_014)이면 재발급처럼 세션을 끝내고 만료를 알린다', async () => {
    setSession(token('access-1'))
    reissueMock.mockRejectedValueOnce(apiError('AUTH_014', 401))

    await refreshSession()

    expect(getSessionSnapshot().status).toBe('guest')
    expect(expired).toHaveBeenCalledTimes(1)
  })
})

describe('setSession · clearSession · 탭 사이 소식', () => {
  it('세션을 정하면 힌트를 남기고 다른 탭에 알린다', () => {
    setSession(token('access-1'))

    expect(hasSessionHint()).toBe(true)
    expect(sync.posted).toEqual([{ type: 'signed-in', token: token('access-1') }])
  })

  it('요약이 같으면 스냅숏 객체를 바꾸지 않는다 (다시 그리지 않게)', () => {
    setSession(token('access-1'))
    const before = getSessionSnapshot()
    setSession(token('access-2'))
    expect(getSessionSnapshot()).toBe(before)

    setSession(token('access-3', { reportWritable: false }))
    expect(getSessionSnapshot()).not.toBe(before)
  })

  it("clearSession('logout') 은 메모리 · 힌트를 비우고 다른 탭에 알리되 만료를 알리지 않는다", async () => {
    setSession(token('access-1'))
    sync.posted = []

    clearSession('logout')

    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(hasSessionHint()).toBe(false)
    expect(sync.posted).toEqual([{ type: 'signed-out', reason: 'logout' }])
    expect(expired).not.toHaveBeenCalled()
    await expect(resolveAccessToken()).resolves.toBeNull()
  })

  it("세션이 없을 때의 clearSession('expired') 는 만료를 알리지 않는다", () => {
    clearSession('expired')
    expect(expired).not.toHaveBeenCalled()
  })

  it('다른 탭의 signed-in 을 받으면 회원이 되고 되돌려 알리지 않는다', async () => {
    sync.receive?.({ type: 'signed-in', token: token('access-9') })

    expect(getSessionSnapshot().status).toBe('member')
    expect(sync.posted).toEqual([])
    await expect(resolveAccessToken()).resolves.toBe('access-9')
  })

  it('다른 탭의 signed-out 을 받으면 비회원이 되고 만료를 알리지 않는다', () => {
    setSession(token('access-1'))
    sync.posted = []

    sync.receive?.({ type: 'signed-out', reason: 'expired' })

    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(sync.posted).toEqual([])
    expect(expired).not.toHaveBeenCalled()
  })

  it('스냅숏 · 쿠키에 access token 이 없다', () => {
    setSession(token('secret-access-token'))

    expect(JSON.stringify(getSessionSnapshot())).not.toContain('secret-access-token')
    expect(Object.keys(getSessionSnapshot())).toEqual(['status', 'summary'])
    expect(document.cookie).not.toContain('secret-access-token')
  })
})
